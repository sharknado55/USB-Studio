package com.example

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.FileBlockTransport
import com.example.data.UsbDiskEngine
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.viewmodel.UsbStudioViewModel
import com.example.viewmodel.WorkbenchTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UsbStudioViewModelTest {

    private lateinit var app: Application
    private lateinit var blockFile: File
    private lateinit var viewModel: UsbStudioViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "otg_disks").deleteRecursively()
        File(app.filesDir, "otg_mounts").deleteRecursively()
        File(app.filesDir, "iso_images").deleteRecursively()
        TestSafDocumentsProvider.register(app.cacheDir)

        blockFile = File(app.cacheDir, "vm_test_block.img").apply { delete() }
        val engine = UsbDiskEngine(
            context = app,
            blockTransportFactory = { FileBlockTransport(blockFile, totalBlocks = 131072L) }
        )
        viewModel = UsbStudioViewModel(app, engine)
        viewModel.refreshUsbBus()
    }

    private suspend fun awaitCondition(timeoutMs: Long = 10000L, predicate: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(30)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    private suspend fun mountSafTestDrive(volumeKey: String = "TEST-USB1") {
        val uri = Uri.parse("content://${TestSafDocumentsProvider.AUTHORITY}/tree/$volumeKey%3A")
        viewModel.mountSafVolume(uri)
        awaitCondition { viewModel.uiState.value.drives.any { it.safTreeUri == uri } }
    }

    @Test
    fun `initial bus scan discovers zero dummy drives, mounts real SAF volumes, switches target, and toggles theme`() = runBlocking {
        awaitCondition { !viewModel.uiState.value.isScanningBus }
        val emptyState = viewModel.uiState.value
        assertTrue("Zero dummy drives should exist initially", emptyState.drives.isEmpty())
        assertNull(emptyState.selectedDrive)
        assertTrue(emptyState.darkTheme)
        assertTrue(emptyState.logs.isNotEmpty())

        viewModel.toggleTheme()
        assertFalse(viewModel.uiState.value.darkTheme)
        viewModel.toggleTheme()
        assertTrue(viewModel.uiState.value.darkTheme)

        mountSafTestDrive("VOL-0001")
        mountSafTestDrive("VOL-0002")
        assertEquals(2, viewModel.uiState.value.drives.size)

        val secondDrive = viewModel.uiState.value.drives[1]
        viewModel.selectDrive(secondDrive.id)
        assertEquals(secondDrive.id, viewModel.uiState.value.selectedDriveId)
        assertNotNull(viewModel.uiState.value.bannerMessage)

        viewModel.dismissBanner()
        assertNull(viewModel.uiState.value.bannerMessage)
    }

    @Test
    fun `file manager tracks SAF documentId stack across nested folders, edits exact URI, renames, and deletes`() = runBlocking {
        awaitCondition { !viewModel.uiState.value.isScanningBus }
        mountSafTestDrive("FILES-VOL")
        viewModel.selectTab(WorkbenchTab.FILES)

        // Create folder & navigate into it using openDirectory (pushes real SAF documentId)
        viewModel.createNewFolder("QA_Suite")
        awaitCondition { viewModel.uiState.value.fileEntries.any { it.name == "QA_Suite" } }
        val qaFolder = viewModel.uiState.value.fileEntries.first { it.name == "QA_Suite" }
        assertNotNull(qaFolder.documentId)
        assertEquals(-1L, qaFolder.sizeBytes)

        viewModel.openDirectory(qaFolder)
        awaitCondition {
            viewModel.uiState.value.currentPath == "/QA_Suite" &&
                viewModel.uiState.value.currentSafDocumentId == qaFolder.documentId &&
                viewModel.uiState.value.fileEntries.isEmpty()
        }

        // Create two files & test search filtering
        viewModel.createOrSaveTextFile("alpha.txt", "Alpha Content")
        awaitCondition { viewModel.uiState.value.fileEntries.any { it.name == "alpha.txt" } }
        viewModel.createOrSaveTextFile("beta.log", "Beta Content")
        awaitCondition { viewModel.uiState.value.fileEntries.size == 2 }

        viewModel.updateFileSearchQuery("alpha")
        assertEquals(1, viewModel.uiState.value.filteredFiles.size)
        assertEquals("alpha.txt", viewModel.uiState.value.filteredFiles.first().name)
        viewModel.updateFileSearchQuery("")
        assertEquals(2, viewModel.uiState.value.filteredFiles.size)

        // Open file for editing, verify content, and overwrite exact documentUri
        val alphaEntry = viewModel.uiState.value.fileEntries.first { it.name == "alpha.txt" }
        viewModel.openFileForEditing(alphaEntry)
        awaitCondition { viewModel.uiState.value.selectedFileForEdit?.name == "alpha.txt" }
        assertEquals("Alpha Content", viewModel.uiState.value.editingFileText)

        viewModel.createOrSaveTextFile("alpha.txt", "Updated Alpha", existingEntry = alphaEntry)
        awaitCondition { viewModel.uiState.value.selectedFileForEdit == null }
        assertEquals(2, viewModel.uiState.value.fileEntries.size) // No duplicate created!

        // Re-open to verify real overwrite persisted
        val refreshedAlpha = viewModel.uiState.value.fileEntries.first { it.name == "alpha.txt" }
        viewModel.openFileForEditing(refreshedAlpha)
        awaitCondition { viewModel.uiState.value.editingFileText == "Updated Alpha" }
        viewModel.closeFileEditor()

        // Inspect file hex
        viewModel.inspectFileHex(refreshedAlpha)
        awaitCondition { viewModel.uiState.value.activeHexInspection != null }
        assertTrue(viewModel.uiState.value.activeHexInspection!!.bytes.isNotEmpty())
        viewModel.closeHexInspector()
        assertNull(viewModel.uiState.value.activeHexInspection)

        // Rename & Delete
        viewModel.renameFileEntry(refreshedAlpha, "gamma.txt")
        awaitCondition { viewModel.uiState.value.fileEntries.any { it.name == "gamma.txt" } }
        val gammaEntry = viewModel.uiState.value.fileEntries.first { it.name == "gamma.txt" }
        viewModel.deleteFileEntry(gammaEntry)
        awaitCondition { viewModel.uiState.value.fileEntries.none { it.name == "gamma.txt" } }

        // Navigate up back to root
        viewModel.navigateUpDirectory()
        awaitCondition {
            viewModel.uiState.value.currentPath == "/" &&
                viewModel.uiState.value.safFolderStack.isEmpty()
        }
    }

    @Test
    fun `formatter adapts cluster sizes and writes real filesystem structures cleanly`() = runBlocking {
        awaitCondition { !viewModel.uiState.value.isScanningBus }
        mountSafTestDrive("FMT-VOL")

        viewModel.updateFormatConfig(
            label = "LINUX_TEST",
            fileSystem = FileSystemType.EXT4,
            scheme = PartitionScheme.GPT,
            quick = true
        )
        assertEquals(4096, viewModel.uiState.value.formatClusterSize)
        assertEquals("LINUX_TEST", viewModel.uiState.value.formatLabel)

        viewModel.executeFormatOperation()
        awaitCondition(timeoutMs = 10000L) {
            !viewModel.uiState.value.formatStatus.isRunning &&
                viewModel.uiState.value.formatStatus.completedVolumeUuid != null
        }

        val state = viewModel.uiState.value
        assertEquals(1.0f, state.formatStatus.progress, 0.001f)
        assertEquals(FileSystemType.EXT4, state.selectedDrive?.primaryFileSystem)
        assertEquals("LINUX_TEST", state.selectedDrive?.partitions?.first()?.label)
    }

    @Test
    fun `iso writer flashes user-selected local image and verifies SHA-256`() = runBlocking {
        awaitCondition { !viewModel.uiState.value.isScanningBus }
        mountSafTestDrive("ISO-VOL")

        val tempIso = File(app.cacheDir, "custom_recovery.iso")
        val bytes = ByteArray(128 * 1024) { (it % 199).toByte() }
        tempIso.writeBytes(bytes)

        viewModel.selectCustomIsoUri(Uri.fromFile(tempIso), "custom_recovery.iso", tempIso.length())
        assertEquals("custom_recovery.iso", viewModel.uiState.value.customIsoName)

        viewModel.toggleVerifyIsoSha256(true)
        viewModel.executeIsoFlash()

        awaitCondition(timeoutMs = 15000L) {
            !viewModel.uiState.value.isoFlashStatus.isRunning &&
                viewModel.uiState.value.isoFlashStatus.sha256Hex.length == 64
        }

        val flash = viewModel.uiState.value.isoFlashStatus
        assertEquals(true, flash.verifiedSuccess)
        assertEquals(1.0f, flash.progress, 0.001f)
        assertEquals(FileSystemType.ISO9660, viewModel.uiState.value.selectedDrive?.primaryFileSystem)
        tempIso.delete()
        Unit
    }

    @Test
    fun `partition manager deletes partition into unallocated space, allocates new partition, toggles boot flag, resets, and commits`() = runBlocking {
        awaitCondition { !viewModel.uiState.value.isScanningBus }
        mountSafTestDrive("PART-VOL")
        viewModel.selectTab(WorkbenchTab.PARTITIONS)

        val initialPartitions = viewModel.uiState.value.stagedPartitions
        assertEquals(1, initialPartitions.size)

        val firstPart = initialPartitions.first()
        viewModel.deleteStagedPartition(firstPart.id)
        val afterDelete = viewModel.uiState.value.stagedPartitions
        assertTrue(viewModel.uiState.value.hasUncommittedPartitionChanges)
        assertEquals(1, afterDelete.size)
        assertEquals(FileSystemType.UNALLOCATED, afterDelete.first().fileSystem)

        viewModel.createPartitionInUnallocated(
            unallocatedId = afterDelete.first().id,
            label = "MEDIA_VOL",
            fileSystem = FileSystemType.EXFAT,
            sizeMb = 32L,
            bootable = true
        )
        val created = viewModel.uiState.value.stagedPartitions.first { it.label == "MEDIA_VOL" }
        assertTrue(created.isBootable)
        assertTrue("BOOT" in created.flags)

        viewModel.togglePartitionBootFlag(created.id)
        assertFalse(viewModel.uiState.value.stagedPartitions.first { it.id == created.id }.isBootable)

        viewModel.resetStagedPartitionChanges()
        assertFalse(viewModel.uiState.value.hasUncommittedPartitionChanges)
        assertEquals(firstPart.label, viewModel.uiState.value.stagedPartitions.first().label)

        viewModel.setStagedPartitionScheme(PartitionScheme.MBR)
        assertTrue(viewModel.uiState.value.hasUncommittedPartitionChanges)
        viewModel.applyStagedPartitionTable()
        awaitCondition { !viewModel.uiState.value.hasUncommittedPartitionChanges }
        assertEquals(PartitionScheme.MBR, viewModel.uiState.value.selectedDrive?.partitionScheme)
    }

    @Test
    fun `inspectDriveSector loads raw LBA sector into activeHexInspection`() = runBlocking {
        awaitCondition { !viewModel.uiState.value.isScanningBus }
        mountSafTestDrive("HEX-VOL")

        // Write valid MBR first via commitPartitionTable
        viewModel.setStagedPartitionScheme(PartitionScheme.MBR)
        viewModel.applyStagedPartitionTable()
        awaitCondition { !viewModel.uiState.value.hasUncommittedPartitionChanges }

        viewModel.inspectDriveSector(0L, "LBA 0 MBR")
        awaitCondition { viewModel.uiState.value.activeHexInspection != null }

        val hex = viewModel.uiState.value.activeHexInspection!!
        assertEquals(512, hex.bytes.size)
        assertEquals(0x55.toByte(), hex.bytes[510])
        assertEquals(0xAA.toByte(), hex.bytes[511])
    }
}

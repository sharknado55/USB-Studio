package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.BlockDiskStructures
import com.example.data.Fat32FileSystemReader
import com.example.data.FileBlockTransport
import com.example.data.UsbDiskEngine
import com.example.model.DriveConnectionType
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.model.UsbDriveDevice
import com.example.model.UsbPartition
import com.example.ui.components.formatHexDump
import com.example.util.formatBytes
import com.example.util.formatTimestamp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UsbDiskEngineTest {

    private lateinit var context: Context
    private lateinit var blockBackingFile: File
    private lateinit var engine: UsbDiskEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "otg_disks").deleteRecursively()
        File(context.filesDir, "otg_mounts").deleteRecursively()
        File(context.filesDir, "iso_images").deleteRecursively()
        TestSafDocumentsProvider.register(context.cacheDir)

        blockBackingFile = File(context.cacheDir, "test_usb_scsi_device.img").apply { delete() }
        engine = UsbDiskEngine(
            context = context,
            blockTransportFactory = { drive ->
                if (drive.supportsRawBlockIo) {
                    FileBlockTransport(blockBackingFile, totalBlocks = 262144L) // 128 MB test USB block device
                } else {
                    null
                }
            }
        )
    }

    private fun createHardwareDrive(): UsbDriveDevice = UsbDriveDevice(
        id = "usb_hw_test_1",
        name = "Test USB OTG Flash",
        vendorName = "Test Vendor",
        connectionType = DriveConnectionType.USB_OTG_HARDWARE,
        usbDeviceName = "/dev/bus/usb/001/002",
        vendorId = 0x0781,
        productId = 0x5583,
        serialNumber = "TEST123456",
        usbSpec = "3.0",
        protocolName = "BOT / Bulk-Only (0x50) • SCSI Direct",
        maxPowerMa = 300,
        hasUsbPermission = true,
        totalBytes = 262144L * 512L,
        logicalBlockSize = 512,
        partitionScheme = PartitionScheme.MBR,
        partitions = listOf(
            UsbPartition(
                id = "p1",
                index = 1,
                label = "USB_TEST",
                fileSystem = FileSystemType.FAT32,
                startLba = 2048L,
                sectorCount = 128000L,
                sizeBytes = 128000L * 512L,
                usedBytes = 0L
            )
        ),
        safTreeUri = null,
        backingImagePath = "",
        mountRootPath = ""
    )

    private suspend fun mountTestSafDrive(volumeId: String = "ABCD-1234"): UsbDriveDevice {
        val treeUri = Uri.parse("content://${TestSafDocumentsProvider.AUTHORITY}/tree/$volumeId%3A")
        return engine.createSafMountedDrive(treeUri)
    }

    @Test
    fun `discoverConnectedDrives returns empty list when no hardware or SAF volume is attached and purges legacy dummy files`() = runBlocking {
        val legacyImg = File(File(context.filesDir, "otg_disks").apply { mkdirs() }, "sandisk_extreme_otg.img")
        legacyImg.writeText("stale")
        assertTrue(legacyImg.exists())

        val drives = engine.discoverConnectedDrives(emptyList())
        assertTrue("No dummy or simulated drives may be injected", drives.isEmpty())
        assertFalse("Legacy loopback files must be purged", legacyImg.exists())
    }

    @Test
    fun `live SAF DocumentsProvider supports full CRUD, nested documentId navigation, exact overwrite, rename, delete, and volume wipe`() = runBlocking {
        val drive = mountTestSafDrive("SAF-LIVE-1")

        assertEquals(DriveConnectionType.SAF_MOUNTED_VOLUME, drive.connectionType)
        assertTrue(drive.hasUsbPermission)
        assertTrue(drive.supportsFileWrites)
        assertFalse(drive.supportsRawBlockIo)
        assertTrue(engine.listDirectory(drive, "/").isEmpty())

        // Create folder via real DocumentsContract
        val folder = engine.createFolder(drive, "/", "Project_Data")
        assertNotNull(folder.documentId)
        assertNotNull(folder.documentUri)

        // Create root file with custom extension (must NOT be mangled to .txt!)
        engine.writeTextFile(drive, "/", "settings.cfg", "mode=usb")

        val rootEntries = engine.listDirectory(drive, "/")
        assertEquals(2, rootEntries.size)
        assertTrue("Directories must sort before files", rootEntries.first().isDirectory)
        assertEquals("Project_Data", rootEntries.first().name)
        assertEquals(-1L, rootEntries.first().sizeBytes) // Directories must NOT show 4096 items!
        assertEquals("settings.cfg", rootEntries.last().name)

        // Create nested file inside Project_Data using exact documentId
        engine.writeTextFile(
            drive = drive,
            parentRelativePath = "/Project_Data",
            fileName = "notes.txt",
            content = "Initial Payload",
            parentDocumentId = folder.documentId
        )

        val nested = engine.listDirectory(drive, "/Project_Data", safDocumentId = folder.documentId)
        assertEquals(1, nested.size)
        val notesEntry = nested.first()
        assertEquals("notes.txt", notesEntry.name)
        assertNotNull(notesEntry.documentUri)
        assertEquals("Initial Payload", engine.readFileBytes(drive, notesEntry).toString(Charsets.UTF_8))

        // Overwrite existing file directly using existingDocumentUri (no duplicates, proper truncation!)
        engine.writeTextFile(
            drive = drive,
            parentRelativePath = "/Project_Data",
            fileName = "notes.txt",
            content = "Short",
            parentDocumentId = folder.documentId,
            existingDocumentUri = notesEntry.documentUri
        )
        val afterEdit = engine.listDirectory(drive, "/Project_Data", safDocumentId = folder.documentId)
        assertEquals(1, afterEdit.size)
        assertEquals("Short", engine.readFileBytes(drive, afterEdit.first()).toString(Charsets.UTF_8))

        // Rename & delete using exact documentUri
        assertTrue(engine.renameEntry(drive, afterEdit.first(), "renamed.md"))
        val afterRename = engine.listDirectory(drive, "/Project_Data", safDocumentId = folder.documentId)
        assertTrue(afterRename.any { it.name == "renamed.md" })

        assertTrue(engine.deleteEntry(drive, afterRename.first()))
        assertTrue(engine.listDirectory(drive, "/Project_Data", safDocumentId = folder.documentId).isEmpty())

        // SAF-only format cleanly wipes all real files & folders on the volume
        val (_, uuid) = engine.formatDriveOrPartition(
            drive = drive,
            targetPartitionId = null,
            newLabel = "IGNORED",
            fileSystem = FileSystemType.FAT32,
            clusterSize = 4096,
            partitionScheme = PartitionScheme.MBR,
            quickFormat = true,
            wipeFullDrive = true
        ) { _, _ -> }
        assertEquals("SAF-WIPED", uuid)
        assertTrue("SAF wipe must remove all items", engine.listDirectory(drive, "/").isEmpty())
    }

    @Test
    fun `raw USB block formatter writes real FAT32 structures and refuses stub filesystems`() = runBlocking {
        val hwDrive = createHardwareDrive()

        // 1. Format as FAT32 + MBR -> verify real FAT32 volume is readable by Fat32FileSystemReader!
        val progressEvents = mutableListOf<Float>()
        val (fat32Drive, fat32Uuid) = engine.formatDriveOrPartition(
            drive = hwDrive,
            targetPartitionId = null,
            newLabel = "BOOT_FAT",
            fileSystem = FileSystemType.FAT32,
            clusterSize = 4096,
            partitionScheme = PartitionScheme.MBR,
            quickFormat = false,
            wipeFullDrive = true
        ) { p, _ -> progressEvents.add(p) }

        assertEquals(1.0f, progressEvents.last(), 0.001f)
        assertTrue(fat32Uuid.contains("-"))
        val fat32Mbr = engine.readRawSector(fat32Drive, 0L)
        assertEquals(FileSystemType.FAT32.code, fat32Mbr[0x1BE + 4])
        val startLba = fat32Drive.partitions.first().startLba
        val fat32Vbr = engine.readRawSector(fat32Drive, startLba)
        assertEquals("MSDOS5.0", String(fat32Vbr, 3, 8, Charsets.US_ASCII))
        assertEquals("FAT32   ", String(fat32Vbr, 82, 8, Charsets.US_ASCII))

        // Direct FAT32 SCSI reader must cleanly read the formatted empty root directory
        val fatEntries = engine.listDirectory(fat32Drive, "/")
        assertTrue(fatEntries.isEmpty())

        // Re-read partition layout from raw sectors via BlockDiskStructures
        FileBlockTransport(blockBackingFile, totalBlocks = 262144L).use { t ->
            val (probedScheme, probedParts) = BlockDiskStructures.readPartitionLayout(t)
            assertEquals(PartitionScheme.MBR, probedScheme)
            assertEquals(FileSystemType.FAT32, probedParts.first().fileSystem)
            assertEquals("BOOT_FAT", probedParts.first().label)
            // Verify Fat32FileSystemReader parses cleanly
            assertTrue(Fat32FileSystemReader(t, probedParts.first().startLba).listDirectory("/").isEmpty())
        }

        // 2. Re-format as FAT32 + GPT: primary and backup GPT headers are both written
        val (gptDrive, _) = engine.formatDriveOrPartition(
            drive = fat32Drive,
            targetPartitionId = null,
            newLabel = "GPT_FAT",
            fileSystem = FileSystemType.FAT32,
            clusterSize = 4096,
            partitionScheme = PartitionScheme.GPT,
            quickFormat = true,
            wipeFullDrive = true
        ) { _, _ -> }
        assertEquals(PartitionScheme.GPT, gptDrive.partitionScheme)
        assertEquals("EFI PART", String(engine.readRawSector(gptDrive, 262143L), 0, 8, Charsets.US_ASCII))

        // 3. exFAT, EXT4 and NTFS writers only made stub headers, so they are refused before any write
        for (fs in listOf(FileSystemType.EXFAT, FileSystemType.EXT4, FileSystemType.NTFS)) {
            val failed = runCatching {
                engine.formatDriveOrPartition(
                    drive = gptDrive,
                    targetPartitionId = null,
                    newLabel = "NOPE",
                    fileSystem = fs,
                    clusterSize = 4096,
                    partitionScheme = PartitionScheme.MBR,
                    quickFormat = true,
                    wipeFullDrive = true
                ) { _, _ -> }
            }.isFailure
            assertTrue("${fs.label} format must be refused", failed)
        }
        assertEquals("EFI PART", String(engine.readRawSector(gptDrive, 1L), 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `formatting one partition leaves the other partitions untouched`() = runBlocking {
        val blank = createHardwareDrive().copy(partitions = emptyList())
        val (formatted, _) = engine.formatDriveOrPartition(
            drive = blank, targetPartitionId = null, newLabel = "WHOLE", fileSystem = FileSystemType.FAT32,
            clusterSize = 4096, partitionScheme = PartitionScheme.MBR, quickFormat = true, wipeFullDrive = true
        ) { _, _ -> }
        val split = listOf(
            UsbPartition(id = "a", index = 1, label = "FIRST", fileSystem = FileSystemType.FAT32,
                startLba = 2048L, sectorCount = 100000L, sizeBytes = 100000L * 512L, usedBytes = 0L),
            UsbPartition(id = "b", index = 2, label = "SECOND", fileSystem = FileSystemType.FAT32,
                startLba = 102048L, sectorCount = 100000L, sizeBytes = 100000L * 512L, usedBytes = 0L)
        )
        val twoParts = engine.commitPartitionTable(formatted, PartitionScheme.MBR, split)
        val firstVbrBefore = engine.readRawSector(twoParts, 2048L)
        val second = twoParts.partitions.first { it.startLba == 102048L }

        engine.formatDriveOrPartition(
            drive = twoParts, targetPartitionId = second.id, newLabel = "SECOND2", fileSystem = FileSystemType.FAT32,
            clusterSize = 4096, partitionScheme = PartitionScheme.MBR, quickFormat = false, wipeFullDrive = false
        ) { _, _ -> }

        assertTrue(firstVbrBefore.contentEquals(engine.readRawSector(twoParts, 2048L)))
        assertEquals("MSDOS5.0", String(engine.readRawSector(twoParts, 102048L), 3, 8, Charsets.US_ASCII))
    }

    @Test
    fun `writes are refused when the disk no longer matches the app's partition list`() = runBlocking {
        val stale = createHardwareDrive() // claims a partition the blank disk does not have
        val result = runCatching {
            engine.formatDriveOrPartition(
                drive = stale, targetPartitionId = "p1", newLabel = "X", fileSystem = FileSystemType.FAT32,
                clusterSize = 4096, partitionScheme = PartitionScheme.MBR, quickFormat = true, wipeFullDrive = false
            ) { _, _ -> }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `flashIsoImageToDrive streams image directly to raw USB blocks and passes SHA-256 read-back verification`() = runBlocking {
        val hwDrive = createHardwareDrive()
        val sampleIso = File(context.cacheDir, "test_boot_image.iso")
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        payload[510] = 0x55.toByte()
        payload[511] = 0xAA.toByte()
        System.arraycopy("CD001".toByteArray(Charsets.US_ASCII), 0, payload, 0x8001, 5)
        sampleIso.writeBytes(payload)

        var lastProgress = 0f
        val (flashedDrive, sha256, verified) = engine.flashIsoImageToDrive(
            drive = hwDrive,
            externalUri = Uri.fromFile(sampleIso),
            externalName = "test_boot_image.iso",
            verifyAfterWrite = true
        ) { _, progress, _, _, _ ->
            lastProgress = progress
        }

        assertTrue("SHA-256 verification must pass on read-back", verified)
        assertEquals(64, sha256.length)
        assertTrue(lastProgress >= 0.99f)
        assertEquals(FileSystemType.ISO9660, flashedDrive.primaryFileSystem)

        val lba0 = engine.readRawSector(flashedDrive, 0L)
        assertEquals(0x55.toByte(), lba0[510])
        assertEquals(0xAA.toByte(), lba0[511])

        val pvd = engine.readRawSector(flashedDrive, 64L)
        assertEquals("CD001", String(pvd, 1, 5, Charsets.US_ASCII))

        sampleIso.delete()
        Unit
    }

    @Test
    fun `commitPartitionTable converts between GPT and MBR and preserves boot flags on real block sectors`() = runBlocking {
        val hwDrive = createHardwareDrive().copy(partitions = emptyList()) // blank disk
        val customParts = listOf(
            UsbPartition(
                id = "p_custom_1",
                index = 1,
                label = "BOOT_PART",
                fileSystem = FileSystemType.FAT32,
                startLba = 2048L,
                sectorCount = 80000L,
                sizeBytes = 80000L * 512L,
                usedBytes = 0L,
                isBootable = true,
                flags = listOf("BOOT")
            ),
            UsbPartition(
                id = "p_custom_2",
                index = 2,
                label = "DATA_PART",
                fileSystem = FileSystemType.FAT32,
                startLba = 82048L,
                sectorCount = 80000L,
                sizeBytes = 80000L * 512L,
                usedBytes = 0L,
                isBootable = false
            )
        )

        val mbrCommitted = engine.commitPartitionTable(hwDrive, PartitionScheme.MBR, customParts)
        assertEquals(PartitionScheme.MBR, mbrCommitted.partitionScheme)
        val mbrLba0 = engine.readRawSector(mbrCommitted, 0L)
        assertEquals(0x80.toByte(), mbrLba0[0x1BE])
        assertEquals(FileSystemType.FAT32.code, mbrLba0[0x1BE + 4])
        assertEquals(0x00.toByte(), mbrLba0[0x1BE + 16])
        assertEquals(FileSystemType.FAT32.code, mbrLba0[0x1BE + 16 + 4])

        val gptCommitted = engine.commitPartitionTable(mbrCommitted, PartitionScheme.GPT, customParts)
        assertEquals(PartitionScheme.GPT, gptCommitted.partitionScheme)
        val gptLba0 = engine.readRawSector(gptCommitted, 0L)
        val gptLba1 = engine.readRawSector(gptCommitted, 1L)
        assertEquals(0xEE.toByte(), gptLba0[0x1BE + 4])
        assertEquals("EFI PART", String(gptLba1, 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `FormatUtils and formatHexDump handle all edge cases cleanly`() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("0 B", formatBytes(-100L))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("2.5 MB", formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("64.00 GB", formatBytes(64L * 1024 * 1024 * 1024))
        assertEquals("1.00 TB", formatBytes(1024L * 1024 * 1024 * 1024))

        assertEquals("—", formatTimestamp(0L))
        assertNotEquals("—", formatTimestamp(1_700_000_000_000L))

        val sector = ByteArray(512) { (it % 256).toByte() }
        val dump = formatHexDump(sector, baseOffset = 0x200L)
        assertEquals(32, dump.size)
        assertTrue(dump.first().startsWith("00000200"))
        assertTrue(dump.last().startsWith("000003F0"))
    }
}

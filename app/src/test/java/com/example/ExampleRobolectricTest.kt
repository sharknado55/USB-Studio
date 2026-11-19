package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.BlockDiskStructures
import com.example.data.FileBlockTransport
import com.example.data.UsbDiskEngine
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.model.UsbPartition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name and verify zero dummy drives and real GPT block structure roundtrip`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("USB Studio", appName)

        TestSafDocumentsProvider.register(context.cacheDir)

        val engine = UsbDiskEngine(context)
        // Zero dummy/simulated drives should exist when no hardware is attached
        val initialDrives = engine.discoverConnectedDrives(emptyList())
        assertTrue(initialDrives.isEmpty())

        // Verify real BlockDiskStructures GPT header & CRC32 round-trip over BlockDeviceTransport
        val blockFile = File(context.cacheDir, "example_block_test.img").apply { delete() }
        FileBlockTransport(blockFile, totalBlocks = 131072L).use { transport ->
            val part = UsbPartition(
                id = "p1",
                index = 1,
                label = "USB_BOOT",
                fileSystem = FileSystemType.FAT32,
                startLba = 2048L,
                sectorCount = 100000L,
                sizeBytes = 100000L * 512L,
                usedBytes = 0L
            )
            BlockDiskStructures.writePartitionTable(transport, PartitionScheme.GPT, listOf(part))
            BlockDiskStructures.writeFileSystemAtPartition(
                transport = transport,
                startLba = 2048L,
                sectorCount = 100000L,
                fileSystem = FileSystemType.FAT32,
                volumeLabel = "USB_BOOT",
                clusterSize = 4096,
                uuid = "ABCD-1234"
            )

            val lba0 = transport.readBlocks(0L, 1)
            assertEquals(0x55.toByte(), lba0[510])
            assertEquals(0xAA.toByte(), lba0[511])
            assertEquals(0xEE.toByte(), lba0[0x1BE + 4])

            val lba1 = transport.readBlocks(1L, 1)
            assertEquals("EFI PART", String(lba1, 0, 8, Charsets.US_ASCII))

            val (scheme, parts) = BlockDiskStructures.readPartitionLayout(transport)
            assertEquals(PartitionScheme.GPT, scheme)
            assertTrue(parts.any { it.label == "USB_BOOT" && it.fileSystem == FileSystemType.FAT32 })
        }

        // Mount an external SAF volume via real DocumentsProvider
        val mountedDrive = engine.createSafMountedDrive(
            Uri.parse("content://${TestSafDocumentsProvider.AUTHORITY}/tree/USB1-0001%3A")
        )
        assertTrue(mountedDrive.supportsFileWrites)
        assertTrue(engine.listDirectory(mountedDrive, "/").isEmpty())

        blockFile.delete()
        Unit
    }
}

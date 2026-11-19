package com.example

import com.example.data.BlockDiskStructures
import com.example.data.FileBlockTransport
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.model.UsbPartition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plain JVM tests for the partition-table and FAT32 safety checks (security review fixes).
 */
class BlockDiskSafetyTest {

    private lateinit var file: File
    private lateinit var transport: FileBlockTransport
    private val totalBlocks = 262_144L // 128 MiB

    @Before
    fun setUp() {
        file = File.createTempFile("block_safety", ".img")
        transport = FileBlockTransport(file, totalBlocks)
    }

    @After
    fun tearDown() {
        transport.close()
        file.delete()
    }

    private fun part(id: String, start: Long, count: Long, fs: FileSystemType = FileSystemType.FAT32) = UsbPartition(
        id = id, index = 1, label = id.uppercase(), fileSystem = fs,
        startLba = start, sectorCount = count, sizeBytes = count * 512, usedBytes = 0L
    )

    private inline fun assertIoFails(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            return
        }
        throw AssertionError("Expected IOException")
    }

    @Test
    fun `GPT write includes a valid backup header at the last LBA`() {
        BlockDiskStructures.writePartitionTable(transport, PartitionScheme.GPT, listOf(part("a", 2048, 100_000)))
        val backup = transport.readBlocks(totalBlocks - 1, 1)
        assertEquals("EFI PART", String(backup, 0, 8, Charsets.US_ASCII))
        val bb = ByteBuffer.wrap(backup).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(totalBlocks - 1, bb.getLong(24)) // MyLBA
        assertEquals(1L, bb.getLong(32)) // AlternateLBA
    }

    @Test
    fun `GPT rewrite keeps type GUID and unique GUID`() {
        BlockDiskStructures.writePartitionTable(transport, PartitionScheme.GPT, listOf(part("a", 2048, 100_000)))
        val (_, first) = BlockDiskStructures.readPartitionLayout(transport)
        val original = first.first { it.fileSystem != FileSystemType.UNALLOCATED }

        BlockDiskStructures.writePartitionTable(transport, PartitionScheme.GPT, first)
        val (_, second) = BlockDiskStructures.readPartitionLayout(transport)
        val reread = second.first { it.fileSystem != FileSystemType.UNALLOCATED }
        assertEquals(original.gptUniqueGuid, reread.gptUniqueGuid)
        assertEquals(original.gptTypeGuid, reread.gptTypeGuid)
    }

    @Test
    fun `MBR rewrite keeps an unknown type byte`() {
        val esp = part("esp", 2048, 100_000).copy(mbrTypeCode = 0xEF)
        BlockDiskStructures.writePartitionTable(transport, PartitionScheme.MBR, listOf(esp))
        val (_, parts) = BlockDiskStructures.readPartitionLayout(transport)
        BlockDiskStructures.writePartitionTable(transport, PartitionScheme.MBR, parts)
        assertEquals(0xEF.toByte(), transport.readBlocks(0, 1)[0x1BE + 4])
    }

    @Test
    fun `MBR refuses more than four partitions and extended partitions`() {
        val five = (0 until 5).map { part("p$it", 2048L + it * 40_000L, 39_000L) }
        assertIoFails { BlockDiskStructures.writePartitionTable(transport, PartitionScheme.MBR, five) }
        val extended = part("ext", 2048, 100_000).copy(mbrTypeCode = 0x0F)
        assertIoFails { BlockDiskStructures.writePartitionTable(transport, PartitionScheme.MBR, listOf(extended)) }
    }

    @Test
    fun `layout validation refuses overlap and partitions past the end`() {
        assertIoFails {
            BlockDiskStructures.writePartitionTable(
                transport, PartitionScheme.GPT, listOf(part("a", 2048, 100_000), part("b", 50_000, 10_000))
            )
        }
        assertIoFails {
            BlockDiskStructures.writePartitionTable(transport, PartitionScheme.GPT, listOf(part("a", 2048, totalBlocks)))
        }
    }

    @Test
    fun `damaged GPT is reported instead of parsed`() {
        BlockDiskStructures.writePartitionTable(transport, PartitionScheme.GPT, listOf(part("a", 2048, 100_000)))
        val entries = transport.readBlocks(2, 1)
        entries[40] = (entries[40] + 1).toByte() // corrupt LastLBA of entry 0
        transport.writeBlocks(2, entries)
        assertIoFails { BlockDiskStructures.readPartitionLayout(transport) }
    }

    @Test
    fun `FAT32 refuses tiny partitions and other filesystems are not created`() {
        assertIoFails { BlockDiskStructures.validateFat32Geometry(40_000, 4096) }
        BlockDiskStructures.validateFat32Geometry(70_000, 4096) // fits once clusters shrink to 512 B
        assertIoFails {
            BlockDiskStructures.writeFileSystemAtPartition(transport, 2048, 100_000, FileSystemType.EXFAT, "X", 32768, "1")
        }
    }

    @Test
    fun `FAT32 format clears the whole FAT`() {
        transport.writeBlocks(2048 + 32 + 100, ByteArray(512) { 0x7F }) // stale FAT data deep in FAT1
        BlockDiskStructures.writeFileSystemAtPartition(transport, 2048, 100_000, FileSystemType.FAT32, "VOL", 4096, "1")
        assertTrue(transport.readBlocks(2048 + 32 + 100, 1).all { it == 0.toByte() })
    }

    @Test
    fun `block transport refuses out of range access`() {
        assertIoFails { transport.writeBlocks(totalBlocks, ByteArray(512)) }
        assertIoFails { transport.readBlocks(totalBlocks - 1, 2) }
    }
}

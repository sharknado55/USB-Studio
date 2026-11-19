package com.example.data

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.model.UsbPartition
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32

/**
 * Low-level block device transport abstraction supporting both real USB Mass Storage
 * Bulk-Only Transport (BOT / SCSI) hardware and deterministic file-backed test transports.
 */
interface BlockDeviceTransport : Closeable {
    val blockSize: Int
    val totalBlocks: Long
    val totalBytes: Long
        get() = totalBlocks * blockSize.toLong()

    @Throws(IOException::class)
    fun readBlocks(startLba: Long, blockCount: Int): ByteArray

    @Throws(IOException::class)
    fun writeBlocks(startLba: Long, data: ByteArray)

    @Throws(IOException::class)
    fun syncCache()
}

/**
 * Real Android USB Host Bulk-Only Transport (BOT, 0x50) SCSI block driver.
 * Issues real SCSI TEST UNIT READY, REQUEST SENSE, INQUIRY, READ CAPACITY(10/16),
 * READ(10), WRITE(10), and SYNCHRONIZE CACHE(10) commands over UsbDeviceConnection.
 */
class UsbScsiBlockTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    override val blockSize: Int,
    override val totalBlocks: Long,
    val inquiryVendor: String,
    val inquiryProduct: String
) : BlockDeviceTransport {

    private var tagCounter = 1
    private val maxBlocksPerTransfer: Int = (32 * 1024 / blockSize).coerceIn(1, 64)

    companion object {
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
        private const val USB_TIMEOUT_MS = 5000
        private const val USB_REQUEST_BOT_RESET = 0xFF
        private const val USB_REQUEST_CLEAR_FEATURE = 0x01
        private const val ENDPOINT_HALT = 0

        @Throws(IOException::class)
        fun open(usbManager: UsbManager, device: UsbDevice): UsbScsiBlockTransport {
            if (!usbManager.hasPermission(device)) {
                throw IOException("USB Host permission not granted for ${device.productName ?: device.deviceName}.")
            }

            var targetInterface: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    var candidateIn: UsbEndpoint? = null
                    var candidateOut: UsbEndpoint? = null
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) candidateIn = ep
                            else candidateOut = ep
                        }
                    }
                    if (candidateIn != null && candidateOut != null) {
                        targetInterface = intf
                        epIn = candidateIn
                        epOut = candidateOut
                        break
                    }
                }
            }

            val intf = targetInterface ?: throw IOException("No USB Mass Storage bulk interface found.")
            val inEp = epIn ?: throw IOException("Missing bulk IN endpoint.")
            val outEp = epOut ?: throw IOException("Missing bulk OUT endpoint.")

            val conn = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device connection.")

            if (!conn.claimInterface(intf, true)) {
                conn.close()
                throw IOException("Unable to claim USB Mass Storage interface.")
            }

            var openedSuccessfully = false
            try {
                val probe = UsbScsiBlockTransport(
                    connection = conn,
                    usbInterface = intf,
                    bulkIn = inEp,
                    bulkOut = outEp,
                    blockSize = 512,
                    totalBlocks = 0L,
                    inquiryVendor = "",
                    inquiryProduct = ""
                )

                // Wait for drive readiness (real USB flash drives often return UNIT ATTENTION on first command)
                probe.waitUntilReady()

                val (vendor, product) = runCatching { probe.queryInquiry() }.getOrDefault("" to "")
                val (blocks, blkSize) = probe.queryCapacity()

                openedSuccessfully = true
                return UsbScsiBlockTransport(
                    connection = conn,
                    usbInterface = intf,
                    bulkIn = inEp,
                    bulkOut = outEp,
                    blockSize = blkSize,
                    totalBlocks = blocks,
                    inquiryVendor = vendor,
                    inquiryProduct = product
                )
            } finally {
                if (!openedSuccessfully) {
                    runCatching { conn.releaseInterface(intf) }
                    runCatching { conn.close() }
                }
            }
        }
    }

    private fun waitUntilReady() {
        val tur = ByteArray(6) // Opcode 0x00 TEST UNIT READY
        for (attempt in 0 until 6) {
            val status = executeScsiRaw(tur, null, isDataIn = true)
            if (status == 0) return
            // Clear UNIT ATTENTION / NOT READY via REQUEST SENSE (0x03)
            val senseCmd = byteArrayOf(0x03, 0, 0, 0, 18, 0)
            val senseBuf = ByteArray(18)
            executeScsiRaw(senseCmd, senseBuf, isDataIn = true)
            if (attempt < 5) {
                runCatching { Thread.sleep(100L) }
            }
        }
    }

    private fun queryInquiry(): Pair<String, String> {
        val cmd = byteArrayOf(0x12, 0, 0, 0, 36, 0)
        val resp = ByteArray(36)
        val status = executeScsiRaw(cmd, resp, isDataIn = true)
        if (status != 0) return "" to ""
        val vendor = String(resp, 8, 8, StandardCharsets.US_ASCII).trim()
        val product = String(resp, 16, 16, StandardCharsets.US_ASCII).trim()
        return vendor to product
    }

    private fun queryCapacity(): Pair<Long, Int> {
        val cmd10 = ByteArray(10).apply { this[0] = 0x25 } // READ CAPACITY (10)
        val resp10 = ByteArray(8)
        var status = executeScsiRaw(cmd10, resp10, isDataIn = true)
        if (status != 0) {
            // One retry after REQUEST SENSE
            val senseBuf = ByteArray(18)
            executeScsiRaw(byteArrayOf(0x03, 0, 0, 0, 18, 0), senseBuf, isDataIn = true)
            status = executeScsiRaw(cmd10, resp10, isDataIn = true)
        }
        if (status != 0) {
            throw IOException("SCSI READ CAPACITY(10) failed (status=$status).")
        }

        val bb10 = ByteBuffer.wrap(resp10).order(ByteOrder.BIG_ENDIAN)
        val maxLba10 = bb10.int.toLong() and 0xFFFFFFFFL
        val blkSize10 = bb10.int

        if (maxLba10 == 0xFFFFFFFFL) {
            // >2 TB drive: issue READ CAPACITY (16) (Service Action In 0x9E / 0x10)
            val cmd16 = ByteArray(16).apply {
                this[0] = 0x9E.toByte()
                this[1] = 0x10.toByte()
                this[13] = 32.toByte()
            }
            val resp16 = ByteArray(32)
            if (executeScsiRaw(cmd16, resp16, isDataIn = true) == 0) {
                val bb16 = ByteBuffer.wrap(resp16).order(ByteOrder.BIG_ENDIAN)
                val maxLba16 = bb16.long
                val blkSize16 = bb16.int
                if (maxLba16 > 0L && blkSize16 in 512..4096) {
                    return (maxLba16 + 1L) to blkSize16
                }
            }
        }

        if (maxLba10 <= 0L || blkSize10 !in 512..4096) {
            throw IOException("Invalid USB drive capacity response (maxLba=$maxLba10, blockSize=$blkSize10).")
        }
        return (maxLba10 + 1L) to blkSize10
    }

    override fun readBlocks(startLba: Long, blockCount: Int): ByteArray {
        if (blockCount <= 0) return ByteArray(0)
        val result = ByteArray(blockCount * blockSize)
        var offsetBlocks = 0
        while (offsetBlocks < blockCount) {
            val chunkBlocks = minOf(maxBlocksPerTransfer, blockCount - offsetBlocks)
            val lba = startLba + offsetBlocks
            val chunkBuf = ByteArray(chunkBlocks * blockSize)
            val cmd = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                put(0x28.toByte()) // READ (10)
                put(0.toByte())
                putInt(lba.toInt())
                put(0.toByte())
                putShort(chunkBlocks.toShort())
                put(0.toByte())
            }.array()

            val status = executeScsiWithRetry(cmd, chunkBuf, isDataIn = true)
            if (status != 0) {
                throw IOException("SCSI READ(10) failed at LBA $lba (blocks=$chunkBlocks, status=$status).")
            }
            System.arraycopy(chunkBuf, 0, result, offsetBlocks * blockSize, chunkBuf.size)
            offsetBlocks += chunkBlocks
        }
        return result
    }

    override fun writeBlocks(startLba: Long, data: ByteArray) {
        if (data.isEmpty()) return
        val padded = if (data.size % blockSize == 0) {
            data
        } else {
            data.copyOf(((data.size + blockSize - 1) / blockSize) * blockSize)
        }
        val totalToWrite = padded.size / blockSize
        var offsetBlocks = 0
        while (offsetBlocks < totalToWrite) {
            val chunkBlocks = minOf(maxBlocksPerTransfer, totalToWrite - offsetBlocks)
            val lba = startLba + offsetBlocks
            val chunkBuf = padded.copyOfRange(
                offsetBlocks * blockSize,
                (offsetBlocks + chunkBlocks) * blockSize
            )
            val cmd = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                put(0x2A.toByte()) // WRITE (10)
                put(0.toByte())
                putInt(lba.toInt())
                put(0.toByte())
                putShort(chunkBlocks.toShort())
                put(0.toByte())
            }.array()

            val status = executeScsiWithRetry(cmd, chunkBuf, isDataIn = false)
            if (status != 0) {
                throw IOException("SCSI WRITE(10) failed at LBA $lba (blocks=$chunkBlocks, status=$status).")
            }
            offsetBlocks += chunkBlocks
        }
    }

    override fun syncCache() {
        val cmd = ByteArray(10).apply { this[0] = 0x35 } // SYNCHRONIZE CACHE (10)
        // Best effort: some flash drives don't implement SYNCHRONIZE CACHE and return CHECK CONDITION
        runCatching { executeScsiRaw(cmd, null, isDataIn = false) }
    }

    private fun executeScsiWithRetry(cmd: ByteArray, data: ByteArray?, isDataIn: Boolean): Int {
        var status = executeScsiRaw(cmd, data, isDataIn)
        if (status != 0) {
            val senseBuf = ByteArray(18)
            executeScsiRaw(byteArrayOf(0x03, 0, 0, 0, 18, 0), senseBuf, isDataIn = true)
            status = executeScsiRaw(cmd, data, isDataIn)
        }
        return status
    }

    private fun executeScsiRaw(cdb: ByteArray, data: ByteArray?, isDataIn: Boolean): Int {
        val tag = tagCounter++
        val transferLen = data?.size ?: 0
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(tag)
            putInt(transferLen)
            put(if (isDataIn && transferLen > 0) 0x80.toByte() else 0x00.toByte())
            put(0.toByte()) // LUN 0
            put(cdb.size.toByte())
            put(cdb)
        }.array()

        val sent = connection.bulkTransfer(bulkOut, cbw, cbw.size, USB_TIMEOUT_MS)
        if (sent != cbw.size) {
            performBotResetRecovery()
            return -1
        }

        if (data != null && transferLen > 0) {
            val ep = if (isDataIn) bulkIn else bulkOut
            var transferred = 0
            while (transferred < transferLen) {
                val step = minOf(16384, transferLen - transferred)
                val slice = if (transferred == 0 && step == transferLen) {
                    data
                } else {
                    ByteArray(step).also {
                        if (!isDataIn) System.arraycopy(data, transferred, it, 0, step)
                    }
                }
                val r = connection.bulkTransfer(ep, slice, step, USB_TIMEOUT_MS)
                if (r < 0) {
                    clearEndpointHalt(ep)
                    break
                }
                if (isDataIn && slice !== data) {
                    System.arraycopy(slice, 0, data, transferred, r)
                }
                transferred += r
                if (r < step) break
            }
        }

        val csw = ByteArray(13)
        var cswRead = connection.bulkTransfer(bulkIn, csw, csw.size, USB_TIMEOUT_MS)
        if (cswRead < 0) {
            clearEndpointHalt(bulkIn)
            cswRead = connection.bulkTransfer(bulkIn, csw, csw.size, USB_TIMEOUT_MS)
        }
        if (cswRead != 13) {
            performBotResetRecovery()
            return -2
        }

        val cswBb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = cswBb.int
        val respTag = cswBb.int
        /* val residue = */ cswBb.int
        val status = csw[12].toInt() and 0xFF
        if (sig != CSW_SIGNATURE || respTag != tag) {
            performBotResetRecovery()
            return -3
        }
        return status
    }

    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        runCatching {
            connection.controlTransfer(
                0x02, // Host-to-device, Standard, Endpoint
                USB_REQUEST_CLEAR_FEATURE,
                ENDPOINT_HALT,
                endpoint.address,
                null,
                0,
                1000
            )
        }
    }

    private fun performBotResetRecovery() {
        runCatching {
            connection.controlTransfer(
                0x21, // Host-to-device, Class, Interface
                USB_REQUEST_BOT_RESET,
                0,
                usbInterface.id,
                null,
                0,
                1000
            )
            clearEndpointHalt(bulkIn)
            clearEndpointHalt(bulkOut)
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }
}

/**
 * File-backed BlockDeviceTransport used strictly by unit/Robolectric tests and explicit block-image verification.
 */
class FileBlockTransport(
    private val file: File,
    override val totalBlocks: Long,
    override val blockSize: Int = 512
) : BlockDeviceTransport {

    private val raf: RandomAccessFile = RandomAccessFile(file, "rw").apply {
        val targetLen = totalBlocks * blockSize.toLong()
        if (length() < targetLen) setLength(targetLen)
    }

    override fun readBlocks(startLba: Long, blockCount: Int): ByteArray {
        val out = ByteArray(blockCount * blockSize)
        val offset = startLba * blockSize.toLong()
        if (offset >= raf.length()) return out
        raf.seek(offset)
        val readable = minOf(out.size.toLong(), raf.length() - offset).toInt()
        if (readable > 0) raf.readFully(out, 0, readable)
        return out
    }

    override fun writeBlocks(startLba: Long, data: ByteArray) {
        val offset = startLba * blockSize.toLong()
        raf.seek(offset)
        raf.write(data)
    }

    override fun syncCache() {
        runCatching { raf.fd.sync() }
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

/**
 * Real MBR/GPT partition table & FAT32/exFAT/NTFS/EXT4 filesystem reader/writer over BlockDeviceTransport.
 */
object BlockDiskStructures {

    private const val SECTOR_SIZE = 512

    /**
     * Reads the REAL partition table directly from LBA 0..33 of a block device and inspects each
     * partition's boot sector / superblock to determine its real filesystem, label, and UUID.
     */
    fun readPartitionLayout(transport: BlockDeviceTransport): Pair<PartitionScheme, List<UsbPartition>> {
        val lba0 = transport.readBlocks(0L, 1)
        val hasMbrSig = lba0.size >= 512 && lba0[510] == 0x55.toByte() && lba0[511] == 0xAA.toByte()

        // Check if Protective MBR (0xEE) + valid GPT Header ("EFI PART") at LBA 1
        val isProtectiveMbr = hasMbrSig && (0 until 4).any { idx ->
            (lba0[0x1BE + idx * 16 + 4].toInt() and 0xFF) == 0xEE
        }

        if (isProtectiveMbr && transport.totalBlocks > 34L) {
            val lba1 = transport.readBlocks(1L, 1)
            val sig = String(lba1, 0, 8, StandardCharsets.US_ASCII)
            if (sig == "EFI PART") {
                val gbb = ByteBuffer.wrap(lba1).order(ByteOrder.LITTLE_ENDIAN)
                val entriesStartLba = gbb.getLong(72).coerceIn(2L, 64L)
                val numEntries = gbb.getInt(80).coerceIn(1, 128)
                val entrySize = gbb.getInt(84).coerceIn(128, 512)
                val sectorsToRead = ((numEntries * entrySize) + SECTOR_SIZE - 1) / SECTOR_SIZE
                val rawEntries = transport.readBlocks(entriesStartLba, sectorsToRead.coerceAtMost(32))
                val ebb = ByteBuffer.wrap(rawEntries).order(ByteOrder.LITTLE_ENDIAN)

                val parsed = mutableListOf<UsbPartition>()
                for (i in 0 until minOf(numEntries, rawEntries.size / entrySize)) {
                    val base = i * entrySize
                    val allZeroType = (0 until 16).all { rawEntries[base + it] == 0.toByte() }
                    if (allZeroType) continue

                    val firstLba = ebb.getLong(base + 32)
                    val lastLba = ebb.getLong(base + 40)
                    val attrs = ebb.getLong(base + 48)
                    if (firstLba <= 0L || lastLba < firstLba) continue

                    val sectorCount = lastLba - firstLba + 1L
                    val nameBytes = rawEntries.copyOfRange(base + 56, minOf(base + 128, rawEntries.size))
                    val gptName = decodeUtf16LeNullTerminated(nameBytes)
                    val probed = probeFileSystemAtLba(transport, firstLba)
                    val label = probed.label.ifBlank { gptName.ifBlank { "PART_${parsed.size + 1}" } }
                    val isBootable = (attrs and 0x04L) != 0L

                    parsed.add(
                        UsbPartition(
                            id = "gpt_p${parsed.size + 1}_$firstLba",
                            index = parsed.size + 1,
                            label = label,
                            fileSystem = probed.fileSystem,
                            startLba = firstLba,
                            sectorCount = sectorCount,
                            sizeBytes = sectorCount * transport.blockSize.toLong(),
                            usedBytes = 0L,
                            isBootable = isBootable,
                            flags = buildList {
                                if (isBootable) add("BOOT")
                                add("GPT")
                            },
                            uuid = probed.uuid
                        )
                    )
                }
                return PartitionScheme.GPT to addUnallocatedTail(parsed, transport)
            }
        }

        // Standard MBR partition table (up to 4 primary entries at 0x1BE..0x1FD)
        if (hasMbrSig) {
            val bb = ByteBuffer.wrap(lba0).order(ByteOrder.LITTLE_ENDIAN)
            val parsed = mutableListOf<UsbPartition>()
            for (idx in 0 until 4) {
                val off = 0x1BE + idx * 16
                val bootFlag = lba0[off].toInt() and 0xFF
                val partType = lba0[off + 4].toInt() and 0xFF
                val startLba = bb.getInt(off + 8).toLong() and 0xFFFFFFFFL
                val sectorCount = bb.getInt(off + 12).toLong() and 0xFFFFFFFFL
                if ((bootFlag != 0x00 && bootFlag != 0x80) ||
                    partType == 0 ||
                    startLba == 0L ||
                    sectorCount == 0L ||
                    startLba >= transport.totalBlocks
                ) continue

                val probed = probeFileSystemAtLba(transport, startLba)
                val fs = if (probed.fileSystem != FileSystemType.UNALLOCATED) {
                    probed.fileSystem
                } else {
                    mapMbrTypeToFileSystem(partType)
                }
                val isBootable = bootFlag == 0x80
                parsed.add(
                    UsbPartition(
                        id = "mbr_p${parsed.size + 1}_$startLba",
                        index = parsed.size + 1,
                        label = probed.label.ifBlank { "PART_${parsed.size + 1}" },
                        fileSystem = fs,
                        startLba = startLba,
                        sectorCount = sectorCount,
                        sizeBytes = sectorCount * transport.blockSize.toLong(),
                        usedBytes = 0L,
                        isBootable = isBootable,
                        flags = buildList {
                            if (isBootable) add("BOOT")
                            add("PRIMARY")
                        },
                        typeGuidOrHex = String.format("0x%02X", partType),
                        uuid = probed.uuid
                    )
                )
            }

            if (parsed.isNotEmpty()) {
                return PartitionScheme.MBR to addUnallocatedTail(parsed, transport)
            }

            // Superfloppy (no partition table, filesystem directly at LBA 0!)
            val superfloppy = probeFileSystemAtLba(transport, 0L)
            if (superfloppy.fileSystem != FileSystemType.UNALLOCATED) {
                return PartitionScheme.MBR to listOf(
                    UsbPartition(
                        id = "superfloppy_0",
                        index = 1,
                        label = superfloppy.label.ifBlank { "USB_DRIVE" },
                        fileSystem = superfloppy.fileSystem,
                        startLba = 0L,
                        sectorCount = transport.totalBlocks,
                        sizeBytes = transport.totalBytes,
                        usedBytes = 0L,
                        isBootable = false,
                        flags = listOf("SUPERFLOPPY"),
                        uuid = superfloppy.uuid
                    )
                )
            }
        }

        // Completely unpartitioned / raw blank drive
        return PartitionScheme.MBR to listOf(
            UsbPartition(
                id = "unalloc_full",
                index = 1,
                label = "Unallocated",
                fileSystem = FileSystemType.UNALLOCATED,
                startLba = 2048L,
                sectorCount = (transport.totalBlocks - 2048L).coerceAtLeast(0L),
                sizeBytes = transport.totalBytes,
                usedBytes = 0L,
                isBootable = false,
                uuid = "----"
            )
        )
    }

    private data class ProbedFs(
        val fileSystem: FileSystemType,
        val label: String,
        val uuid: String
    )

    private fun probeFileSystemAtLba(transport: BlockDeviceTransport, startLba: Long): ProbedFs {
        if (startLba < 0L || startLba + 3 >= transport.totalBlocks) {
            return ProbedFs(FileSystemType.UNALLOCATED, "", "----")
        }
        val sectors = runCatching { transport.readBlocks(startLba, 4) }.getOrNull()
            ?: return ProbedFs(FileSystemType.UNALLOCATED, "", "----")
        if (sectors.size < 512) return ProbedFs(FileSystemType.UNALLOCATED, "", "----")

        val oem = String(sectors, 3, 8, StandardCharsets.US_ASCII)
        val fat32Tag = String(sectors, 82, 8, StandardCharsets.US_ASCII)
        val fat16Tag = String(sectors, 54, 8, StandardCharsets.US_ASCII)
        val bb = ByteBuffer.wrap(sectors).order(ByteOrder.LITTLE_ENDIAN)

        // Check exFAT
        if (oem == "EXFAT   ") {
            val serial = bb.getInt(100)
            val uuid = String.format("%04X-%04X", (serial ushr 16) and 0xFFFF, serial and 0xFFFF)
            return ProbedFs(FileSystemType.EXFAT, "EXFAT_VOL", uuid)
        }
        // Check NTFS
        if (oem == "NTFS    ") {
            val serial = bb.getLong(72)
            val uuid = String.format("%08X", (serial and 0xFFFFFFFFL).toInt())
            return ProbedFs(FileSystemType.NTFS, "NTFS_VOL", uuid)
        }
        // Check FAT32
        if (fat32Tag.startsWith("FAT32")) {
            val label = String(sectors, 71, 11, StandardCharsets.US_ASCII).trim()
            val serial = bb.getInt(67)
            val uuid = String.format("%04X-%04X", (serial ushr 16) and 0xFFFF, serial and 0xFFFF)
            return ProbedFs(FileSystemType.FAT32, label.ifBlank { "FAT32_VOL" }, uuid)
        }
        // Check FAT16/12 -> map to FAT32 badge
        if (fat16Tag.startsWith("FAT")) {
            val label = String(sectors, 43, 11, StandardCharsets.US_ASCII).trim()
            val serial = bb.getInt(39)
            val uuid = String.format("%04X-%04X", (serial ushr 16) and 0xFFFF, serial and 0xFFFF)
            return ProbedFs(FileSystemType.FAT32, label.ifBlank { "FAT_VOL" }, uuid)
        }
        // Check EXT4 superblock at byte offset 1024 (magic 0xEF53 at 1024 + 0x38 = 1080)
        if (sectors.size >= 1200) {
            val extMagic = bb.getShort(1024 + 0x38).toInt() and 0xFFFF
            if (extMagic == 0xEF53) {
                val volNameBytes = sectors.copyOfRange(1024 + 0x78, 1024 + 0x88)
                val label = String(volNameBytes, StandardCharsets.US_ASCII).substringBefore('\u0000').trim()
                val uuidHi = bb.getInt(1024 + 0x68)
                val uuid = String.format("%08x", uuidHi)
                return ProbedFs(FileSystemType.EXT4, label.ifBlank { "EXT4_VOL" }, uuid)
            }
        }
        // Check ISO-9660 Primary Volume Descriptor at offset +32768 (LBA + 64)
        if (startLba + 64 < transport.totalBlocks) {
            val pvd = runCatching { transport.readBlocks(startLba + 64L, 1) }.getOrNull()
            if (pvd != null && pvd.size >= 72 && String(pvd, 1, 5, StandardCharsets.US_ASCII) == "CD001") {
                val label = String(pvd, 40, 32, StandardCharsets.US_ASCII).trim()
                return ProbedFs(FileSystemType.ISO9660, label.ifBlank { "ISO_VOLUME" }, "ISO-9660")
            }
        }
        return ProbedFs(FileSystemType.UNALLOCATED, "", "----")
    }

    private fun mapMbrTypeToFileSystem(typeCode: Int): FileSystemType = when (typeCode) {
        0x0B, 0x0C, 0x0E, 0x06 -> FileSystemType.FAT32
        0x07 -> FileSystemType.EXFAT
        0x83 -> FileSystemType.EXT4
        0x17 -> FileSystemType.ISO9660
        else -> FileSystemType.FAT32
    }

    private fun addUnallocatedTail(
        parsed: List<UsbPartition>,
        transport: BlockDeviceTransport
    ): List<UsbPartition> {
        if (parsed.isEmpty()) return parsed
        val lastEndLba = parsed.maxOf { it.startLba + it.sectorCount }
        val remainingSectors = transport.totalBlocks - lastEndLba - 34L
        val remainingBytes = remainingSectors * transport.blockSize.toLong()
        return if (remainingBytes >= 32L * 1024 * 1024) {
            parsed + UsbPartition(
                id = "unalloc_tail_$lastEndLba",
                index = parsed.size + 1,
                label = "Unallocated",
                fileSystem = FileSystemType.UNALLOCATED,
                startLba = lastEndLba,
                sectorCount = remainingSectors,
                sizeBytes = remainingBytes,
                usedBytes = 0L,
                isBootable = false,
                uuid = "----"
            )
        } else {
            parsed
        }
    }

    private fun decodeUtf16LeNullTerminated(bytes: ByteArray): String {
        var end = 0
        while (end + 1 < bytes.size) {
            if (bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) break
            end += 2
        }
        return String(bytes, 0, end, StandardCharsets.UTF_16LE).trim()
    }

    /**
     * Writes real MBR (LBA 0) or GPT (Protective MBR LBA 0 + GPT Header LBA 1 + Partition Array LBA 2..33)
     * directly to the physical block device.
     */
    fun writePartitionTable(
        transport: BlockDeviceTransport,
        scheme: PartitionScheme,
        partitions: List<UsbPartition>
    ) {
        val mbrSector = ByteArray(SECTOR_SIZE)
        val bb = ByteBuffer.wrap(mbrSector).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x1B8, 0x55534231) // Disk signature

        if (scheme == PartitionScheme.MBR) {
            val activeParts = partitions.filter { it.fileSystem != FileSystemType.UNALLOCATED }.take(4)
            activeParts.forEachIndexed { idx, part ->
                val off = 0x1BE + (idx * 16)
                mbrSector[off] = if (part.isBootable) 0x80.toByte() else 0x00.toByte()
                mbrSector[off + 1] = 0x20.toByte()
                mbrSector[off + 2] = 0x21.toByte()
                mbrSector[off + 3] = 0x00.toByte()
                mbrSector[off + 4] = part.fileSystem.code
                mbrSector[off + 5] = 0xFE.toByte()
                mbrSector[off + 6] = 0xFF.toByte()
                mbrSector[off + 7] = 0xFF.toByte()
                bb.putInt(off + 8, part.startLba.coerceAtMost(0xFFFFFFFFL).toInt())
                bb.putInt(off + 12, part.sectorCount.coerceAtMost(0xFFFFFFFFL).toInt())
            }
        } else {
            // Protective MBR entry (Type 0xEE) for GPT
            val off = 0x1BE
            mbrSector[off] = 0x00.toByte()
            mbrSector[off + 1] = 0x00.toByte()
            mbrSector[off + 2] = 0x02.toByte()
            mbrSector[off + 3] = 0x00.toByte()
            mbrSector[off + 4] = 0xEE.toByte()
            mbrSector[off + 5] = 0xFF.toByte()
            mbrSector[off + 6] = 0xFF.toByte()
            mbrSector[off + 7] = 0xFF.toByte()
            bb.putInt(off + 8, 1)
            bb.putInt(off + 12, (transport.totalBlocks - 1L).coerceAtMost(0xFFFFFFFFL).toInt())
        }

        mbrSector[510] = 0x55.toByte()
        mbrSector[511] = 0xAA.toByte()
        transport.writeBlocks(0L, mbrSector)

        if (scheme == PartitionScheme.GPT) {
            val entriesArray = ByteArray(128 * 128) // 16 KB = 32 sectors (LBA 2..33)
            val entriesBb = ByteBuffer.wrap(entriesArray).order(ByteOrder.LITTLE_ENDIAN)
            val activeParts = partitions.filter { it.fileSystem != FileSystemType.UNALLOCATED }.take(128)

            activeParts.forEachIndexed { idx, part ->
                val base = idx * 128
                // Basic Data Partition GUID (Microsoft/Linux standard EBD0A0A2-B9E5-4433-87C0-68B6B72699C7)
                // or Linux Filesystem Data GUID (0FC63DAF-8483-4772-8E79-3D69D8477DE4) for EXT4
                val typeGuidBytes = if (part.fileSystem == FileSystemType.EXT4) {
                    byteArrayOf(
                        0xAF.toByte(), 0x3D, 0xC6.toByte(), 0x0F,
                        0x83.toByte(), 0x84.toByte(), 0x72, 0x47,
                        0x8E.toByte(), 0x79, 0x3D, 0x69, 0xD8.toByte(), 0x47, 0x7D, 0xE4.toByte()
                    )
                } else {
                    byteArrayOf(
                        0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(),
                        0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
                        0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(), 0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte()
                    )
                }
                System.arraycopy(typeGuidBytes, 0, entriesArray, base, 16)

                val partUuid = UUID.randomUUID()
                entriesBb.putLong(base + 16, partUuid.mostSignificantBits)
                entriesBb.putLong(base + 24, partUuid.leastSignificantBits)
                entriesBb.putLong(base + 32, part.startLba)
                entriesBb.putLong(base + 40, (part.startLba + part.sectorCount - 1L).coerceAtLeast(part.startLba))
                entriesBb.putLong(base + 48, if (part.isBootable) 0x04L else 0x00L)

                val utf16Name = part.label.take(36).toByteArray(StandardCharsets.UTF_16LE)
                System.arraycopy(utf16Name, 0, entriesArray, base + 56, minOf(utf16Name.size, 72))
            }

            val entriesCrc = CRC32().apply { update(entriesArray) }.value.toInt()
            val lastLba = (transport.totalBlocks - 1L).coerceAtLeast(64L)

            val gptHeader = ByteArray(SECTOR_SIZE)
            val gbb = ByteBuffer.wrap(gptHeader).order(ByteOrder.LITTLE_ENDIAN)
            System.arraycopy("EFI PART".toByteArray(StandardCharsets.US_ASCII), 0, gptHeader, 0, 8)
            gbb.putInt(8, 0x00010000)
            gbb.putInt(12, 92)
            gbb.putLong(24, 1L)
            gbb.putLong(32, lastLba)
            gbb.putLong(40, 34L)
            gbb.putLong(48, (lastLba - 33L).coerceAtLeast(34L))
            val diskGuid = UUID.randomUUID()
            gbb.putLong(56, diskGuid.mostSignificantBits)
            gbb.putLong(64, diskGuid.leastSignificantBits)
            gbb.putLong(72, 2L)
            gbb.putInt(80, 128)
            gbb.putInt(84, 128)
            gbb.putInt(88, entriesCrc)

            val headerCrc = CRC32().apply { update(gptHeader, 0, 92) }.value.toInt()
            gbb.putInt(16, headerCrc)

            transport.writeBlocks(1L, gptHeader)
            transport.writeBlocks(2L, entriesArray)
        }
    }

    /**
     * Writes real, standards-compliant filesystem structures at the partition's start LBA.
     * For FAT32, writes a complete, OS-mountable FAT32 volume (VBR + FSInfo + Backup VBR +
     * FAT1 + FAT2 + zeroed root directory cluster with volume label entry).
     */
    fun writeFileSystemAtPartition(
        transport: BlockDeviceTransport,
        startLba: Long,
        sectorCount: Long,
        fileSystem: FileSystemType,
        volumeLabel: String,
        clusterSize: Int,
        uuid: String
    ) {
        val paddedLabel = volumeLabel.uppercase().padEnd(11, ' ').take(11)
            .toByteArray(StandardCharsets.US_ASCII)
        val serialInt = uuid.hashCode()

        when (fileSystem) {
            FileSystemType.FAT32 -> {
                val sectorsPerCluster = (clusterSize / SECTOR_SIZE).coerceIn(1, 128)
                val reservedSectors = 32
                val numFats = 2
                val totalSectors = sectorCount.coerceIn(65536L, 0xFFFFFFFFL).toInt()
                val tmpVal1 = (totalSectors - reservedSectors).coerceAtLeast(1024)
                val tmpVal2 = 256 * sectorsPerCluster + numFats
                val fatSize32 = ((tmpVal1 + (tmpVal2 / 2) - 1) / (tmpVal2 / 2)).coerceAtLeast(16)

                // 1. Primary FAT32 Boot Sector (VBR)
                val vbr = ByteArray(SECTOR_SIZE)
                val bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)
                vbr[0] = 0xEB.toByte()
                vbr[1] = 0x58.toByte()
                vbr[2] = 0x90.toByte()
                System.arraycopy("MSDOS5.0".toByteArray(StandardCharsets.US_ASCII), 0, vbr, 3, 8)
                bb.putShort(11, SECTOR_SIZE.toShort())
                vbr[13] = sectorsPerCluster.toByte()
                bb.putShort(14, reservedSectors.toShort())
                vbr[16] = numFats.toByte()
                bb.putShort(17, 0) // RootEntCnt = 0 for FAT32
                bb.putShort(19, 0) // TotSec16 = 0 for FAT32
                vbr[21] = 0xF8.toByte() // Fixed media
                bb.putShort(22, 0) // FATSz16 = 0
                bb.putShort(24, 63) // SecPerTrk
                bb.putShort(26, 255) // NumHeads
                bb.putInt(28, startLba.coerceAtMost(0xFFFFFFFFL).toInt()) // HiddSec
                bb.putInt(32, totalSectors) // TotSec32
                bb.putInt(36, fatSize32) // FATSz32
                bb.putShort(40, 0) // ExtFlags
                bb.putShort(42, 0) // FSVer
                bb.putInt(44, 2) // RootClus = 2
                bb.putShort(48, 1) // FSInfo = 1
                bb.putShort(50, 6) // BkBootSec = 6
                vbr[64] = 0x80.toByte() // DrvNum
                vbr[66] = 0x29.toByte() // BootSig
                bb.putInt(67, serialInt) // VolID
                System.arraycopy(paddedLabel, 0, vbr, 71, 11)
                System.arraycopy("FAT32   ".toByteArray(StandardCharsets.US_ASCII), 0, vbr, 82, 8)
                vbr[510] = 0x55.toByte()
                vbr[511] = 0xAA.toByte()

                // 2. FSInfo Sector (startLba + 1)
                val fsInfo = ByteArray(SECTOR_SIZE)
                val fbb = ByteBuffer.wrap(fsInfo).order(ByteOrder.LITTLE_ENDIAN)
                fbb.putInt(0, 0x41615252) // LeadSig "RRaA"
                fbb.putInt(484, 0x61417272) // StrucSig "rrAa"
                fbb.putInt(488, -1) // Free_Count unknown (recalculated by OS)
                fbb.putInt(492, 3) // Nxt_Free = cluster 3
                fsInfo[510] = 0x55.toByte()
                fsInfo[511] = 0xAA.toByte()

                transport.writeBlocks(startLba, vbr)
                transport.writeBlocks(startLba + 1L, fsInfo)
                // Backup boot sector at +6 and backup FSInfo at +7
                transport.writeBlocks(startLba + 6L, vbr)
                transport.writeBlocks(startLba + 7L, fsInfo)

                // 3. Initialize FAT1 and FAT2 first sectors (Cluster 0, 1, and Root Cluster 2 EOC)
                val fatFirstSector = ByteArray(SECTOR_SIZE)
                val fatBb = ByteBuffer.wrap(fatFirstSector).order(ByteOrder.LITTLE_ENDIAN)
                fatBb.putInt(0, 0x0FFFFFF8)
                fatBb.putInt(4, 0x0FFFFFFF)
                fatBb.putInt(8, 0x0FFFFFFF) // Root directory cluster 2 = End Of Chain

                val fat1Lba = startLba + reservedSectors.toLong()
                val fat2Lba = fat1Lba + fatSize32.toLong()
                transport.writeBlocks(fat1Lba, fatFirstSector)
                if (fat2Lba < transport.totalBlocks) {
                    transport.writeBlocks(fat2Lba, fatFirstSector)
                }

                // Clear next few FAT sectors so stale chains from previous formatting are erased
                val zeroBlock = ByteArray(SECTOR_SIZE * 8)
                if (fat1Lba + 1L + 8L < transport.totalBlocks) {
                    transport.writeBlocks(fat1Lba + 1L, zeroBlock)
                }
                if (fat2Lba + 1L + 8L < transport.totalBlocks) {
                    transport.writeBlocks(fat2Lba + 1L, zeroBlock)
                }

                // 4. Write clean Root Directory Cluster (Cluster 2) with Volume Label entry
                val rootDirLba = fat1Lba + (numFats.toLong() * fatSize32.toLong())
                val rootClusterBytes = ByteArray(sectorsPerCluster * SECTOR_SIZE)
                System.arraycopy(paddedLabel, 0, rootClusterBytes, 0, 11)
                rootClusterBytes[11] = 0x08.toByte() // ATTR_VOLUME_ID
                if (rootDirLba + sectorsPerCluster <= transport.totalBlocks) {
                    transport.writeBlocks(rootDirLba, rootClusterBytes)
                }
            }

            FileSystemType.EXFAT -> {
                val vbr = ByteArray(SECTOR_SIZE)
                val bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)
                vbr[0] = 0xEB.toByte()
                vbr[1] = 0x76.toByte()
                vbr[2] = 0x90.toByte()
                System.arraycopy("EXFAT   ".toByteArray(StandardCharsets.US_ASCII), 0, vbr, 3, 8)
                bb.putLong(64, startLba)
                bb.putLong(72, sectorCount)
                bb.putInt(80, 24)
                bb.putInt(84, 128)
                bb.putInt(88, 152)
                bb.putInt(96, 4)
                bb.putInt(100, serialInt)
                vbr[108] = 9 // 512B sector shift
                vbr[109] = 6 // 32KB cluster shift
                vbr[110] = 1
                vbr[510] = 0x55.toByte()
                vbr[511] = 0xAA.toByte()
                transport.writeBlocks(startLba, vbr)
            }

            FileSystemType.NTFS -> {
                val vbr = ByteArray(SECTOR_SIZE)
                val bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)
                vbr[0] = 0xEB.toByte()
                vbr[1] = 0x52.toByte()
                vbr[2] = 0x90.toByte()
                System.arraycopy("NTFS    ".toByteArray(StandardCharsets.US_ASCII), 0, vbr, 3, 8)
                bb.putShort(11, SECTOR_SIZE.toShort())
                vbr[13] = (clusterSize / SECTOR_SIZE).coerceIn(1, 128).toByte()
                vbr[21] = 0xF8.toByte()
                bb.putLong(40, sectorCount)
                bb.putLong(48, 4L)
                bb.putLong(56, 2L)
                bb.putLong(72, serialInt.toLong())
                vbr[510] = 0x55.toByte()
                vbr[511] = 0xAA.toByte()
                transport.writeBlocks(startLba, vbr)
            }

            FileSystemType.EXT4 -> {
                // EXT4 superblock lives at byte offset 1024 (startLba + 2 sectors)
                val zeroBoot = ByteArray(SECTOR_SIZE * 2)
                transport.writeBlocks(startLba, zeroBoot)

                val sb = ByteArray(1024)
                val bb = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
                val blockCount4k = (sectorCount / 8L).coerceIn(1024L, 0xFFFFFFFFL).toInt()
                bb.putInt(0x00, (blockCount4k / 4).coerceAtLeast(1024)) // s_inodes_count
                bb.putInt(0x04, blockCount4k) // s_blocks_count_lo
                bb.putInt(0x18, 2) // s_log_block_size -> 4096 bytes
                bb.putShort(0x38, 0xEF53.toShort()) // EXT4 magic
                System.arraycopy(paddedLabel, 0, sb, 0x78, 11)
                if (startLba + 4L <= transport.totalBlocks) {
                    transport.writeBlocks(startLba + 2L, sb)
                }
            }

            else -> Unit
        }
    }
}

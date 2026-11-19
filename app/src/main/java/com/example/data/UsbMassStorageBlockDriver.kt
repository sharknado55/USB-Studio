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
    /** Bytes moved in the data phase of the last command, and the CSW data residue it reported. */
    private var lastTransferred = 0
    private var lastResidue = 0
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

    private fun checkRange(startLba: Long, blockCount: Long) {
        if (startLba < 0L || blockCount < 0L || startLba + blockCount > totalBlocks) {
            throw IOException("Block range LBA $startLba (+$blockCount) is outside the drive (totalBlocks=$totalBlocks).")
        }
    }

    /** Builds READ/WRITE(10), or the 16-byte variant when the range does not fit 32-bit LBAs. */
    private fun buildRwCdb(isRead: Boolean, lba: Long, blocks: Int): ByteArray {
        return if (lba + blocks > 0xFFFFFFFFL) {
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                put((if (isRead) 0x88 else 0x8A).toByte()) // READ(16) / WRITE(16)
                put(0.toByte())
                putLong(lba)
                putInt(blocks)
                put(0.toByte())
                put(0.toByte())
            }.array()
        } else {
            ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).apply {
                put((if (isRead) 0x28 else 0x2A).toByte()) // READ(10) / WRITE(10)
                put(0.toByte())
                putInt(lba.toInt()) // lba <= 0xFFFFFFFF, so the bit pattern is the unsigned value
                put(0.toByte())
                putShort(blocks.toShort())
                put(0.toByte())
            }.array()
        }
    }

    override fun readBlocks(startLba: Long, blockCount: Int): ByteArray {
        if (blockCount <= 0) return ByteArray(0)
        checkRange(startLba, blockCount.toLong())
        val result = ByteArray(blockCount * blockSize)
        var offsetBlocks = 0
        while (offsetBlocks < blockCount) {
            val chunkBlocks = minOf(maxBlocksPerTransfer, blockCount - offsetBlocks)
            val lba = startLba + offsetBlocks
            val chunkBuf = ByteArray(chunkBlocks * blockSize)
            val cmd = buildRwCdb(isRead = true, lba = lba, blocks = chunkBlocks)

            val status = executeScsiWithRetry(cmd, chunkBuf, isDataIn = true)
            if (status != 0) {
                throw IOException("SCSI READ failed at LBA $lba (blocks=$chunkBlocks, status=$status).")
            }
            if (lastTransferred != chunkBuf.size || lastResidue != 0) {
                throw IOException("SCSI READ at LBA $lba returned a short transfer ($lastTransferred of ${chunkBuf.size} bytes, residue=$lastResidue).")
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
        checkRange(startLba, totalToWrite.toLong())
        var offsetBlocks = 0
        while (offsetBlocks < totalToWrite) {
            val chunkBlocks = minOf(maxBlocksPerTransfer, totalToWrite - offsetBlocks)
            val lba = startLba + offsetBlocks
            val chunkBuf = padded.copyOfRange(
                offsetBlocks * blockSize,
                (offsetBlocks + chunkBlocks) * blockSize
            )
            val cmd = buildRwCdb(isRead = false, lba = lba, blocks = chunkBlocks)

            val status = executeScsiWithRetry(cmd, chunkBuf, isDataIn = false)
            if (status != 0) {
                throw IOException("SCSI WRITE failed at LBA $lba (blocks=$chunkBlocks, status=$status).")
            }
            if (lastTransferred != chunkBuf.size || lastResidue != 0) {
                throw IOException("SCSI WRITE at LBA $lba was short ($lastTransferred of ${chunkBuf.size} bytes, residue=$lastResidue).")
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
        lastTransferred = 0
        lastResidue = 0
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
            lastTransferred = transferred
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
        lastResidue = cswBb.int
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
        if (startLba < 0L || startLba + blockCount > totalBlocks) {
            throw IOException("Block range LBA $startLba (+$blockCount) is outside the image (totalBlocks=$totalBlocks).")
        }
        val out = ByteArray(blockCount * blockSize)
        val offset = startLba * blockSize.toLong()
        if (offset >= raf.length()) return out
        raf.seek(offset)
        val readable = minOf(out.size.toLong(), raf.length() - offset).toInt()
        if (readable > 0) raf.readFully(out, 0, readable)
        return out
    }

    override fun writeBlocks(startLba: Long, data: ByteArray) {
        val blocks = (data.size + blockSize - 1) / blockSize
        if (startLba < 0L || startLba + blocks > totalBlocks) {
            throw IOException("Block range LBA $startLba (+$blocks) is outside the image (totalBlocks=$totalBlocks).")
        }
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
 * Real MBR/GPT partition table & FAT32 filesystem reader/writer over BlockDeviceTransport.
 * exFAT, NTFS, EXT4 and ISO-9660 are detected when reading but cannot be created.
 */
object BlockDiskStructures {

    private const val SECTOR_SIZE = 512
    private const val GPT_ENTRY_COUNT = 128
    private const val GPT_ENTRY_SIZE = 128
    private const val GPT_ARRAY_SECTORS = (GPT_ENTRY_COUNT * GPT_ENTRY_SIZE) / SECTOR_SIZE // 32
    private const val FAT32_MIN_CLUSTERS = 65525L
    private const val FAT32_MAX_CLUSTERS = 0x0FFFFFF5L

    /** MBR types for extended (container) partitions, whose logical partitions this app cannot edit. */
    private val MBR_EXTENDED_TYPES = setOf(0x05, 0x0F, 0x85)

    const val FLAG_OUT_OF_BOUNDS = "OUT_OF_BOUNDS"
    const val FLAG_EXTENDED = "EXTENDED"

    private val BASIC_DATA_GUID = byteArrayOf(
        0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(),
        0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
        0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(), 0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte()
    )
    private val LINUX_DATA_GUID = byteArrayOf(
        0xAF.toByte(), 0x3D, 0xC6.toByte(), 0x0F,
        0x83.toByte(), 0x84.toByte(), 0x72, 0x47,
        0x8E.toByte(), 0x79, 0x3D, 0x69, 0xD8.toByte(), 0x47, 0x7D, 0xE4.toByte()
    )

    /** Raw writes below assume 512-byte logical sectors; refuse anything else instead of corrupting it. */
    @Throws(IOException::class)
    fun requireSupportedSectorSize(transport: BlockDeviceTransport) {
        if (transport.blockSize != SECTOR_SIZE) {
            throw IOException(
                "This drive uses ${transport.blockSize}-byte sectors. Partitioning and formatting " +
                    "support only 512-byte sector drives."
            )
        }
    }

    /**
     * Reads the REAL partition table directly from LBA 0..33 of a block device and inspects each
     * partition's boot sector / superblock to determine its real filesystem, label, and UUID.
     * Throws when a GPT is present but its CRCs do not match, so a damaged table is never edited.
     */
    @Throws(IOException::class)
    fun readPartitionLayout(transport: BlockDeviceTransport): Pair<PartitionScheme, List<UsbPartition>> {
        val lba0 = transport.readBlocks(0L, 1)
        val hasMbrSig = lba0.size >= 512 && lba0[510] == 0x55.toByte() && lba0[511] == 0xAA.toByte()

        // Check if Protective MBR (0xEE) + valid GPT Header ("EFI PART") at LBA 1
        val isProtectiveMbr = hasMbrSig && (0 until 4).any { idx ->
            (lba0[0x1BE + idx * 16 + 4].toInt() and 0xFF) == 0xEE
        }

        if (isProtectiveMbr && transport.totalBlocks > 67L) {
            val lba1 = transport.readBlocks(1L, 1)
            val sig = String(lba1, 0, 8, StandardCharsets.US_ASCII)
            if (sig == "EFI PART") {
                val gbb = ByteBuffer.wrap(lba1).order(ByteOrder.LITTLE_ENDIAN)
                val headerSize = gbb.getInt(12)
                if (headerSize < 92 || headerSize > lba1.size) {
                    throw IOException("GPT header size $headerSize is invalid. The partition table is damaged.")
                }
                val storedHeaderCrc = gbb.getInt(16)
                val headerCopy = lba1.copyOf(headerSize)
                ByteBuffer.wrap(headerCopy).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0)
                if (CRC32().apply { update(headerCopy) }.value.toInt() != storedHeaderCrc) {
                    throw IOException("GPT header CRC mismatch. The partition table is damaged; format the whole drive to rebuild it.")
                }

                val entriesStartLba = gbb.getLong(72)
                val numEntries = gbb.getInt(80)
                val entrySize = gbb.getInt(84)
                if (entriesStartLba < 2L || entriesStartLba >= transport.totalBlocks ||
                    numEntries !in 1..GPT_ENTRY_COUNT || entrySize !in 128..512 || entrySize % 8 != 0
                ) {
                    throw IOException("GPT header fields are out of range. The partition table is damaged.")
                }
                val arrayBytes = numEntries * entrySize
                val sectorsToRead = (arrayBytes + transport.blockSize - 1) / transport.blockSize
                if (entriesStartLba + sectorsToRead > transport.totalBlocks) {
                    throw IOException("GPT partition array lies past the end of the drive.")
                }
                val rawEntries = transport.readBlocks(entriesStartLba, sectorsToRead)
                val entriesCrc = CRC32().apply { update(rawEntries, 0, arrayBytes) }.value.toInt()
                if (entriesCrc != gbb.getInt(88)) {
                    throw IOException("GPT partition array CRC mismatch. The partition table is damaged; format the whole drive to rebuild it.")
                }
                val ebb = ByteBuffer.wrap(rawEntries).order(ByteOrder.LITTLE_ENDIAN)
                val lastUsable = transport.totalBlocks - 1L - (GPT_ARRAY_SECTORS + 1)

                val parsed = mutableListOf<UsbPartition>()
                for (i in 0 until numEntries) {
                    val base = i * entrySize
                    val allZeroType = (0 until 16).all { rawEntries[base + it] == 0.toByte() }
                    if (allZeroType) continue

                    val firstLba = ebb.getLong(base + 32)
                    val lastLba = ebb.getLong(base + 40)
                    val attrs = ebb.getLong(base + 48)
                    val outOfBounds = firstLba < 34L || lastLba < firstLba || lastLba > lastUsable
                    val sectorCount = if (lastLba >= firstLba) lastLba - firstLba + 1L else 0L
                    val nameBytes = rawEntries.copyOfRange(base + 56, base + 128)
                    val gptName = decodeUtf16LeNullTerminated(nameBytes)
                    val probed = if (outOfBounds) {
                        ProbedFs(FileSystemType.UNALLOCATED, "", "----")
                    } else {
                        probeFileSystemAtLba(transport, firstLba)
                    }
                    val label = probed.label.ifBlank { gptName.ifBlank { "PART_${parsed.size + 1}" } }
                    val isBootable = (attrs and 0x04L) != 0L
                    val typeGuidBytes = rawEntries.copyOfRange(base, base + 16)

                    parsed.add(
                        UsbPartition(
                            id = "gpt_p${parsed.size + 1}_$firstLba",
                            index = parsed.size + 1,
                            label = label,
                            // Unknown content keeps a non-UNALLOCATED badge so it is never dropped on rewrite.
                            fileSystem = if (probed.fileSystem == FileSystemType.UNALLOCATED) {
                                if (typeGuidBytes.contentEquals(LINUX_DATA_GUID)) FileSystemType.EXT4 else FileSystemType.FAT32
                            } else {
                                probed.fileSystem
                            },
                            startLba = firstLba,
                            sectorCount = sectorCount,
                            sizeBytes = sectorCount * transport.blockSize.toLong(),
                            usedBytes = 0L,
                            isBootable = isBootable,
                            flags = buildList {
                                if (isBootable) add("BOOT")
                                add("GPT")
                                if (outOfBounds) add(FLAG_OUT_OF_BOUNDS)
                            },
                            typeGuidOrHex = formatGuid(typeGuidBytes),
                            uuid = probed.uuid,
                            gptTypeGuid = typeGuidBytes.toHex(),
                            gptUniqueGuid = rawEntries.copyOfRange(base + 16, base + 32).toHex(),
                            gptAttributes = attrs
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
                    sectorCount == 0L
                ) continue

                val outOfBounds = startLba + sectorCount > transport.totalBlocks
                val isExtended = partType in MBR_EXTENDED_TYPES
                val probed = if (outOfBounds || isExtended) {
                    ProbedFs(FileSystemType.UNALLOCATED, "", "----")
                } else {
                    probeFileSystemAtLba(transport, startLba)
                }
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
                        label = probed.label.ifBlank { if (isExtended) "EXTENDED" else "PART_${parsed.size + 1}" },
                        fileSystem = fs,
                        startLba = startLba,
                        sectorCount = sectorCount,
                        sizeBytes = sectorCount * transport.blockSize.toLong(),
                        usedBytes = 0L,
                        isBootable = isBootable,
                        flags = buildList {
                            if (isBootable) add("BOOT")
                            add(if (isExtended) FLAG_EXTENDED else "PRIMARY")
                            if (outOfBounds) add(FLAG_OUT_OF_BOUNDS)
                        },
                        typeGuidOrHex = String.format("0x%02X", partType),
                        uuid = probed.uuid,
                        mbrTypeCode = partType
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

        // Completely unpartitioned / raw blank drive. Keep 1 MiB at the start and room for a backup GPT at the end.
        val blankSectors = (transport.totalBlocks - 2048L - (GPT_ARRAY_SECTORS + 1)).coerceAtLeast(0L)
        return PartitionScheme.MBR to listOf(
            UsbPartition(
                id = "unalloc_full",
                index = 1,
                label = "Unallocated",
                fileSystem = FileSystemType.UNALLOCATED,
                startLba = 2048L,
                sectorCount = blankSectors,
                sizeBytes = blankSectors * transport.blockSize.toLong(),
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
        // Display badge only: the raw type byte is kept in UsbPartition.mbrTypeCode and written back unchanged.
        else -> FileSystemType.FAT32
    }

    private fun addUnallocatedTail(
        parsed: List<UsbPartition>,
        transport: BlockDeviceTransport
    ): List<UsbPartition> {
        if (parsed.isEmpty()) return parsed
        val lastEndLba = parsed.maxOf { it.startLba + it.sectorCount }
        val remainingSectors = transport.totalBlocks - lastEndLba - (GPT_ARRAY_SECTORS + 1)
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length != 32) return null
        return runCatching { ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    /** Formats a raw on-disk (mixed-endian) GPT GUID as the usual text form. */
    private fun formatGuid(b: ByteArray): String {
        fun h(i: Int) = "%02X".format(b[i])
        return h(3) + h(2) + h(1) + h(0) + "-" + h(5) + h(4) + "-" + h(7) + h(6) + "-" +
            h(8) + h(9) + "-" + (10..15).joinToString("") { h(it) }
    }

    /**
     * Checks a layout before anything is written: sector size, entry limits, extended partitions,
     * bounds against the real drive size, and overlaps. Throws IOException describing the first problem.
     */
    @Throws(IOException::class)
    fun validateLayout(
        transport: BlockDeviceTransport,
        scheme: PartitionScheme,
        partitions: List<UsbPartition>
    ) {
        requireSupportedSectorSize(transport)
        val active = partitions.filter { it.fileSystem != FileSystemType.UNALLOCATED }
        val total = transport.totalBlocks

        val (firstUsable, lastUsable) = if (scheme == PartitionScheme.MBR) {
            if (active.size > 4) {
                throw IOException("MBR supports at most 4 primary partitions; this layout has ${active.size}. Use GPT or remove partitions.")
            }
            if (active.any { FLAG_EXTENDED in it.flags || (it.mbrTypeCode ?: -1) in MBR_EXTENDED_TYPES }) {
                throw IOException("This drive has an extended partition with logical partitions. Editing it is not supported; format the whole drive instead.")
            }
            1L to minOf(total - 1L, 0xFFFFFFFFL)
        } else {
            if (active.size > GPT_ENTRY_COUNT) {
                throw IOException("GPT supports at most $GPT_ENTRY_COUNT partitions.")
            }
            (2L + GPT_ARRAY_SECTORS) to (total - 2L - GPT_ARRAY_SECTORS)
        }

        val sorted = active.sortedBy { it.startLba }
        var prevEnd = -1L
        for (part in sorted) {
            val end = part.startLba + part.sectorCount - 1L
            if (part.sectorCount <= 0L || part.startLba < firstUsable || end > lastUsable) {
                throw IOException(
                    "Partition '${part.label}' (LBA ${part.startLba}..$end) is outside the usable area " +
                        "LBA $firstUsable..$lastUsable of this drive."
                )
            }
            if (part.startLba <= prevEnd) {
                throw IOException("Partition '${part.label}' overlaps the previous partition.")
            }
            prevEnd = end
        }
    }

    /**
     * Writes real MBR (LBA 0) or GPT (Protective MBR + primary header/array at LBA 1..33 and
     * backup array/header at the last 33 sectors) to the physical block device.
     * Existing partition types, GUIDs, attributes, disk identity and MBR boot code are preserved.
     */
    @Throws(IOException::class)
    fun writePartitionTable(
        transport: BlockDeviceTransport,
        scheme: PartitionScheme,
        partitions: List<UsbPartition>
    ) {
        validateLayout(transport, scheme, partitions)
        val total = transport.totalBlocks
        val active = partitions.filter { it.fileSystem != FileSystemType.UNALLOCATED }.sortedBy { it.startLba }

        val oldLba0 = transport.readBlocks(0L, 1)
        val oldHasSig = oldLba0[510] == 0x55.toByte() && oldLba0[511] == 0xAA.toByte()
        val mbrSector = ByteArray(SECTOR_SIZE)
        val bb = ByteBuffer.wrap(mbrSector).order(ByteOrder.LITTLE_ENDIAN)
        if (oldHasSig) {
            // Keep boot code (0..439) and the disk signature (440..445).
            System.arraycopy(oldLba0, 0, mbrSector, 0, 446)
        } else {
            bb.putInt(0x1B8, UUID.randomUUID().hashCode())
        }

        if (scheme == PartitionScheme.MBR) {
            active.forEachIndexed { idx, part ->
                val off = 0x1BE + (idx * 16)
                mbrSector[off] = if (part.isBootable) 0x80.toByte() else 0x00.toByte()
                mbrSector[off + 1] = 0xFE.toByte() // CHS not used: LBA-only markers
                mbrSector[off + 2] = 0xFF.toByte()
                mbrSector[off + 3] = 0xFF.toByte()
                mbrSector[off + 4] = (part.mbrTypeCode ?: (part.fileSystem.code.toInt() and 0xFF)).toByte()
                mbrSector[off + 5] = 0xFE.toByte()
                mbrSector[off + 6] = 0xFF.toByte()
                mbrSector[off + 7] = 0xFF.toByte()
                bb.putInt(off + 8, part.startLba.toInt())
                bb.putInt(off + 12, part.sectorCount.toInt())
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
            bb.putInt(off + 12, (total - 1L).coerceAtMost(0xFFFFFFFFL).toInt())
        }

        mbrSector[510] = 0x55.toByte()
        mbrSector[511] = 0xAA.toByte()

        if (scheme == PartitionScheme.MBR) {
            transport.writeBlocks(0L, mbrSector)
            // Remove stale GPT structures that no MBR partition covers, so tools do not see two tables.
            val firstStart = active.firstOrNull()?.startLba ?: Long.MAX_VALUE
            val lastEnd = active.maxOfOrNull { it.startLba + it.sectorCount - 1L } ?: -1L
            val gptHeaderPresent = String(transport.readBlocks(1L, 1), 0, 8, StandardCharsets.US_ASCII) == "EFI PART"
            if (gptHeaderPresent && firstStart > 1L + GPT_ARRAY_SECTORS) {
                transport.writeBlocks(1L, ByteArray(SECTOR_SIZE * (GPT_ARRAY_SECTORS + 1)))
            }
            val tailStart = total - 1L - GPT_ARRAY_SECTORS
            val backupPresent = String(transport.readBlocks(total - 1L, 1), 0, 8, StandardCharsets.US_ASCII) == "EFI PART"
            if (backupPresent && lastEnd < tailStart) {
                transport.writeBlocks(tailStart, ByteArray(SECTOR_SIZE * (GPT_ARRAY_SECTORS + 1)))
            }
            return
        }

        val entriesArray = ByteArray(GPT_ENTRY_COUNT * GPT_ENTRY_SIZE)
        val entriesBb = ByteBuffer.wrap(entriesArray).order(ByteOrder.LITTLE_ENDIAN)
        active.forEachIndexed { idx, part ->
            val base = idx * GPT_ENTRY_SIZE
            val typeGuidBytes = part.gptTypeGuid?.let { hexToBytes(it) }
                ?: if (part.fileSystem == FileSystemType.EXT4) LINUX_DATA_GUID else BASIC_DATA_GUID
            System.arraycopy(typeGuidBytes, 0, entriesArray, base, 16)

            val uniqueBytes = part.gptUniqueGuid?.let { hexToBytes(it) }
            if (uniqueBytes != null) {
                System.arraycopy(uniqueBytes, 0, entriesArray, base + 16, 16)
            } else {
                val partUuid = UUID.randomUUID()
                entriesBb.putLong(base + 16, partUuid.mostSignificantBits)
                entriesBb.putLong(base + 24, partUuid.leastSignificantBits)
            }
            entriesBb.putLong(base + 32, part.startLba)
            entriesBb.putLong(base + 40, part.startLba + part.sectorCount - 1L)
            val attrs = (part.gptAttributes and 0x04L.inv()) or (if (part.isBootable) 0x04L else 0L)
            entriesBb.putLong(base + 48, attrs)

            val utf16Name = part.label.take(36).toByteArray(StandardCharsets.UTF_16LE)
            System.arraycopy(utf16Name, 0, entriesArray, base + 56, minOf(utf16Name.size, 72))
        }
        val entriesCrc = CRC32().apply { update(entriesArray) }.value.toInt()

        // Keep the disk GUID when a valid GPT header is already there.
        val oldHeader = transport.readBlocks(1L, 1)
        val diskGuid = if (String(oldHeader, 0, 8, StandardCharsets.US_ASCII) == "EFI PART") {
            oldHeader.copyOfRange(56, 72)
        } else {
            val g = UUID.randomUUID()
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(g.mostSignificantBits).putLong(g.leastSignificantBits).array()
        }

        val lastLba = total - 1L
        val backupArrayLba = lastLba - GPT_ARRAY_SECTORS
        fun buildHeader(myLba: Long, alternateLba: Long, arrayLba: Long): ByteArray {
            val header = ByteArray(SECTOR_SIZE)
            val gbb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            System.arraycopy("EFI PART".toByteArray(StandardCharsets.US_ASCII), 0, header, 0, 8)
            gbb.putInt(8, 0x00010000)
            gbb.putInt(12, 92)
            gbb.putLong(24, myLba)
            gbb.putLong(32, alternateLba)
            gbb.putLong(40, 2L + GPT_ARRAY_SECTORS)
            gbb.putLong(48, backupArrayLba - 1L)
            System.arraycopy(diskGuid, 0, header, 56, 16)
            gbb.putLong(72, arrayLba)
            gbb.putInt(80, GPT_ENTRY_COUNT)
            gbb.putInt(84, GPT_ENTRY_SIZE)
            gbb.putInt(88, entriesCrc)
            gbb.putInt(16, CRC32().apply { update(header, 0, 92) }.value.toInt())
            return header
        }

        transport.writeBlocks(0L, mbrSector)
        transport.writeBlocks(2L, entriesArray)
        transport.writeBlocks(1L, buildHeader(myLba = 1L, alternateLba = lastLba, arrayLba = 2L))
        transport.writeBlocks(backupArrayLba, entriesArray)
        transport.writeBlocks(lastLba, buildHeader(myLba = lastLba, alternateLba = 1L, arrayLba = backupArrayLba))
    }

    private const val FAT32_RESERVED_SECTORS = 32L
    private const val FAT32_NUM_FATS = 2L

    /**
     * Returns (sectorsPerCluster, fatSizeSectors) for a FAT32 volume of [totalSectors] 512-byte sectors.
     * Uses the largest cluster size up to [clusterSize] that still gives a valid FAT32 cluster count.
     */
    @Throws(IOException::class)
    private fun fat32Geometry(totalSectors: Long, clusterSize: Int): Pair<Int, Long> {
        if (totalSectors > 0xFFFFFFFFL) {
            throw IOException("Partition is too large for FAT32 (the limit is 2 TiB).")
        }
        var sectorsPerCluster = (clusterSize / SECTOR_SIZE).coerceIn(1, 128)
        var fatSize32: Long
        var clusterCount: Long
        while (true) {
            val tmpVal2 = (256L * sectorsPerCluster + FAT32_NUM_FATS) / 2L
            fatSize32 = ((totalSectors - FAT32_RESERVED_SECTORS) + tmpVal2 - 1L) / tmpVal2
            clusterCount = (totalSectors - FAT32_RESERVED_SECTORS - FAT32_NUM_FATS * fatSize32) / sectorsPerCluster
            if (clusterCount >= FAT32_MIN_CLUSTERS || sectorsPerCluster == 1) break
            sectorsPerCluster /= 2
        }
        if (clusterCount < FAT32_MIN_CLUSTERS) {
            throw IOException("Partition is too small for FAT32 (it needs at least about 33 MB).")
        }
        if (clusterCount > FAT32_MAX_CLUSTERS) {
            throw IOException("Partition is too large for FAT32 with this cluster size. Choose a larger cluster size.")
        }
        return sectorsPerCluster to fatSize32
    }

    /** Throws before any write when a FAT32 volume of this size cannot be created. */
    @Throws(IOException::class)
    fun validateFat32Geometry(sectorCount: Long, clusterSize: Int) {
        fat32Geometry(sectorCount, clusterSize)
    }

    /**
     * Writes a complete, OS-mountable FAT32 volume at the partition's start LBA
     * (VBR + FSInfo + backup VBR, both FATs fully cleared, empty root directory with a volume label).
     * Other filesystems are refused: the app has no writer that produces a mountable volume for them.
     */
    @Throws(IOException::class)
    fun writeFileSystemAtPartition(
        transport: BlockDeviceTransport,
        startLba: Long,
        sectorCount: Long,
        fileSystem: FileSystemType,
        volumeLabel: String,
        clusterSize: Int,
        uuid: String
    ) {
        if (!fileSystem.canFormat) {
            throw IOException("Creating ${fileSystem.label} volumes is not supported. Use FAT32.")
        }
        requireSupportedSectorSize(transport)
        if (startLba < 0L || sectorCount <= 0L || startLba + sectorCount > transport.totalBlocks) {
            throw IOException("Partition LBA $startLba (+$sectorCount) is outside the drive.")
        }

        val paddedLabel = volumeLabel.uppercase().padEnd(11, ' ').take(11)
            .toByteArray(StandardCharsets.US_ASCII)
        val serialInt = uuid.hashCode()

        val reservedSectors = FAT32_RESERVED_SECTORS
        val numFats = FAT32_NUM_FATS
        val totalSectors = sectorCount
        val (sectorsPerCluster, fatSize32) = fat32Geometry(totalSectors, clusterSize)

        // 1. Primary FAT32 Boot Sector (VBR)
        val vbr = ByteArray(SECTOR_SIZE)
        val bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)
        vbr[0] = 0xEB.toByte()
        vbr[1] = 0x58.toByte()
        vbr[2] = 0x90.toByte()
        System.arraycopy("MSDOS5.0".toByteArray(StandardCharsets.US_ASCII), 0, vbr, 3, 8)
        bb.putShort(11, SECTOR_SIZE.toShort())
        vbr[13] = sectorsPerCluster.toByte()
        bb.putShort(14, reservedSectors.toInt().toShort())
        vbr[16] = numFats.toInt().toByte()
        bb.putShort(17, 0) // RootEntCnt = 0 for FAT32
        bb.putShort(19, 0) // TotSec16 = 0 for FAT32
        vbr[21] = 0xF8.toByte() // Fixed media
        bb.putShort(22, 0) // FATSz16 = 0
        bb.putShort(24, 63) // SecPerTrk
        bb.putShort(26, 255) // NumHeads
        bb.putInt(28, startLba.coerceAtMost(0xFFFFFFFFL).toInt()) // HiddSec
        bb.putInt(32, totalSectors.toInt()) // TotSec32
        bb.putInt(36, fatSize32.toInt()) // FATSz32
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

        // 3. Clear the reserved area, both FATs and the root cluster so no stale chains survive.
        val fat1Lba = startLba + reservedSectors
        val rootDirLba = fat1Lba + numFats * fatSize32
        val clearEnd = rootDirLba + sectorsPerCluster
        val zeroChunk = ByteArray(SECTOR_SIZE * 64)
        var lba = startLba
        while (lba < clearEnd) {
            val count = minOf(64L, clearEnd - lba).toInt()
            transport.writeBlocks(lba, if (count == 64) zeroChunk else ByteArray(count * SECTOR_SIZE))
            lba += count
        }

        transport.writeBlocks(startLba, vbr)
        transport.writeBlocks(startLba + 1L, fsInfo)
        // Backup boot sector at +6 and backup FSInfo at +7
        transport.writeBlocks(startLba + 6L, vbr)
        transport.writeBlocks(startLba + 7L, fsInfo)

        // 4. First FAT sector of each copy: media/EOC markers for clusters 0, 1 and root cluster 2.
        val fatFirstSector = ByteArray(SECTOR_SIZE)
        val fatBb = ByteBuffer.wrap(fatFirstSector).order(ByteOrder.LITTLE_ENDIAN)
        fatBb.putInt(0, 0x0FFFFFF8)
        fatBb.putInt(4, 0x0FFFFFFF)
        fatBb.putInt(8, 0x0FFFFFFF) // Root directory cluster 2 = End Of Chain
        transport.writeBlocks(fat1Lba, fatFirstSector)
        transport.writeBlocks(fat1Lba + fatSize32, fatFirstSector)

        // 5. Root Directory Cluster (Cluster 2) with Volume Label entry
        val rootClusterBytes = ByteArray(sectorsPerCluster * SECTOR_SIZE)
        System.arraycopy(paddedLabel, 0, rootClusterBytes, 0, 11)
        rootClusterBytes[11] = 0x08.toByte() // ATTR_VOLUME_ID
        transport.writeBlocks(rootDirLba, rootClusterBytes)
    }
}

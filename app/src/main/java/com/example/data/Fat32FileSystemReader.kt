package com.example.data

import com.example.model.UsbFileEntry
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone

/**
 * Direct read-only FAT32 filesystem parser over any BlockDeviceTransport (e.g., UsbScsiBlockTransport).
 * Allows listing real files and folders and reading file contents directly from a physical USB OTG drive
 * even before the user grants an Android SAF DocumentTree mount.
 */
class Fat32FileSystemReader(
    private val transport: BlockDeviceTransport,
    private val partitionStartLba: Long
) {
    private val bytesPerSector: Int
    private val sectorsPerCluster: Int
    private val reservedSectors: Int
    private val numFats: Int
    private val fatSizeSectors: Long
    private val rootCluster: Long
    private val fatStartLba: Long
    private val dataStartLba: Long
    private val clusterSizeBytes: Int

    private val fatSectorCache = HashMap<Long, ByteArray>()

    init {
        val vbr = transport.readBlocks(partitionStartLba, 1)
        if (vbr.size < 512) {
            throw IOException("Unable to read FAT32 boot sector at LBA $partitionStartLba.")
        }
        val bb = ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN)
        bytesPerSector = bb.getShort(11).toInt() and 0xFFFF
        sectorsPerCluster = vbr[13].toInt() and 0xFF
        reservedSectors = bb.getShort(14).toInt() and 0xFFFF
        numFats = vbr[16].toInt() and 0xFF
        val fatSize16 = bb.getShort(22).toInt() and 0xFFFF
        val fatSize32 = bb.getInt(36).toLong() and 0xFFFFFFFFL
        fatSizeSectors = if (fatSize16 != 0) fatSize16.toLong() else fatSize32
        rootCluster = bb.getInt(44).toLong() and 0x0FFFFFFFL

        val fsTag = String(vbr, 82, 8, StandardCharsets.US_ASCII)
        if (bytesPerSector < 512 || sectorsPerCluster == 0 || fatSizeSectors == 0L || rootCluster < 2L ||
            (!fsTag.startsWith("FAT32") && fatSize16 != 0)
        ) {
            throw IOException("Partition at LBA $partitionStartLba is not a valid FAT32 volume.")
        }

        fatStartLba = partitionStartLba + reservedSectors.toLong()
        dataStartLba = fatStartLba + (numFats.toLong() * fatSizeSectors)
        clusterSizeBytes = bytesPerSector * sectorsPerCluster
    }

    private data class FatEntry(
        val name: String,
        val isDirectory: Boolean,
        val firstCluster: Long,
        val sizeBytes: Long,
        val lastModifiedMillis: Long
    )

    fun listDirectory(relativePath: String): List<UsbFileEntry> {
        val segments = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        var currentCluster = rootCluster

        for (seg in segments) {
            val entries = readDirectoryClusterChain(currentCluster)
            val match = entries.firstOrNull { it.isDirectory && it.name.equals(seg, ignoreCase = true) }
                ?: throw IOException("Directory '$seg' not found on USB FAT32 volume.")
            currentCluster = if (match.firstCluster >= 2L) match.firstCluster else rootCluster
        }

        val cleanParent = relativePath.trimEnd('/').let { if (it.isEmpty()) "" else it }
        return readDirectoryClusterChain(currentCluster)
            .sortedWith(compareBy<FatEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { entry ->
                UsbFileEntry(
                    name = entry.name,
                    path = "$cleanParent/${entry.name}",
                    isDirectory = entry.isDirectory,
                    sizeBytes = if (entry.isDirectory) -1L else entry.sizeBytes,
                    lastModified = entry.lastModifiedMillis,
                    mimeType = if (entry.isDirectory) "inode/directory" else guessMime(entry.name),
                    documentUri = null,
                    documentId = null,
                    canWrite = false
                )
            }
    }

    fun readFileBytes(relativePath: String, maxBytes: Int): ByteArray {
        val segments = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ByteArray(0)
        val fileName = segments.last()
        var currentCluster = rootCluster

        for (seg in segments.dropLast(1)) {
            val entries = readDirectoryClusterChain(currentCluster)
            val match = entries.firstOrNull { it.isDirectory && it.name.equals(seg, ignoreCase = true) }
                ?: return ByteArray(0)
            currentCluster = if (match.firstCluster >= 2L) match.firstCluster else rootCluster
        }

        val fileEntry = readDirectoryClusterChain(currentCluster)
            .firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
            ?: return ByteArray(0)

        val targetLen = minOf(fileEntry.sizeBytes, maxBytes.toLong()).coerceAtLeast(0L).toInt()
        if (targetLen == 0 || fileEntry.firstCluster < 2L) return ByteArray(0)

        val out = ByteArray(targetLen)
        var written = 0
        var cluster = fileEntry.firstCluster
        var hops = 0

        while (written < targetLen && cluster in 2L..0x0FFFFFF6L && hops < 4096) {
            val clusterBytes = readCluster(cluster)
            val copyLen = minOf(clusterBytes.size, targetLen - written)
            System.arraycopy(clusterBytes, 0, out, written, copyLen)
            written += copyLen
            cluster = readFatNextCluster(cluster)
            hops++
        }
        return if (written == targetLen) out else out.copyOf(written)
    }

    private fun readDirectoryClusterChain(startCluster: Long): List<FatEntry> {
        val results = mutableListOf<FatEntry>()
        val lfnParts = HashMap<Int, String>()
        var cluster = startCluster
        var hops = 0
        var endReached = false

        while (!endReached && cluster in 2L..0x0FFFFFF6L && hops < 512) {
            val data = readCluster(cluster)
            var offset = 0
            while (offset + 32 <= data.size) {
                val firstByte = data[offset].toInt() and 0xFF
                if (firstByte == 0x00) {
                    endReached = true
                    break
                }
                if (firstByte == 0xE5) {
                    lfnParts.clear()
                    offset += 32
                    continue
                }
                val attr = data[offset + 11].toInt() and 0xFF
                if (attr == 0x0F) {
                    // VFAT Long File Name entry
                    val seq = firstByte and 0x1F
                    if ((firstByte and 0x40) != 0) lfnParts.clear()
                    lfnParts[seq] = decodeLfnSegment(data, offset)
                    offset += 32
                    continue
                }
                // Skip Volume Label entries
                if ((attr and 0x08) != 0) {
                    lfnParts.clear()
                    offset += 32
                    continue
                }

                val shortName = decodeShort83Name(data, offset)
                val fullName = if (lfnParts.isNotEmpty()) {
                    lfnParts.keys.sorted().joinToString("") { lfnParts[it].orEmpty() }.trim()
                        .ifBlank { shortName }
                } else {
                    shortName
                }
                lfnParts.clear()

                if (fullName != "." && fullName != ".." && fullName.isNotBlank()) {
                    val bb = ByteBuffer.wrap(data, offset, 32).order(ByteOrder.LITTLE_ENDIAN)
                    val highClus = (bb.getShort(20).toInt() and 0xFFFF).toLong()
                    val lowClus = (bb.getShort(26).toInt() and 0xFFFF).toLong()
                    val firstClus = ((highClus shl 16) or lowClus) and 0x0FFFFFFFL
                    val fileSize = bb.getInt(28).toLong() and 0xFFFFFFFFL
                    val wrtTime = bb.getShort(22).toInt() and 0xFFFF
                    val wrtDate = bb.getShort(24).toInt() and 0xFFFF
                    val isDir = (attr and 0x10) != 0

                    results.add(
                        FatEntry(
                            name = fullName,
                            isDirectory = isDir,
                            firstCluster = firstClus,
                            sizeBytes = if (isDir) 0L else fileSize,
                            lastModifiedMillis = decodeDosDateTime(wrtDate, wrtTime)
                        )
                    )
                }
                offset += 32
            }
            cluster = readFatNextCluster(cluster)
            hops++
        }
        return results
    }

    private fun readCluster(cluster: Long): ByteArray {
        val lba = dataStartLba + (cluster - 2L) * sectorsPerCluster.toLong()
        return transport.readBlocks(lba, sectorsPerCluster)
    }

    private fun readFatNextCluster(cluster: Long): Long {
        val fatByteOffset = cluster * 4L
        val fatSectorOffset = fatByteOffset / bytesPerSector.toLong()
        val offsetInSector = (fatByteOffset % bytesPerSector.toLong()).toInt()
        val lba = fatStartLba + fatSectorOffset

        val sector = fatSectorCache.getOrPut(lba) {
            transport.readBlocks(lba, 1)
        }
        if (offsetInSector + 4 > sector.size) return 0x0FFFFFFFL
        val value = ByteBuffer.wrap(sector, offsetInSector, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0x0FFFFFFFL
        return value
    }

    private fun decodeLfnSegment(data: ByteArray, off: Int): String {
        val chars = StringBuilder(13)
        val positions = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        for (p in positions) {
            val lo = data[off + p].toInt() and 0xFF
            val hi = data[off + p + 1].toInt() and 0xFF
            val code = (hi shl 8) or lo
            if (code == 0x0000 || code == 0xFFFF) break
            chars.append(code.toChar())
        }
        return chars.toString()
    }

    private fun decodeShort83Name(data: ByteArray, off: Int): String {
        val ntRes = data[off + 12].toInt() and 0xFF
        var base = String(data, off, 8, StandardCharsets.US_ASCII).trimEnd()
        var ext = String(data, off + 8, 3, StandardCharsets.US_ASCII).trimEnd()
        if ((ntRes and 0x08) != 0) base = base.lowercase()
        if ((ntRes and 0x10) != 0) ext = ext.lowercase()
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    private fun decodeDosDateTime(dosDate: Int, dosTime: Int): Long {
        if (dosDate == 0) return 0L
        val year = 1980 + ((dosDate ushr 9) and 0x7F)
        val month = ((dosDate ushr 5) and 0x0F).coerceIn(1, 12) - 1
        val day = (dosDate and 0x1F).coerceIn(1, 31)
        val hour = ((dosTime ushr 11) and 0x1F).coerceIn(0, 23)
        val minute = ((dosTime ushr 5) and 0x3F).coerceIn(0, 59)
        val second = ((dosTime and 0x1F) * 2).coerceIn(0, 59)
        return Calendar.getInstance(TimeZone.getDefault()).apply {
            set(year, month, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "iso", "img", "bin" -> "application/x-iso9660-image"
        "txt", "md", "log", "cfg", "ini" -> "text/plain"
        "json" -> "application/json"
        "jpg", "jpeg", "png", "webp" -> "image/jpeg"
        "mp4", "mov", "mkv" -> "video/mp4"
        else -> "application/octet-stream"
    }
}

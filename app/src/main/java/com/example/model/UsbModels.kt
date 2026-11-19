package com.example.model

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class DriveConnectionType(val badge: String) {
    USB_OTG_HARDWARE("USB-C OTG"),
    SD_CARD_READER("SDXC OTG"),
    SAF_MOUNTED_VOLUME("SAF VOLUME")
}

enum class FileSystemType(
    val label: String,
    val code: Byte,
    val clusterSizes: List<Int>,
    val description: String,
    val maxFileSizeLabel: String
) {
    FAT32(
        label = "FAT32",
        code = 0x0C.toByte(),
        clusterSizes = listOf(4096, 8192, 16384, 32768),
        description = "Universal compatibility across Android OTG, Windows, macOS, Linux, cameras & BIOS/UEFI boot.",
        maxFileSizeLabel = "4 GB Max File"
    ),
    EXFAT(
        label = "exFAT",
        code = 0x07.toByte(),
        clusterSizes = listOf(32768, 65536, 131072),
        description = "Optimized for high-capacity USB flash drives and SDXC cards (>32 GB) with no 4 GB file limit.",
        maxFileSizeLabel = "16 EB Max File"
    ),
    EXT4(
        label = "EXT4",
        code = 0x83.toByte(),
        clusterSizes = listOf(4096),
        description = "Native Linux & Android journaling file system with POSIX permissions and extent allocation.",
        maxFileSizeLabel = "16 TB Max File"
    ),
    NTFS(
        label = "NTFS",
        code = 0x07.toByte(),
        clusterSizes = listOf(4096, 8192, 16384, 65536),
        description = "Windows Master File Table (MFT) file system with large file & compression support.",
        maxFileSizeLabel = "8 PB Max File"
    ),
    ISO9660(
        label = "ISO-9660",
        code = 0x17.toByte(),
        clusterSizes = listOf(2048),
        description = "Optical & Hybrid El Torito bootable live image filesystem.",
        maxFileSizeLabel = "Read-Only Boot"
    ),
    UNALLOCATED(
        label = "Unallocated",
        code = 0x00.toByte(),
        clusterSizes = listOf(4096),
        description = "Raw unpartitioned disk sectors available for new volume allocation.",
        maxFileSizeLabel = "Raw LBA"
    )
}

enum class PartitionScheme(val label: String, val description: String) {
    GPT("GPT (GUID Partition Table)", "Modern UEFI standard with CRC32 integrity, 128 partitions, and >2 TB support"),
    MBR("MBR (Master Boot Record)", "Legacy BIOS 512-byte LBA 0 table with up to 4 primary partitions & 0x55AA signature")
}

@Immutable
data class UsbPartition(
    val id: String,
    val index: Int,
    val label: String,
    val fileSystem: FileSystemType,
    val startLba: Long,
    val sectorCount: Long,
    val sizeBytes: Long,
    val usedBytes: Long,
    val isBootable: Boolean = false,
    val flags: List<String> = emptyList(),
    val typeGuidOrHex: String = "0FC63DAF-8483-4772-8E79-3D69D8477DE4",
    val uuid: String = "4A8F-9C2E"
)

@Immutable
data class UsbDriveDevice(
    val id: String,
    val name: String,
    val vendorName: String,
    val connectionType: DriveConnectionType,
    val usbDeviceName: String?,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String,
    val usbSpec: String,
    val protocolName: String,
    val maxPowerMa: Int,
    val hasUsbPermission: Boolean,
    val totalBytes: Long,
    val logicalBlockSize: Int = 512,
    val partitionScheme: PartitionScheme,
    val partitions: List<UsbPartition>,
    val safTreeUri: Uri? = null,
    val backingImagePath: String,
    val mountRootPath: String
) {
    val usedBytes: Long
        get() = partitions
            .filter { it.fileSystem != FileSystemType.UNALLOCATED }
            .sumOf { it.usedBytes }

    val primaryFileSystem: FileSystemType
        get() = partitions.firstOrNull { it.fileSystem != FileSystemType.UNALLOCATED }?.fileSystem
            ?: FileSystemType.FAT32

    val vidPidHex: String
        get() = String.format("%04X:%04X", vendorId and 0xFFFF, productId and 0xFFFF)

    val supportsRawBlockIo: Boolean
        get() = connectionType != DriveConnectionType.SAF_MOUNTED_VOLUME && !usbDeviceName.isNullOrBlank()

    val supportsFileWrites: Boolean
        get() = safTreeUri != null
}

@Immutable
data class UsbFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String,
    val documentUri: Uri? = null,
    val documentId: String? = null,
    val canWrite: Boolean = true
) {
    val extension: String
        get() = if (isDirectory) "DIR" else name.substringAfterLast('.', "BIN").uppercase()
}

@Immutable
data class IsoFlashStatus(
    val isRunning: Boolean = false,
    val stage: String = "Idle",
    val progress: Float = 0f,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val speedMbps: Float = 0f,
    val sha256Hex: String = "",
    val verifiedSuccess: Boolean? = null,
    val errorMessage: String? = null
)

@Immutable
data class FormatOperationStatus(
    val isRunning: Boolean = false,
    val stepDescription: String = "",
    val progress: Float = 0f,
    val completedVolumeUuid: String? = null,
    val errorMessage: String? = null
)

@Immutable
data class OperationLogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false
)

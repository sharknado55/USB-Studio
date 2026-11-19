package com.example.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.model.DriveConnectionType
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.model.UsbDriveDevice
import com.example.model.UsbFileEntry
import com.example.model.UsbPartition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

class UsbDiskEngine(
    private val context: Context,
    private val blockTransportFactory: ((UsbDriveDevice) -> BlockDeviceTransport?)? = null
) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.aistudio.usbstudio.USB_PERMISSION"
        private const val SECTOR_SIZE = 512
        private const val STREAM_BLOCK_CHUNK_SECTORS = 64 // 32 KB per SCSI WRITE/READ(10)
    }

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val disksBaseDir: File
        get() = File(context.filesDir, "otg_disks")

    private val mountsBaseDir: File
        get() = File(context.filesDir, "otg_mounts")

    /**
     * Scans the physical Android USB OTG bus via UsbManager (reading REAL capacity and REAL MBR/GPT
     * partition layouts over USB Mass Storage BOT/SCSI when permission is granted) and merges with
     * active/persisted user-mounted SAF DocumentTree volumes. Never injects dummy drives.
     */
    suspend fun discoverConnectedDrives(
        existingDrives: List<UsbDriveDevice>
    ): List<UsbDriveDevice> = withContext(Dispatchers.IO) {
        purgeLegacyLoopbackArtifacts()

        val result = mutableListOf<UsbDriveDevice>()
        val linkedSafUris = mutableSetOf<String>()

        // 1. Query real physical Android UsbManager mass-storage devices
        val deviceList = usbManager?.deviceList ?: emptyMap()
        for ((deviceName, usbDevice) in deviceList) {
            if (!isMassStorageDevice(usbDevice)) continue

            val hasPerm = usbManager?.hasPermission(usbDevice) == true
            val existingHw = existingDrives.find {
                it.usbDeviceName == deviceName || it.id == "usb_hw_${usbDevice.deviceId}"
            }
            val linkedSafUri = existingHw?.safTreeUri?.takeIf { isTreeUriAccessible(it) }
            if (linkedSafUri != null) {
                linkedSafUris.add(linkedSafUri.toString())
            }

            var totalCapacity = existingHw?.totalBytes ?: 0L
            var blockSize = existingHw?.logicalBlockSize ?: SECTOR_SIZE
            var protocolLabel = detectUsbProtocolName(usbDevice)
            var scheme = existingHw?.partitionScheme ?: PartitionScheme.MBR
            var partitions = existingHw?.partitions ?: emptyList()
            var vendorName = usbDevice.manufacturerName?.takeIf { it.isNotBlank() }
                ?: lookupUsbVendorName(usbDevice.vendorId)
            var productName = usbDevice.productName?.takeIf { it.isNotBlank() }
                ?: "USB Mass Storage ($deviceName)"

            if (hasPerm) {
                runCatching {
                    UsbScsiBlockTransport.open(usbManager, usbDevice).use { transport ->
                        totalCapacity = transport.totalBytes
                        blockSize = transport.blockSize
                        protocolLabel = "${detectUsbProtocolName(usbDevice)} • SCSI Direct"
                        if (transport.inquiryVendor.isNotBlank() && usbDevice.manufacturerName.isNullOrBlank()) {
                            vendorName = transport.inquiryVendor
                        }
                        if (transport.inquiryProduct.isNotBlank() && usbDevice.productName.isNullOrBlank()) {
                            productName = transport.inquiryProduct
                        }
                        val (probedScheme, probedParts) = BlockDiskStructures.readPartitionLayout(transport)
                        scheme = probedScheme
                        partitions = probedParts
                    }
                }
            }

            // Enrich used/total stats from linked SAF volume if present
            if (linkedSafUri != null) {
                val docId = runCatching { DocumentsContract.getTreeDocumentId(linkedSafUri) }.getOrDefault("")
                val volKey = docId.substringBefore(':')
                val safStats = querySafVolumeStats(linkedSafUri, volKey)
                if (totalCapacity <= 0L && safStats.totalBytes > 0L) {
                    totalCapacity = safStats.totalBytes
                }
                if (safStats.usedBytes > 0L && partitions.isNotEmpty()) {
                    val firstAllocIdx = partitions.indexOfFirst { it.fileSystem != FileSystemType.UNALLOCATED }
                    if (firstAllocIdx >= 0) {
                        partitions = partitions.mapIndexed { idx, p ->
                            if (idx == firstAllocIdx) p.copy(usedBytes = safStats.usedBytes) else p
                        }
                    }
                }
            }

            result.add(
                UsbDriveDevice(
                    id = "usb_hw_${usbDevice.deviceId}",
                    name = productName,
                    vendorName = vendorName,
                    connectionType = if (productName.contains("SD", ignoreCase = true)) {
                        DriveConnectionType.SD_CARD_READER
                    } else {
                        DriveConnectionType.USB_OTG_HARDWARE
                    },
                    usbDeviceName = deviceName,
                    vendorId = usbDevice.vendorId,
                    productId = usbDevice.productId,
                    serialNumber = runCatching { if (hasPerm) usbDevice.serialNumber else null }.getOrNull()
                        ?: String.format("VID%04X-PID%04X", usbDevice.vendorId and 0xFFFF, usbDevice.productId and 0xFFFF),
                    usbSpec = usbDevice.version,
                    protocolName = if (linkedSafUri != null) "$protocolLabel + SAF" else protocolLabel,
                    maxPowerMa = getUsbMaxPowerMa(usbDevice),
                    hasUsbPermission = hasPerm,
                    totalBytes = totalCapacity,
                    logicalBlockSize = blockSize,
                    partitionScheme = scheme,
                    partitions = partitions,
                    safTreeUri = linkedSafUri,
                    backingImagePath = "",
                    mountRootPath = ""
                )
            )
        }

        // 2. Preserve any active user-mounted standalone SAF volumes (that are still accessible)
        val knownSafUris = linkedSafUris.toMutableSet()
        existingDrives.filter { it.connectionType == DriveConnectionType.SAF_MOUNTED_VOLUME }.forEach { safDrive ->
            val uri = safDrive.safTreeUri
            val uriStr = uri?.toString()
            if (uri != null && uriStr != null && uriStr !in knownSafUris) {
                if (isTreeUriAccessible(uri)) {
                    knownSafUris.add(uriStr)
                    // Refresh live capacity / used bytes
                    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
                    val volKey = docId.substringBefore(':')
                    val liveStats = querySafVolumeStats(uri, volKey)
                    val updatedTotal = if (liveStats.totalBytes > 0L) liveStats.totalBytes else safDrive.totalBytes
                    val updatedUsed = if (liveStats.usedBytes >= 0L && liveStats.totalBytes > 0L) {
                        liveStats.usedBytes
                    } else {
                        safDrive.usedBytes
                    }
                    val updatedParts = safDrive.partitions.mapIndexed { idx, p ->
                        if (idx == 0) {
                            p.copy(
                                sizeBytes = updatedTotal,
                                sectorCount = if (updatedTotal > 0L) updatedTotal / SECTOR_SIZE else p.sectorCount,
                                usedBytes = updatedUsed
                            )
                        } else p
                    }
                    result.add(safDrive.copy(totalBytes = updatedTotal, partitions = updatedParts))
                }
            }
        }

        // 3. Restore previously granted persisted SAF document-tree permissions across app restarts
        val persistedUris = runCatching {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri }
        }.getOrDefault(emptyList())

        for (uri in persistedUris) {
            if (uri.toString() !in knownSafUris && isTreeUriAccessible(uri)) {
                knownSafUris.add(uri.toString())
                result.add(createSafMountedDrive(uri))
            }
        }

        result
    }

    /**
     * Checks if runtime USB Host permission is already granted for a device.
     */
    fun hasUsbPermission(usbDeviceName: String): Boolean {
        val manager = usbManager ?: return false
        val device = manager.deviceList[usbDeviceName] ?: return false
        return manager.hasPermission(device)
    }

    /**
     * Requests runtime USB Host permission for an attached physical OTG device.
     * Returns Pair(requestSent, alreadyGranted).
     */
    fun requestUsbPermission(usbDeviceName: String): Pair<Boolean, Boolean> {
        val manager = usbManager ?: return false to false
        val device = manager.deviceList[usbDeviceName] ?: return false to false
        if (manager.hasPermission(device)) return true to true

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        manager.requestPermission(device, pendingIntent)
        return true to false
    }

    /**
     * Mounts an external USB / SD Card directory selected via Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
     * Queries real volume metadata and capacity from DocumentsContract / StatFs with zero fake files.
     */
    suspend fun createSafMountedDrive(treeUri: Uri): UsbDriveDevice = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("volume:root")
        val rawVolumeKey = docId.substringBefore(':').ifBlank { "USB_VOL" }
        val safeFileKey = rawVolumeKey.lowercase().replace(Regex("[^a-z0-9_-]"), "_")

        val stats = querySafVolumeStats(treeUri, rawVolumeKey)
        val displayTitle = stats.title?.takeIf { it.isNotBlank() }
            ?: queryDocumentDisplayName(treeUri, docId)?.takeIf { it.isNotBlank() }
            ?: "External Volume ($rawVolumeKey)"
        val volumeLabel = rawVolumeKey.uppercase().take(11)
        val totalCap = stats.totalBytes
        val usedBytes = stats.usedBytes

        val partitions = listOf(
            UsbPartition(
                id = "saf_p1_$safeFileKey",
                index = 1,
                label = volumeLabel,
                fileSystem = FileSystemType.EXFAT,
                startLba = 2048L,
                sectorCount = (totalCap / SECTOR_SIZE).coerceAtLeast(0L),
                sizeBytes = totalCap,
                usedBytes = usedBytes,
                isBootable = false,
                flags = listOf("SAF_MOUNTED", "RW"),
                uuid = volumeLabel.take(8)
            )
        )

        UsbDriveDevice(
            id = "saf_${safeFileKey}_${UUID.randomUUID().toString().take(6)}",
            name = displayTitle,
            vendorName = "Android Storage Access Framework",
            connectionType = DriveConnectionType.SAF_MOUNTED_VOLUME,
            usbDeviceName = null,
            vendorId = 0x18D1,
            productId = 0x4EE1,
            serialNumber = "SAF-$volumeLabel",
            usbSpec = "OTG / SAF DocumentTree",
            protocolName = "DocumentsContract Provider",
            maxPowerMa = 500,
            hasUsbPermission = true,
            totalBytes = totalCap,
            logicalBlockSize = SECTOR_SIZE,
            partitionScheme = PartitionScheme.GPT,
            partitions = partitions,
            safTreeUri = treeUri,
            backingImagePath = "",
            mountRootPath = ""
        )
    }

    /**
     * Reads raw 512-byte sector(s) directly from the physical USB drive via SCSI READ(10).
     */
    @Throws(IOException::class)
    suspend fun readRawSector(
        drive: UsbDriveDevice,
        lbaIndex: Long,
        sectorCount: Int = 1
    ): ByteArray = withContext(Dispatchers.IO) {
        openBlockTransport(drive).use { transport ->
            transport.readBlocks(lbaIndex.coerceAtLeast(0L), sectorCount.coerceAtLeast(1))
        }
    }

    /**
     * Formats a physical USB OTG drive directly over USB Mass Storage SCSI WRITE(10)
     * (writing real MBR/GPT partition tables + real FAT32/exFAT/NTFS/EXT4 headers + SYNCHRONIZE CACHE),
     * OR performs a real recursive SAF file/folder wipe on SAF-only volumes.
     */
    @Throws(IOException::class)
    suspend fun formatDriveOrPartition(
        drive: UsbDriveDevice,
        targetPartitionId: String?,
        newLabel: String,
        fileSystem: FileSystemType,
        clusterSize: Int,
        partitionScheme: PartitionScheme,
        quickFormat: Boolean,
        wipeFullDrive: Boolean,
        onProgress: (Float, String) -> Unit
    ): Pair<UsbDriveDevice, String> = withContext(Dispatchers.IO) {
        val cleanLabel = newLabel.trim().ifEmpty { "USB_VOLUME" }.uppercase().take(11)
        val newUuid = generateVolumeUuid(fileSystem)

        // Case 1: SAF-only mounted volume (no direct USB Host SCSI access)
        val injectedTransport = blockTransportFactory?.invoke(drive)
        if (!drive.supportsRawBlockIo && injectedTransport == null) {
            val safUri = drive.safTreeUri
                ?: throw IOException("No writable USB OTG device or SAF volume attached.")

            onProgress(0.15f, "Scanning files and folders on ${drive.name}...")
            val deletedCount = clearSafTreeChildren(safUri) { fraction, itemName ->
                onProgress(0.15f + fraction * 0.75f, "Erasing $itemName...")
            }
            onProgress(1.0f, "Erased $deletedCount top-level item(s) from ${drive.name} via SAF")
            val updatedPartitions = drive.partitions.map { p -> p.copy(usedBytes = 0L) }
            return@withContext drive.copy(partitions = updatedPartitions) to "SAF-WIPED"
        }

        // Case 2: Physical USB OTG drive (or injected test block transport) -> REAL SCSI block writes!
        onProgress(0.08f, "Claiming USB Mass Storage interface for ${drive.name}...")
        (injectedTransport ?: openBlockTransport(drive)).use { transport ->
            val blockSize = transport.blockSize
            val totalBlocks = transport.totalBlocks
            val totalBytes = transport.totalBytes

            if (!quickFormat) {
                // Zero-wipe MBR/GPT, first 4 MB (8192 sectors) of volume headers/FAT tables, and backup GPT tail
                onProgress(0.18f, "Zero-wiping partition headers, FAT regions & backup GPT...")
                val zeroChunk = ByteArray(blockSize * STREAM_BLOCK_CHUNK_SECTORS)
                val wipeSectors = minOf(8192L, totalBlocks)
                var lba = 0L
                while (lba < wipeSectors) {
                    val count = minOf(STREAM_BLOCK_CHUNK_SECTORS.toLong(), wipeSectors - lba).toInt()
                    val buf = if (count == STREAM_BLOCK_CHUNK_SECTORS) zeroChunk else ByteArray(count * blockSize)
                    transport.writeBlocks(lba, buf)
                    lba += count
                    val frac = lba.toFloat() / wipeSectors.toFloat()
                    onProgress(0.18f + frac * 0.30f, "Zeroing LBA $lba / $wipeSectors...")
                }
                if (totalBlocks > 64L) {
                    val tailStart = totalBlocks - 34L
                    transport.writeBlocks(tailStart, ByteArray(34 * blockSize))
                }
            } else {
                onProgress(0.25f, "Clearing existing partition signatures & boot sectors...")
                val clearHeaderSectors = minOf(64L, totalBlocks).toInt()
                transport.writeBlocks(0L, ByteArray(clearHeaderSectors * blockSize))
            }

            val startLba = if (totalBlocks > 4096L) 2048L else 64L
            val usableSectors = (totalBlocks - startLba - if (partitionScheme == PartitionScheme.GPT) 34L else 0L)
                .coerceAtLeast(1L)

            val updatedPartitions = if (wipeFullDrive || targetPartitionId == null || drive.partitions.isEmpty()) {
                listOf(
                    UsbPartition(
                        id = "part_${UUID.randomUUID().toString().take(6)}",
                        index = 1,
                        label = cleanLabel,
                        fileSystem = fileSystem,
                        startLba = startLba,
                        sectorCount = usableSectors,
                        sizeBytes = usableSectors * blockSize.toLong(),
                        usedBytes = 0L,
                        isBootable = false,
                        flags = listOf("PRIMARY", "${clusterSize / 1024}K_CLUSTER"),
                        typeGuidOrHex = if (partitionScheme == PartitionScheme.GPT) {
                            "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
                        } else {
                            String.format("0x%02X", fileSystem.code)
                        },
                        uuid = newUuid
                    )
                )
            } else {
                drive.partitions.map { part ->
                    if (part.id == targetPartitionId) {
                        part.copy(
                            label = cleanLabel,
                            fileSystem = fileSystem,
                            usedBytes = 0L,
                            flags = listOf("FORMATTED", "${clusterSize / 1024}K_CLUSTER"),
                            uuid = newUuid
                        )
                    } else part
                }
            }

            onProgress(0.58f, "Writing ${partitionScheme.name} partition table to LBA 0..33...")
            BlockDiskStructures.writePartitionTable(transport, partitionScheme, updatedPartitions)

            val formattedTarget = if (wipeFullDrive || targetPartitionId == null) {
                updatedPartitions.first()
            } else {
                updatedPartitions.find { it.id == targetPartitionId } ?: updatedPartitions.first()
            }

            onProgress(0.78f, "Writing ${fileSystem.label} filesystem structures at LBA ${formattedTarget.startLba}...")
            BlockDiskStructures.writeFileSystemAtPartition(
                transport = transport,
                startLba = formattedTarget.startLba,
                sectorCount = formattedTarget.sectorCount,
                fileSystem = fileSystem,
                volumeLabel = cleanLabel,
                clusterSize = clusterSize,
                uuid = newUuid
            )

            onProgress(0.94f, "Flushing USB drive write cache (SYNCHRONIZE CACHE)...")
            transport.syncCache()

            onProgress(1.0f, "Formatted $cleanLabel as ${fileSystem.label} ($newUuid)")
            val updatedDrive = drive.copy(
                totalBytes = totalBytes,
                logicalBlockSize = blockSize,
                partitionScheme = partitionScheme,
                partitions = updatedPartitions
            )
            updatedDrive to newUuid
        }
    }

    /**
     * Commits a modified Partition Table (new partitions, deleted partitions, or scheme conversion MBR <-> GPT)
     * directly to the physical USB drive's LBA 0..33 sectors and formats any newly created partitions.
     */
    @Throws(IOException::class)
    suspend fun commitPartitionTable(
        drive: UsbDriveDevice,
        newScheme: PartitionScheme,
        newPartitions: List<UsbPartition>
    ): UsbDriveDevice = withContext(Dispatchers.IO) {
        openBlockTransport(drive).use { transport ->
            val oldIds = drive.partitions.map { it.id }.toSet()

            BlockDiskStructures.writePartitionTable(transport, newScheme, newPartitions)

            // Initialize filesystem headers for newly created partitions
            newPartitions
                .filter { it.fileSystem != FileSystemType.UNALLOCATED && it.id !in oldIds }
                .forEach { newPart ->
                    BlockDiskStructures.writeFileSystemAtPartition(
                        transport = transport,
                        startLba = newPart.startLba,
                        sectorCount = newPart.sectorCount,
                        fileSystem = newPart.fileSystem,
                        volumeLabel = newPart.label,
                        clusterSize = newPart.fileSystem.clusterSizes.firstOrNull() ?: 4096,
                        uuid = newPart.uuid
                    )
                }

            transport.syncCache()

            drive.copy(
                totalBytes = transport.totalBytes,
                logicalBlockSize = transport.blockSize,
                partitionScheme = newScheme,
                partitions = newPartitions
            )
        }
    }

    /**
     * Streams a user-selected external .iso or .img URI block-by-block directly to LBA 0..N of the
     * physical USB drive via SCSI WRITE(10), computing SHA-256 on the fly and optionally performing
     * full read-back SHA-256 verification via SCSI READ(10).
     */
    @Throws(IOException::class)
    suspend fun flashIsoImageToDrive(
        drive: UsbDriveDevice,
        externalUri: Uri,
        externalName: String,
        verifyAfterWrite: Boolean,
        onStatus: (
            stage: String,
            progress: Float,
            bytesDone: Long,
            totalBytes: Long,
            speedMbps: Float
        ) -> Unit
    ): Triple<UsbDriveDevice, String, Boolean> = withContext(Dispatchers.IO) {
        val isoDisplayName = externalName.ifBlank { "Image.iso" }
        val volumeLabel = isoDisplayName.substringBeforeLast('.').take(11).uppercase().ifBlank { "ISO_VOLUME" }
        val queriedSize = queryUriSize(externalUri)

        onStatus("Claiming USB block device...", 0.02f, 0L, queriedSize.coerceAtLeast(1L), 0f)

        openBlockTransport(drive).use { transport ->
            val blockSize = transport.blockSize
            val chunkBytesSize = STREAM_BLOCK_CHUNK_SECTORS * blockSize
            if (queriedSize > 0L && queriedSize > transport.totalBytes) {
                throw IOException("Image size (${queriedSize / (1024 * 1024)} MB) exceeds USB drive capacity (${transport.totalBytes / (1024 * 1024)} MB).")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val startTimeNs = System.nanoTime()
            var totalBytesWritten = 0L
            var currentLba = 0L

            context.contentResolver.openInputStream(externalUri)?.use { input ->
                val chunkBuf = ByteArray(chunkBytesSize)
                while (true) {
                    var filled = 0
                    while (filled < chunkBuf.size) {
                        val r = input.read(chunkBuf, filled, chunkBuf.size - filled)
                        if (r == -1) break
                        filled += r
                    }
                    if (filled == 0) break

                    digest.update(chunkBuf, 0, filled)

                    val blocksNeeded = (filled + blockSize - 1) / blockSize
                    val writeLen = blocksNeeded * blockSize
                    if (currentLba + blocksNeeded > transport.totalBlocks) {
                        throw IOException("Image exceeds physical USB drive capacity at LBA $currentLba.")
                    }
                    val writeBuf = if (writeLen == chunkBuf.size && filled == chunkBuf.size) {
                        chunkBuf
                    } else {
                        ByteArray(writeLen).also {
                            System.arraycopy(chunkBuf, 0, it, 0, filled)
                        }
                    }

                    transport.writeBlocks(currentLba, writeBuf)
                    currentLba += blocksNeeded
                    totalBytesWritten += filled

                    val denom = maxOf(queriedSize, totalBytesWritten, 1L)
                    val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                    val speedMbps = ((totalBytesWritten / (1024.0 * 1024.0)) / elapsedSec).toFloat().coerceAtLeast(0.1f)
                    val writeFraction = (totalBytesWritten.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
                    val overallProgress = if (verifyAfterWrite) writeFraction * 0.72f else writeFraction * 0.96f

                    onStatus(
                        "Writing LBA $currentLba ($isoDisplayName)...",
                        overallProgress,
                        totalBytesWritten,
                        denom,
                        speedMbps
                    )
                }
            } ?: throw IOException("Unable to open selected image file.")

            if (totalBytesWritten <= 0L) {
                throw IOException("Selected image file is empty.")
            }

            onStatus("Flushing USB cache...", if (verifyAfterWrite) 0.74f else 0.98f, totalBytesWritten, totalBytesWritten, 0f)
            transport.syncCache()

            val expectedSha256 = digest.digest().joinToString("") { "%02x".format(it) }

            // Real SCSI READ(10) read-back SHA-256 verification
            var verified = true
            if (verifyAfterWrite) {
                val verifyDigest = MessageDigest.getInstance("SHA-256")
                val verifyStartNs = System.nanoTime()
                var verifyLba = 0L
                var remaining = totalBytesWritten
                var verifiedBytes = 0L

                while (remaining > 0L) {
                    val wantBytes = minOf(chunkBytesSize.toLong(), remaining).toInt()
                    val blocksToRead = (wantBytes + blockSize - 1) / blockSize
                    val readData = transport.readBlocks(verifyLba, blocksToRead)
                    verifyDigest.update(readData, 0, wantBytes)

                    verifyLba += blocksToRead
                    remaining -= wantBytes
                    verifiedBytes += wantBytes

                    val elapsedSec = ((System.nanoTime() - verifyStartNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                    val speedMbps = ((verifiedBytes / (1024.0 * 1024.0)) / elapsedSec).toFloat().coerceAtLeast(0.1f)
                    val verifyProgress = 0.75f + 0.25f * (verifiedBytes.toFloat() / totalBytesWritten.toFloat())
                    onStatus(
                        "Verifying SHA-256 sectors (LBA $verifyLba)...",
                        verifyProgress,
                        verifiedBytes,
                        totalBytesWritten,
                        speedMbps
                    )
                }

                val actualSha256 = verifyDigest.digest().joinToString("") { "%02x".format(it) }
                verified = expectedSha256.equals(actualSha256, ignoreCase = true)
            }

            val probed = runCatching { BlockDiskStructures.readPartitionLayout(transport) }.getOrNull()
            val (finalScheme, finalPartitions) = if (probed != null &&
                probed.second.any { it.fileSystem != FileSystemType.UNALLOCATED }
            ) {
                probed
            } else {
                val isoPart = UsbPartition(
                    id = "part_iso_${UUID.randomUUID().toString().take(6)}",
                    index = 1,
                    label = volumeLabel,
                    fileSystem = FileSystemType.ISO9660,
                    startLba = 0L,
                    sectorCount = (totalBytesWritten / blockSize).coerceAtLeast(1L),
                    sizeBytes = totalBytesWritten,
                    usedBytes = totalBytesWritten,
                    isBootable = true,
                    flags = listOf("BOOT", "ISO9660"),
                    typeGuidOrHex = "0x17 (ISO-9660)",
                    uuid = expectedSha256.take(8).uppercase()
                )
                val remainingBytes = (transport.totalBytes - totalBytesWritten).coerceAtLeast(0L)
                val list = buildList {
                    add(isoPart)
                    if (remainingBytes > 0L) {
                        add(
                            UsbPartition(
                                id = "part_unalloc_after_iso",
                                index = 2,
                                label = "Unallocated",
                                fileSystem = FileSystemType.UNALLOCATED,
                                startLba = (totalBytesWritten / blockSize) + 1L,
                                sectorCount = remainingBytes / blockSize,
                                sizeBytes = remainingBytes,
                                usedBytes = 0L,
                                isBootable = false,
                                flags = emptyList(),
                                typeGuidOrHex = "0x00",
                                uuid = "----"
                            )
                        )
                    }
                }
                PartitionScheme.MBR to list
            }

            val updatedDrive = drive.copy(
                totalBytes = transport.totalBytes,
                logicalBlockSize = blockSize,
                partitionScheme = finalScheme,
                partitions = finalPartitions
            )

            Triple(updatedDrive, expectedSha256, verified)
        }
    }

    // =========================================================================
    // File Manager Operations (Live SAF DocumentsContract + Direct SCSI FAT32 Reader)
    // =========================================================================

    @Throws(IOException::class)
    suspend fun listDirectory(
        drive: UsbDriveDevice,
        relativePath: String,
        safDocumentId: String? = null
    ): List<UsbFileEntry> = withContext(Dispatchers.IO) {
        // 1. Live SAF DocumentTree (supports full read/write for FAT32, exFAT, NTFS, etc.)
        val safUri = drive.safTreeUri
        if (safUri != null) {
            return@withContext listSafDirectory(safUri, relativePath, safDocumentId)
        }

        // 2. Direct USB SCSI FAT32 read-only fallback when hardware is connected without SAF mounted
        val directTransport = blockTransportFactory?.invoke(drive)
            ?: if (drive.supportsRawBlockIo) openBlockTransport(drive) else null
        if (directTransport != null) {
            directTransport.use { transport ->
                val fat32Part = drive.partitions.firstOrNull { it.fileSystem == FileSystemType.FAT32 }
                    ?: BlockDiskStructures.readPartitionLayout(transport).second
                        .firstOrNull { it.fileSystem == FileSystemType.FAT32 }
                if (fat32Part != null) {
                    val reader = Fat32FileSystemReader(transport, fat32Part.startLba)
                    return@withContext reader.listDirectory(relativePath)
                }
                throw IOException("Direct raw browsing supports FAT32 partitions. Tap 'Mount Folder Access (SAF)' to browse & edit files on ${drive.primaryFileSystem.label} volumes.")
            }
        }

        throw IOException("Mount the USB folder via SAF or grant USB Host permission to browse files.")
    }

    @Throws(IOException::class)
    suspend fun createFolder(
        drive: UsbDriveDevice,
        parentRelativePath: String,
        folderName: String,
        parentDocumentId: String? = null
    ): UsbFileEntry = withContext(Dispatchers.IO) {
        val sanitized = folderName.trim().replace("/", "_")
        if (sanitized.isEmpty()) throw IOException("Folder name cannot be empty.")

        val safUri = drive.safTreeUri
            ?: throw IOException("Creating folders requires folder access. Tap 'Mount Folder Access (SAF)' and select your USB drive.")

        val parentDocId = parentDocumentId ?: resolveSafDocumentId(safUri, parentRelativePath)
            ?: throw IOException("Unable to locate folder '$parentRelativePath' on USB drive.")

        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(safUri, parentDocId)
        val createdUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            sanitized
        ) ?: throw IOException("Android Storage Provider refused to create folder '$sanitized'.")

        val createdDocId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrNull()
        val cleanParent = parentRelativePath.trimEnd('/').let { if (it.isEmpty()) "" else it }
        UsbFileEntry(
            name = sanitized,
            path = "$cleanParent/$sanitized",
            isDirectory = true,
            sizeBytes = -1L,
            lastModified = System.currentTimeMillis(),
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            documentUri = createdUri,
            documentId = createdDocId,
            canWrite = true
        )
    }

    @Throws(IOException::class)
    suspend fun writeTextFile(
        drive: UsbDriveDevice,
        parentRelativePath: String,
        fileName: String,
        content: String,
        parentDocumentId: String? = null,
        existingDocumentUri: Uri? = null
    ): Uri = withContext(Dispatchers.IO) {
        val sanitized = fileName.trim().replace("/", "_")
        if (sanitized.isEmpty()) throw IOException("File name cannot be empty.")

        val safUri = drive.safTreeUri
            ?: throw IOException("Writing files requires folder access. Tap 'Mount Folder Access (SAF)' and select your USB drive.")

        val targetDocUri: Uri = if (existingDocumentUri != null) {
            existingDocumentUri
        } else {
            val parentDocId = parentDocumentId ?: resolveSafDocumentId(safUri, parentRelativePath)
                ?: throw IOException("Unable to locate parent folder '$parentRelativePath' on USB drive.")
            val existingChildId = findDirectChildDocumentId(safUri, parentDocId, sanitized)
            if (existingChildId != null) {
                DocumentsContract.buildDocumentUriUsingTree(safUri, existingChildId)
            } else {
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(safUri, parentDocId)
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    safCreateMimeType(sanitized),
                    sanitized
                ) ?: throw IOException("Android Storage Provider refused to create file '$sanitized'.")
            }
        }

        val payload = content.toByteArray(Charsets.UTF_8)
        openSafOutputStream(targetDocUri).use { out ->
            out.write(payload)
            out.flush()
        }
        targetDocUri
    }

    @Throws(IOException::class)
    suspend fun readFileBytes(
        drive: UsbDriveDevice,
        entry: UsbFileEntry,
        maxBytes: Int = 16384
    ): ByteArray = withContext(Dispatchers.IO) {
        val directUri = entry.documentUri
        if (directUri != null) {
            return@withContext readBytesFromContentUri(directUri, maxBytes)
        }

        val safUri = drive.safTreeUri
        if (safUri != null) {
            val docId = entry.documentId ?: resolveSafDocumentId(safUri, entry.path)
                ?: throw IOException("File '${entry.name}' could not be located on USB drive.")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(safUri, docId)
            return@withContext readBytesFromContentUri(docUri, maxBytes)
        }

        val directTransport = blockTransportFactory?.invoke(drive)
            ?: if (drive.supportsRawBlockIo) openBlockTransport(drive) else null
        if (directTransport != null) {
            directTransport.use { transport ->
                val fat32Part = drive.partitions.firstOrNull { it.fileSystem == FileSystemType.FAT32 }
                    ?: BlockDiskStructures.readPartitionLayout(transport).second
                        .firstOrNull { it.fileSystem == FileSystemType.FAT32 }
                    ?: throw IOException("No readable FAT32 partition found.")
                return@withContext Fat32FileSystemReader(transport, fat32Part.startLba)
                    .readFileBytes(entry.path, maxBytes)
            }
        }

        throw IOException("Unable to read '${entry.name}': no active SAF mount or USB Host transport.")
    }

    @Throws(IOException::class)
    suspend fun importExternalUriToDrive(
        drive: UsbDriveDevice,
        parentRelativePath: String,
        sourceUri: Uri,
        fileName: String,
        parentDocumentId: String? = null
    ): String = withContext(Dispatchers.IO) {
        val sanitized = fileName.trim().replace("/", "_").ifBlank { "imported_file.bin" }
        val safUri = drive.safTreeUri
            ?: throw IOException("Importing files requires folder access. Tap 'Mount Folder Access (SAF)' and select your USB drive.")

        val parentDocId = parentDocumentId ?: resolveSafDocumentId(safUri, parentRelativePath)
            ?: throw IOException("Unable to locate destination folder '$parentRelativePath' on USB drive.")

        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(safUri, parentDocId)
        val createdUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentDocUri,
            safCreateMimeType(sanitized),
            sanitized
        ) ?: throw IOException("Unable to create '$sanitized' on USB drive.")

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            openSafOutputStream(createdUri).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
                output.flush()
            }
        } ?: throw IOException("Unable to read selected source file.")

        val actualName = runCatching {
            val createdId = DocumentsContract.getDocumentId(createdUri)
            queryDocumentDisplayName(safUri, createdId)
        }.getOrNull()

        actualName?.takeIf { it.isNotBlank() } ?: sanitized
    }

    @Throws(IOException::class)
    suspend fun deleteEntry(
        drive: UsbDriveDevice,
        entry: UsbFileEntry
    ): Boolean = withContext(Dispatchers.IO) {
        if (entry.path.isBlank() || entry.path == "/") return@withContext false
        val safUri = drive.safTreeUri
            ?: throw IOException("Deleting files requires folder access. Tap 'Mount Folder Access (SAF)' and select your USB drive.")

        val docUri = entry.documentUri ?: run {
            val docId = entry.documentId ?: resolveSafDocumentId(safUri, entry.path)
                ?: throw IOException("Item '${entry.name}' was not found on USB drive.")
            DocumentsContract.buildDocumentUriUsingTree(safUri, docId)
        }

        val deleted = DocumentsContract.deleteDocument(context.contentResolver, docUri)
        if (!deleted) {
            throw IOException("Storage provider refused to delete '${entry.name}'.")
        }
        true
    }

    @Throws(IOException::class)
    suspend fun renameEntry(
        drive: UsbDriveDevice,
        entry: UsbFileEntry,
        newName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val sanitized = newName.trim().replace("/", "_")
        if (sanitized.isEmpty() || entry.path.isBlank() || entry.path == "/") return@withContext false

        val safUri = drive.safTreeUri
            ?: throw IOException("Renaming files requires folder access. Tap 'Mount Folder Access (SAF)' and select your USB drive.")

        val docUri = entry.documentUri ?: run {
            val docId = entry.documentId ?: resolveSafDocumentId(safUri, entry.path)
                ?: throw IOException("Item '${entry.name}' was not found on USB drive.")
            DocumentsContract.buildDocumentUriUsingTree(safUri, docId)
        }

        DocumentsContract.renameDocument(context.contentResolver, docUri, sanitized)
            ?: throw IOException("Storage provider refused to rename '${entry.name}' to '$sanitized'.")
        true
    }

    // =========================================================================
    // Block Transport Resolution
    // =========================================================================

    @Throws(IOException::class)
    private fun openBlockTransport(drive: UsbDriveDevice): BlockDeviceTransport {
        blockTransportFactory?.invoke(drive)?.let { return it }

        if (!drive.supportsRawBlockIo) {
            throw IOException(
                "Raw block operations require a direct USB-C OTG hardware connection. " +
                    "Connect your USB drive via OTG and grant USB Host permission on the Drives tab."
            )
        }

        val manager = usbManager
            ?: throw IOException("Android USB Host service is unavailable on this device.")
        val devName = drive.usbDeviceName
            ?: throw IOException("USB hardware device path is missing.")
        val usbDevice = manager.deviceList[devName]
            ?: throw IOException("USB drive '${drive.name}' is no longer connected to the OTG port.")

        if (!manager.hasPermission(usbDevice)) {
            throw IOException("USB Host permission not granted for ${drive.name}. Tap 'Grant USB Access' on the Drives tab.")
        }

        return UsbScsiBlockTransport.open(manager, usbDevice)
    }

    // =========================================================================
    // SAF Helpers (DocumentsContract & Android 11-Safe Stream Modes)
    // =========================================================================

    /**
     * On Android 11 (API 30) OEM ExternalStorageProvider implementations (e.g., OnePlus OxygenOS 11),
     * openOutputStream(uri, "wt") can throw FileNotFoundException or UnsupportedOperationException.
     * Trying "rwt" -> "wt" -> "w" ensures reliable truncation and writes across Android 10–15.
     */
    @Throws(IOException::class)
    private fun openSafOutputStream(documentUri: Uri): OutputStream {
        val modes = listOf("rwt", "wt", "w")
        var lastError: Throwable? = null
        for (mode in modes) {
            try {
                val stream = context.contentResolver.openOutputStream(documentUri, mode)
                if (stream != null) return stream
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IOException(
            "Unable to open writable stream for USB file (${lastError?.message ?: "permission denied"}).",
            lastError
        )
    }

    /**
     * Prevents Android ExternalStorageProvider from silently appending ".txt" to custom filenames
     * (like "config.cfg" or "notes.md") during DocumentsContract.createDocument().
     */
    private fun safCreateMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "text/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun readBytesFromContentUri(uri: Uri, maxBytes: Int): ByteArray {
        val out = ByteArray(maxBytes)
        var totalRead = 0
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (totalRead < maxBytes) {
                val r = input.read(out, totalRead, maxBytes - totalRead)
                if (r <= 0) break
                totalRead += r
            }
        } ?: throw IOException("Unable to open file stream from USB volume.")
        return if (totalRead == maxBytes) out else out.copyOf(totalRead)
    }

    private fun queryUriSize(uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx)
                }
            }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }.getOrDefault(0L).coerceAtLeast(0L)
    }

    private data class SafVolumeStats(
        val title: String?,
        val totalBytes: Long,
        val usedBytes: Long
    )

    private fun queryDocuments(uri: Uri, projection: Array<String>): android.database.Cursor? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.contentResolver.query(uri, projection, Bundle.EMPTY, null)
        } else {
            context.contentResolver.query(uri, projection, null, null, null)
        }
    }

    private fun isTreeUriAccessible(treeUri: Uri): Boolean {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            queryDocuments(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun queryDocumentDisplayName(treeUri: Uri, docId: String): String? {
        return runCatching {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            queryDocuments(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            )?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun querySafVolumeStats(treeUri: Uri, volumeKey: String): SafVolumeStats {
        var title: String? = null
        var totalBytes = 0L
        var availableBytes = -1L

        val authority = treeUri.authority
        if (!authority.isNullOrBlank()) {
            runCatching {
                val rootsUri = DocumentsContract.buildRootsUri(authority)
                val projection = arrayOf(
                    DocumentsContract.Root.COLUMN_ROOT_ID,
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Root.COLUMN_TITLE,
                    DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
                )
                queryDocuments(rootsUri, projection)?.use { cursor ->
                    val rootIdIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                    val docIdIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                    val titleIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                    val capIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_CAPACITY_BYTES)
                    val availIdx = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

                    while (cursor.moveToNext()) {
                        val rId = if (rootIdIdx >= 0) cursor.getString(rootIdIdx) else null
                        val dId = if (docIdIdx >= 0) cursor.getString(docIdIdx) else null
                        if (rId.equals(volumeKey, ignoreCase = true) ||
                            dId.equals(treeDocId, ignoreCase = true)
                        ) {
                            if (titleIdx >= 0) title = cursor.getString(titleIdx)
                            if (capIdx >= 0 && !cursor.isNull(capIdx)) totalBytes = cursor.getLong(capIdx)
                            if (availIdx >= 0 && !cursor.isNull(availIdx)) availableBytes = cursor.getLong(availIdx)
                            break
                        }
                    }
                }
            }
        }

        // Fallback to StatFs on /storage/<volumeKey> if mounted in Linux VFS
        if (totalBytes <= 0L && volumeKey.isNotBlank() && volumeKey != "primary") {
            val storageMount = File("/storage/$volumeKey")
            if (storageMount.exists() && storageMount.canRead()) {
                runCatching {
                    val stat = StatFs(storageMount.absolutePath)
                    totalBytes = stat.totalBytes
                    availableBytes = stat.availableBytes
                }
            }
        }

        val usedBytes = if (totalBytes > 0L && availableBytes >= 0L) {
            (totalBytes - availableBytes).coerceAtLeast(0L)
        } else {
            0L
        }

        return SafVolumeStats(
            title = title,
            totalBytes = totalBytes.coerceAtLeast(0L),
            usedBytes = usedBytes
        )
    }

    private fun findDirectChildDocumentId(treeUri: Uri, parentDocId: String, displayName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        return runCatching {
            queryDocuments(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (name == displayName && idIdx >= 0) {
                        return@use cursor.getString(idIdx)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun resolveSafDocumentId(treeUri: Uri, relativePath: String): String? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val segments = relativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return rootDocId

        var currentId = rootDocId
        for (segment in segments) {
            currentId = findDirectChildDocumentId(treeUri, currentId, segment) ?: return null
        }
        return currentId
    }

    @Throws(IOException::class)
    private fun listSafDirectory(
        treeUri: Uri,
        relativePath: String,
        explicitParentDocId: String?
    ): List<UsbFileEntry> {
        val parentDocId = explicitParentDocId ?: resolveSafDocumentId(treeUri, relativePath)
            ?: throw IOException("Unable to open directory '$relativePath' on USB volume. Remount via SAF if unplugged.")

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val entries = mutableListOf<UsbFileEntry>()
        val cursor = queryDocuments(childrenUri, projection)
            ?: throw IOException("Android Storage Provider returned no cursor for '$relativePath'. Ensure the USB drive is still connected.")

        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            val cleanParent = relativePath.trimEnd('/').let { if (it.isEmpty()) "" else it }
            while (c.moveToNext()) {
                val docId = if (idIdx >= 0) c.getString(idIdx) else continue
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: continue else continue
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "application/octet-stream" else "application/octet-stream"
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                // SAF COLUMN_SIZE for directories is OS metadata (0 or 4096 bytes), NOT child count!
                val size = if (isDir) -1L else if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val lastMod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                val entryPath = "$cleanParent/$name"
                entries.add(
                    UsbFileEntry(
                        name = name,
                        path = entryPath,
                        isDirectory = isDir,
                        sizeBytes = size,
                        lastModified = lastMod,
                        mimeType = mime,
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        documentId = docId,
                        canWrite = true
                    )
                )
            }
        }
        return entries.sortedWith(compareBy<UsbFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    private fun clearSafTreeChildren(
        treeUri: Uri,
        onItemProgress: (Float, String) -> Unit
    ): Int {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return 0
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val children = mutableListOf<Pair<String, String>>()
        queryDocuments(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (idIdx >= 0) {
                    val id = cursor.getString(idIdx)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: id else id
                    children.add(id to name)
                }
            }
        }

        var deleted = 0
        children.forEachIndexed { idx, (childId, name) ->
            onItemProgress((idx + 1).toFloat() / children.size.coerceAtLeast(1).toFloat(), name)
            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
            val ok = runCatching { DocumentsContract.deleteDocument(context.contentResolver, childUri) }
                .getOrDefault(false)
            if (ok) deleted++
        }
        return deleted
    }

    /**
     * Deletes any stale local loopback image/mount directories left over from earlier builds.
     */
    private fun purgeLegacyLoopbackArtifacts() {
        runCatching { if (disksBaseDir.exists()) disksBaseDir.deleteRecursively() }
        runCatching { if (mountsBaseDir.exists()) mountsBaseDir.deleteRecursively() }
    }

    // =========================================================================
    // USB Hardware Descriptor Helpers
    // =========================================================================

    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        }
        return false
    }

    private fun detectUsbProtocolName(device: UsbDevice): String {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return when (intf.interfaceProtocol) {
                    0x50 -> "BOT / Bulk-Only (0x50)"
                    0x62 -> "UASP / UAS (0x62)"
                    else -> String.format("MSC Proto 0x%02X", intf.interfaceProtocol)
                }
            }
        }
        return "USB Host Bus"
    }

    private fun getUsbMaxPowerMa(device: UsbDevice): Int {
        return runCatching {
            if (device.configurationCount > 0) {
                device.getConfiguration(0).maxPower * 2
            } else {
                500
            }
        }.getOrDefault(500).coerceAtLeast(100)
    }

    private fun lookupUsbVendorName(vendorId: Int): String = when (vendorId) {
        0x0781 -> "SanDisk Corp."
        0x0951 -> "Kingston Technology"
        0x04E8 -> "Samsung Electronics"
        0x05E3 -> "Genesys Logic (SD/MMC Bridge)"
        0x0BDA -> "Realtek Semiconductor (Card Reader)"
        0x1058 -> "Western Digital Technologies"
        0x0BC2 -> "Seagate RSS LLC"
        0x8564 -> "Transcend Information"
        0x152D -> "JMicron NVMe/USB Bridge"
        0x174C -> "ASMedia Technology Inc."
        else -> String.format("USB Vendor 0x%04X", vendorId and 0xFFFF)
    }

    private fun generateVolumeUuid(fs: FileSystemType): String {
        val raw = UUID.randomUUID().toString().uppercase()
        return if (fs == FileSystemType.EXT4) {
            raw.lowercase().take(13)
        } else {
            "${raw.substring(0, 4)}-${raw.substring(4, 8)}"
        }
    }
}

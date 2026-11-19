package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.UsbDiskEngine
import com.example.model.FileSystemType
import com.example.model.FormatOperationStatus
import com.example.model.IsoFlashStatus
import com.example.model.OperationLogEntry
import com.example.model.PartitionScheme
import com.example.model.UsbDriveDevice
import com.example.model.UsbFileEntry
import com.example.model.UsbPartition
import com.example.service.DiskOperationScope
import com.example.service.DiskOperationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class WorkbenchTab(val route: String, val title: String) {
    DRIVES("drives", "Drives"),
    FILES("files", "Files"),
    FORMAT("format", "Format"),
    ISO_WRITER("iso_writer", "ISO Writer"),
    PARTITIONS("partitions", "Partitions")
}

@Immutable
data class HexInspectionPayload(
    val title: String,
    val subtitle: String,
    val bytes: ByteArray,
    val baseOffset: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexInspectionPayload) return false
        return title == other.title &&
            subtitle == other.subtitle &&
            baseOffset == other.baseOffset &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + subtitle.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + baseOffset.hashCode()
        return result
    }
}

@Immutable
data class UsbStudioUiState(
    val currentTab: WorkbenchTab = WorkbenchTab.DRIVES,
    val darkTheme: Boolean = true,
    val drives: List<UsbDriveDevice> = emptyList(),
    val selectedDriveId: String = "",
    val isScanningBus: Boolean = false,
    // File Manager State
    val currentPath: String = "/",
    val safFolderStack: List<Pair<String, String>> = emptyList(),
    val fileEntries: List<UsbFileEntry> = emptyList(),
    val fileListError: String? = null,
    val fileSearchQuery: String = "",
    val selectedFileForEdit: UsbFileEntry? = null,
    val editingFileText: String = "",
    // Formatter State
    val formatLabel: String = "",
    val formatFileSystem: FileSystemType = FileSystemType.FAT32,
    val formatClusterSize: Int = 16384,
    val formatScheme: PartitionScheme = PartitionScheme.MBR,
    val formatQuick: Boolean = true,
    val formatWipeFullDrive: Boolean = true,
    val formatTargetPartitionId: String? = null,
    val formatStatus: FormatOperationStatus = FormatOperationStatus(),
    // ISO Writer State
    val customIsoUri: Uri? = null,
    val customIsoName: String? = null,
    val customIsoSize: Long = 0L,
    val verifyIsoSha256: Boolean = true,
    val isoFlashStatus: IsoFlashStatus = IsoFlashStatus(),
    // Partition Manager State
    val stagedScheme: PartitionScheme = PartitionScheme.GPT,
    val stagedPartitions: List<UsbPartition> = emptyList(),
    val hasUncommittedPartitionChanges: Boolean = false,
    val isCommittingPartitions: Boolean = false,
    // Raw Sector / File Hex Inspector
    val activeHexInspection: HexInspectionPayload? = null,
    // Operation Logs
    val logs: List<OperationLogEntry> = emptyList(),
    val bannerMessage: String? = null
) {
    val selectedDrive: UsbDriveDevice?
        get() = drives.find { it.id == selectedDriveId } ?: drives.firstOrNull()

    /** True while a format, image flash or partition commit is writing to a drive. */
    val isDiskOperationRunning: Boolean
        get() = formatStatus.isRunning || isoFlashStatus.isRunning || isCommittingPartitions

    val currentSafDocumentId: String?
        get() = safFolderStack.lastOrNull()?.second

    val filteredFiles: List<UsbFileEntry>
        get() = if (fileSearchQuery.isBlank()) {
            fileEntries
        } else {
            fileEntries.filter { it.name.contains(fileSearchQuery.trim(), ignoreCase = true) }
        }
}

class UsbStudioViewModel(
    application: Application,
    private val engine: UsbDiskEngine
) : AndroidViewModel(application) {

    private companion object {
        /** Smallest partition that can hold a valid FAT32 volume (65525 clusters of 512 B plus FATs). */
        const val MIN_PARTITION_BYTES = 34L * 1024 * 1024
    }

    constructor(application: Application) : this(
        application,
        UsbDiskEngine(application.applicationContext)
    )

    private val _uiState = MutableStateFlow(UsbStudioUiState())
    val uiState: StateFlow<UsbStudioUiState> = _uiState.asStateFlow()

    fun selectTab(tab: WorkbenchTab) {
        if (_uiState.value.currentTab == tab) return
        _uiState.update { it.copy(currentTab = tab, bannerMessage = null) }
        if (tab == WorkbenchTab.FILES) {
            refreshCurrentDirectory()
        } else if (tab == WorkbenchTab.PARTITIONS) {
            syncStagedPartitionsFromSelectedDrive()
        }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(darkTheme = !it.darkTheme) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun closeHexInspector() {
        _uiState.update { it.copy(activeHexInspection = null) }
    }

    fun refreshUsbBus() {
        if (_uiState.value.isDiskOperationRunning) {
            appendLog("OTG-SCAN", "Rescan skipped: a drive operation is still running.")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningBus = true) }
            val currentDrives = _uiState.value.drives
            val discovered = engine.discoverConnectedDrives(currentDrives)
            val currentSelectedId = _uiState.value.selectedDriveId
            val chosenDrive = discovered.find { it.id == currentSelectedId } ?: discovered.firstOrNull()
            val driveChanged = chosenDrive?.id != currentSelectedId

            _uiState.update { state ->
                state.copy(
                    isScanningBus = false,
                    drives = discovered,
                    selectedDriveId = chosenDrive?.id.orEmpty(),
                    currentPath = if (chosenDrive == null || driveChanged) "/" else state.currentPath,
                    safFolderStack = if (chosenDrive == null || driveChanged) emptyList() else state.safFolderStack,
                    fileEntries = if (chosenDrive == null) emptyList() else state.fileEntries,
                    fileListError = if (chosenDrive == null) null else state.fileListError,
                    formatLabel = chosenDrive?.partitions?.firstOrNull { p -> p.fileSystem != FileSystemType.UNALLOCATED }?.label
                        ?: "",
                    formatScheme = chosenDrive?.partitionScheme ?: PartitionScheme.MBR,
                    stagedScheme = chosenDrive?.partitionScheme ?: PartitionScheme.GPT,
                    stagedPartitions = chosenDrive?.partitions.orEmpty(),
                    hasUncommittedPartitionChanges = false
                )
            }
            appendLog(
                "OTG-SCAN",
                "Enumerated ${discovered.size} storage device(s) on USB/OTG bus. Active: ${chosenDrive?.name ?: "None"}"
            )
            refreshCurrentDirectory()
        }
    }

    fun selectDrive(driveId: String) {
        val drive = _uiState.value.drives.find { it.id == driveId } ?: return
        _uiState.update {
            it.copy(
                selectedDriveId = drive.id,
                currentPath = "/",
                safFolderStack = emptyList(),
                fileListError = null,
                formatLabel = drive.partitions.firstOrNull { p -> p.fileSystem != FileSystemType.UNALLOCATED }?.label
                    ?: "USB_VOLUME",
                formatScheme = drive.partitionScheme,
                formatTargetPartitionId = drive.partitions.firstOrNull()?.id,
                stagedScheme = drive.partitionScheme,
                stagedPartitions = drive.partitions,
                hasUncommittedPartitionChanges = false,
                bannerMessage = "Switched active target to ${drive.name} (${drive.vidPidHex})"
            )
        }
        appendLog("TARGET", "Selected active block device ${drive.name} [${drive.vidPidHex}]")
        refreshCurrentDirectory()
    }

    fun requestHardwareUsbPermission(drive: UsbDriveDevice) {
        val devName = drive.usbDeviceName ?: return
        val (sent, alreadyGranted) = engine.requestUsbPermission(devName)
        if (alreadyGranted) {
            appendLog("USB-PERM", "USB Host permission already granted for $devName")
            refreshUsbBus()
        } else if (sent) {
            appendLog("USB-PERM", "Requested Android USB Host permission for $devName...")
            _uiState.update { it.copy(bannerMessage = "Approve the Android USB access prompt to unlock direct hardware I/O") }
        } else {
            appendLog("USB-PERM", "USB device $devName is no longer attached.", isError = true)
            _uiState.update { it.copy(bannerMessage = "USB device is no longer attached. Rescan the bus.") }
        }
    }

    fun mountSafVolume(treeUri: Uri) {
        viewModelScope.launch {
            val safDrive = try {
                engine.createSafMountedDrive(treeUri)
            } catch (e: Exception) {
                val msg = e.message ?: "That folder cannot be mounted."
                appendLog("SAF-MOUNT", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
                return@launch
            }
            val currentSelected = _uiState.value.selectedDrive

            // If a physical USB OTG hardware drive is currently active without SAF folder access,
            // link this SAF tree to it, but only when the volume's size can belong to that drive.
            if (currentSelected != null && currentSelected.supportsRawBlockIo && currentSelected.safTreeUri == null &&
                engine.safTreeFitsDrive(treeUri, currentSelected)
            ) {
                val mergedHw = currentSelected.copy(
                    safTreeUri = treeUri,
                    totalBytes = if (currentSelected.totalBytes > 0L) currentSelected.totalBytes else safDrive.totalBytes,
                    protocolName = if (currentSelected.protocolName.contains("SAF")) {
                        currentSelected.protocolName
                    } else {
                        "${currentSelected.protocolName} + SAF"
                    }
                )
                _uiState.update { state ->
                    val updatedList = state.drives
                        .filterNot { it.safTreeUri == treeUri && it.id != mergedHw.id }
                        .map { if (it.id == mergedHw.id) mergedHw else it }
                    state.copy(
                        drives = updatedList,
                        selectedDriveId = mergedHw.id,
                        currentPath = "/",
                        safFolderStack = emptyList(),
                        fileListError = null,
                        bannerMessage = "Linked folder read/write access to ${mergedHw.name}"
                    )
                }
                appendLog("SAF-LINK", "Linked SAF tree (${treeUri.lastPathSegment}) to hardware drive ${mergedHw.name}")
                refreshCurrentDirectory()
                return@launch
            }

            _uiState.update { state ->
                val filtered = state.drives.filterNot { it.safTreeUri == treeUri }
                val updated = listOf(safDrive) + filtered
                state.copy(
                    drives = updated,
                    selectedDriveId = safDrive.id,
                    currentPath = "/",
                    safFolderStack = emptyList(),
                    fileListError = null,
                    formatLabel = safDrive.partitions.firstOrNull()?.label.orEmpty(),
                    formatScheme = safDrive.partitionScheme,
                    stagedScheme = safDrive.partitionScheme,
                    stagedPartitions = safDrive.partitions,
                    hasUncommittedPartitionChanges = false,
                    bannerMessage = "Mounted external volume: ${safDrive.name}"
                )
            }
            appendLog("SAF-MOUNT", "Mounted DocumentTree volume ${safDrive.name} (${treeUri.lastPathSegment})")
            refreshCurrentDirectory()
        }
    }

    // =========================================================================
    // Raw Disk Sector & File Hex Inspection
    // =========================================================================

    fun inspectDriveSector(lbaIndex: Long, title: String) {
        val drive = _uiState.value.selectedDrive ?: return
        viewModelScope.launch {
            try {
                val bytes = engine.readRawSector(drive, lbaIndex, sectorCount = 1)
                _uiState.update {
                    it.copy(
                        activeHexInspection = HexInspectionPayload(
                            title = title,
                            subtitle = "${drive.name} • LBA $lbaIndex (Byte Offset 0x${String.format("%08X", lbaIndex * 512)})",
                            bytes = bytes,
                            baseOffset = lbaIndex * 512
                        )
                    )
                }
                appendLog("HEX-READ", "Read 512B sector at LBA $lbaIndex from ${drive.name}")
            } catch (e: Exception) {
                val msg = e.message ?: "Unable to read raw sector LBA $lbaIndex."
                appendLog("HEX-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    fun inspectFileHex(entry: UsbFileEntry) {
        val drive = _uiState.value.selectedDrive ?: return
        viewModelScope.launch {
            try {
                val bytes = engine.readFileBytes(drive, entry, maxBytes = 512)
                _uiState.update {
                    it.copy(
                        activeHexInspection = HexInspectionPayload(
                            title = "File Hex: ${entry.name}",
                            subtitle = "${drive.name} • ${entry.path} (${entry.sizeBytes.coerceAtLeast(0L)} bytes)",
                            bytes = bytes,
                            baseOffset = 0L
                        )
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unable to read hex bytes for ${entry.name}."
                appendLog("HEX-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    // =========================================================================
    // File Manager Actions
    // =========================================================================

    fun refreshCurrentDirectory() {
        val drive = _uiState.value.selectedDrive
        if (drive == null) {
            _uiState.update { it.copy(fileEntries = emptyList(), fileListError = null) }
            return
        }
        if (drive.safTreeUri == null && _uiState.value.isDiskOperationRunning) return
        val path = _uiState.value.currentPath
        val docId = _uiState.value.currentSafDocumentId
        viewModelScope.launch {
            try {
                val entries = engine.listDirectory(drive, path, safDocumentId = docId)
                _uiState.update { it.copy(fileEntries = entries, fileListError = null) }
            } catch (e: Exception) {
                val msg = e.message ?: "Unable to list files in $path."
                appendLog("FS-LIST", msg, isError = true)
                _uiState.update { it.copy(fileEntries = emptyList(), fileListError = msg) }
            }
        }
    }

    fun openDirectory(entry: UsbFileEntry) {
        if (!entry.isDirectory) return
        _uiState.update { state ->
            val nextStack = if (entry.documentId != null) {
                state.safFolderStack + (entry.name to entry.documentId)
            } else {
                emptyList()
            }
            state.copy(
                currentPath = entry.path,
                safFolderStack = nextStack,
                selectedFileForEdit = null,
                fileListError = null
            )
        }
        refreshCurrentDirectory()
    }

    fun navigateToPath(newPath: String) {
        val normalized = if (newPath.isBlank()) "/" else newPath
        val targetSegments = normalized.trim('/').split('/').filter { it.isNotEmpty() }
        _uiState.update { state ->
            val trimmedStack = if (targetSegments.isEmpty()) {
                emptyList()
            } else if (state.safFolderStack.size >= targetSegments.size) {
                val candidate = state.safFolderStack.take(targetSegments.size)
                if (candidate.map { it.first } == targetSegments) candidate else emptyList()
            } else {
                emptyList()
            }
            state.copy(
                currentPath = normalized,
                safFolderStack = trimmedStack,
                selectedFileForEdit = null,
                fileListError = null
            )
        }
        refreshCurrentDirectory()
    }

    fun navigateUpDirectory() {
        val cur = _uiState.value.currentPath
        if (cur == "/" || cur.isBlank()) return
        val parent = cur.trimEnd('/').substringBeforeLast('/', "")
        _uiState.update { state ->
            state.copy(
                currentPath = if (parent.isEmpty()) "/" else parent,
                safFolderStack = if (state.safFolderStack.isNotEmpty()) state.safFolderStack.dropLast(1) else emptyList(),
                selectedFileForEdit = null,
                fileListError = null
            )
        }
        refreshCurrentDirectory()
    }

    fun updateFileSearchQuery(query: String) {
        _uiState.update { it.copy(fileSearchQuery = query) }
    }

    fun createNewFolder(folderName: String) {
        val drive = _uiState.value.selectedDrive ?: return
        val path = _uiState.value.currentPath
        val parentDocId = _uiState.value.currentSafDocumentId
        viewModelScope.launch {
            try {
                val created = engine.createFolder(drive, path, folderName, parentDocumentId = parentDocId)
                appendLog("FS-MKDIR", "Created directory '${created.name}' in $path on ${drive.name}")
                _uiState.update { it.copy(bannerMessage = "Created folder '${created.name}'") }
                refreshCurrentDirectory()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to create folder '$folderName'."
                appendLog("FS-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    fun createOrSaveTextFile(fileName: String, content: String, existingEntry: UsbFileEntry? = null) {
        val drive = _uiState.value.selectedDrive ?: return
        val path = _uiState.value.currentPath
        val parentDocId = _uiState.value.currentSafDocumentId
        viewModelScope.launch {
            try {
                engine.writeTextFile(
                    drive = drive,
                    parentRelativePath = path,
                    fileName = fileName,
                    content = content,
                    parentDocumentId = parentDocId,
                    existingDocumentUri = existingEntry?.documentUri
                )
                appendLog("FS-WRITE", "Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to '$fileName' on ${drive.name}")
                _uiState.update { it.copy(selectedFileForEdit = null, bannerMessage = "Saved $fileName to ${drive.name}") }
                refreshCurrentDirectory()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to save '$fileName'."
                appendLog("FS-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    fun openFileForEditing(entry: UsbFileEntry) {
        val drive = _uiState.value.selectedDrive ?: return
        viewModelScope.launch {
            try {
                val bytes = engine.readFileBytes(drive, entry, maxBytes = 65536)
                val text = bytes.toString(Charsets.UTF_8)
                _uiState.update {
                    it.copy(
                        selectedFileForEdit = entry,
                        editingFileText = text
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unable to read '${entry.name}'."
                appendLog("FS-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    fun closeFileEditor() {
        _uiState.update { it.copy(selectedFileForEdit = null, editingFileText = "") }
    }

    fun importExternalFileToCurrentDir(uri: Uri, suggestedName: String) {
        val drive = _uiState.value.selectedDrive ?: return
        val path = _uiState.value.currentPath
        val parentDocId = _uiState.value.currentSafDocumentId
        viewModelScope.launch {
            try {
                val savedName = engine.importExternalUriToDrive(
                    drive = drive,
                    parentRelativePath = path,
                    sourceUri = uri,
                    fileName = suggestedName,
                    parentDocumentId = parentDocId
                )
                appendLog("FS-IMPORT", "Copied '$savedName' into $path on ${drive.name}")
                _uiState.update { it.copy(bannerMessage = "Imported '$savedName' to ${drive.name}") }
                refreshCurrentDirectory()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to import '$suggestedName'."
                appendLog("FS-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    fun deleteFileEntry(entry: UsbFileEntry) {
        val drive = _uiState.value.selectedDrive ?: return
        viewModelScope.launch {
            try {
                val ok = engine.deleteEntry(drive, entry)
                if (ok) {
                    appendLog("FS-RM", "Deleted '${entry.path}' from ${drive.name}")
                    _uiState.update { it.copy(bannerMessage = "Deleted '${entry.name}'") }
                    refreshCurrentDirectory()
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to delete '${entry.name}'."
                appendLog("FS-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    fun renameFileEntry(entry: UsbFileEntry, newName: String) {
        val drive = _uiState.value.selectedDrive ?: return
        viewModelScope.launch {
            try {
                val ok = engine.renameEntry(drive, entry, newName)
                if (ok) {
                    appendLog("FS-MV", "Renamed '${entry.name}' -> '$newName' on ${drive.name}")
                    _uiState.update { it.copy(bannerMessage = "Renamed '${entry.name}' to '$newName'") }
                    refreshCurrentDirectory()
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to rename '${entry.name}'."
                appendLog("FS-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg) }
            }
        }
    }

    // =========================================================================
    // USB Formatter Actions
    // =========================================================================

    fun updateFormatConfig(
        label: String? = null,
        fileSystem: FileSystemType? = null,
        clusterSize: Int? = null,
        scheme: PartitionScheme? = null,
        quick: Boolean? = null,
        wipeFullDrive: Boolean? = null,
        targetPartitionId: String? = null
    ) {
        _uiState.update { state ->
            val nextFs = fileSystem ?: state.formatFileSystem
            val validCluster = when {
                clusterSize != null -> clusterSize
                state.formatClusterSize in nextFs.clusterSizes -> state.formatClusterSize
                else -> nextFs.clusterSizes.first()
            }
            state.copy(
                formatLabel = label ?: state.formatLabel,
                formatFileSystem = nextFs,
                formatClusterSize = validCluster,
                formatScheme = scheme ?: state.formatScheme,
                formatQuick = quick ?: state.formatQuick,
                formatWipeFullDrive = wipeFullDrive ?: state.formatWipeFullDrive,
                formatTargetPartitionId = targetPartitionId ?: state.formatTargetPartitionId
            )
        }
    }

    fun executeFormatOperation() {
        val state = _uiState.value
        val drive = state.selectedDrive ?: return
        if (rejectIfBusy(state)) return

        // Mark running before the coroutine starts so a second tap cannot start another write.
        _uiState.update {
            it.copy(formatStatus = FormatOperationStatus(isRunning = true, stepDescription = "Preparing USB device...", progress = 0.02f))
        }
        launchDiskOperation("Formatting ${drive.name}") {
            appendLog(
                "MKFS",
                "Starting ${state.formatFileSystem.label} format on ${drive.name} (Label=${state.formatLabel}, Cluster=${state.formatClusterSize}B)"
            )
            _uiState.update {
                it.copy(
                    formatStatus = FormatOperationStatus(
                        isRunning = true,
                        stepDescription = "Preparing USB device...",
                        progress = 0.05f,
                        errorMessage = null
                    )
                )
            }

            try {
                val (updatedDrive, newUuid) = engine.formatDriveOrPartition(
                    drive = drive,
                    targetPartitionId = state.formatTargetPartitionId,
                    newLabel = state.formatLabel,
                    fileSystem = state.formatFileSystem,
                    clusterSize = state.formatClusterSize,
                    partitionScheme = state.formatScheme,
                    quickFormat = state.formatQuick,
                    wipeFullDrive = state.formatWipeFullDrive
                ) { progress, stepText ->
                    _uiState.update { s ->
                        s.copy(
                            formatStatus = s.formatStatus.copy(
                                isRunning = progress < 1.0f,
                                progress = progress,
                                stepDescription = stepText
                            )
                        )
                    }
                }

                val isSafWipe = newUuid == "SAF-WIPED"
                val completeMsg = if (isSafWipe) {
                    "Erased all files and folders on ${updatedDrive.name}"
                } else {
                    "Formatted ${updatedDrive.name} as ${state.formatFileSystem.label} ($newUuid)"
                }

                _uiState.update { s ->
                    val updatedList = s.drives.map { if (it.id == updatedDrive.id) updatedDrive else it }
                    s.copy(
                        drives = updatedList,
                        currentPath = "/",
                        safFolderStack = emptyList(),
                        stagedScheme = updatedDrive.partitionScheme,
                        stagedPartitions = updatedDrive.partitions,
                        formatStatus = FormatOperationStatus(
                            isRunning = false,
                            stepDescription = completeMsg,
                            progress = 1.0f,
                            completedVolumeUuid = newUuid,
                            errorMessage = null
                        ),
                        bannerMessage = completeMsg
                    )
                }
                appendLog("MKFS-OK", completeMsg)
                refreshCurrentDirectory()
            } catch (e: Exception) {
                val msg = e.message ?: "Format operation failed on ${drive.name}."
                _uiState.update { s ->
                    s.copy(
                        formatStatus = FormatOperationStatus(
                            isRunning = false,
                            stepDescription = msg,
                            progress = 0f,
                            completedVolumeUuid = null,
                            errorMessage = msg
                        ),
                        bannerMessage = msg
                    )
                }
                appendLog("MKFS-ERR", msg, isError = true)
            }
        }
    }

    // =========================================================================
    // ISO / IMG Writer Actions
    // =========================================================================

    fun selectCustomIsoUri(uri: Uri, displayName: String, sizeBytes: Long) {
        _uiState.update {
            it.copy(
                customIsoUri = uri,
                customIsoName = displayName,
                customIsoSize = sizeBytes,
                isoFlashStatus = IsoFlashStatus()
            )
        }
        appendLog("ISO-LOAD", "Selected external bootable image: $displayName")
    }

    fun toggleVerifyIsoSha256(enabled: Boolean) {
        _uiState.update { it.copy(verifyIsoSha256 = enabled) }
    }

    fun executeIsoFlash() {
        val state = _uiState.value
        val drive = state.selectedDrive ?: return
        val sourceUri = state.customIsoUri ?: return
        val sourceTitle = state.customIsoName?.takeIf { it.isNotBlank() } ?: "Image.iso"
        if (rejectIfBusy(state)) return

        _uiState.update {
            it.copy(isoFlashStatus = IsoFlashStatus(isRunning = true, stage = "Preparing...", progress = 0.01f))
        }
        launchDiskOperation("Writing $sourceTitle to ${drive.name}") {
            appendLog("ISO-DD", "Flashing '$sourceTitle' to ${drive.name} (${drive.vidPidHex})...")

            _uiState.update {
                it.copy(
                    isoFlashStatus = IsoFlashStatus(
                        isRunning = true,
                        stage = "Opening USB SCSI block channel...",
                        progress = 0.02f,
                        errorMessage = null
                    )
                )
            }

            try {
                val (updatedDrive, sha256, verified) = engine.flashIsoImageToDrive(
                    drive = drive,
                    externalUri = sourceUri,
                    externalName = sourceTitle,
                    verifyAfterWrite = state.verifyIsoSha256
                ) { stage, progress, bytesDone, totalBytes, speedMbps ->
                    _uiState.update { s ->
                        s.copy(
                            isoFlashStatus = s.isoFlashStatus.copy(
                                isRunning = true,
                                stage = stage,
                                progress = progress,
                                bytesProcessed = bytesDone,
                                totalBytes = totalBytes,
                                speedMbps = speedMbps
                            )
                        )
                    }
                }

                val verifyFailed = state.verifyIsoSha256 && !verified
                val verifyError = "Verification failed: the data read back from ${drive.name} does not match " +
                    "$sourceTitle. Do not use this drive; try another drive or port and write again."
                _uiState.update { s ->
                    val updatedList = s.drives.map { if (it.id == updatedDrive.id) updatedDrive else it }
                    s.copy(
                        drives = updatedList,
                        currentPath = "/",
                        safFolderStack = emptyList(),
                        stagedScheme = updatedDrive.partitionScheme,
                        stagedPartitions = updatedDrive.partitions,
                        isoFlashStatus = s.isoFlashStatus.copy(
                            isRunning = false,
                            stage = when {
                                verifyFailed -> "Verification Failed"
                                state.verifyIsoSha256 -> "Flash & SHA-256 Read-Back Verified"
                                else -> "Flash Completed (not verified)"
                            },
                            progress = 1.0f,
                            sha256Hex = sha256,
                            verifiedSuccess = if (state.verifyIsoSha256) verified else null,
                            errorMessage = if (verifyFailed) verifyError else null
                        ),
                        bannerMessage = if (verifyFailed) verifyError else "Flashed $sourceTitle to ${drive.name}"
                    )
                }
                appendLog(
                    if (verifyFailed) "ISO-ERR" else "ISO-OK",
                    "Flashed $sourceTitle • SHA256=${sha256.take(16)}... Verified=$verified",
                    isError = verifyFailed
                )
                refreshCurrentDirectory()
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to flash selected image."
                _uiState.update { s ->
                    s.copy(
                        isoFlashStatus = s.isoFlashStatus.copy(
                            isRunning = false,
                            stage = "Flash Failed",
                            errorMessage = msg
                        ),
                        bannerMessage = msg
                    )
                }
                appendLog("ISO-ERR", msg, isError = true)
            }
        }
    }

    // =========================================================================
    // Partition Management Tool Actions
    // =========================================================================

    private fun syncStagedPartitionsFromSelectedDrive() {
        val drive = _uiState.value.selectedDrive
        if (drive == null) {
            _uiState.update {
                it.copy(
                    stagedPartitions = emptyList(),
                    hasUncommittedPartitionChanges = false
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                stagedScheme = drive.partitionScheme,
                stagedPartitions = drive.partitions,
                hasUncommittedPartitionChanges = false
            )
        }
    }

    fun setStagedPartitionScheme(scheme: PartitionScheme) {
        _uiState.update {
            it.copy(
                stagedScheme = scheme,
                hasUncommittedPartitionChanges = true
            )
        }
    }

    fun deleteStagedPartition(partitionId: String) {
        val current = _uiState.value.stagedPartitions
        val target = current.find { it.id == partitionId } ?: return
        if (target.fileSystem == FileSystemType.UNALLOCATED) return

        val replaced = current.map { part ->
            if (part.id == partitionId) {
                part.copy(
                    label = "Unallocated",
                    fileSystem = FileSystemType.UNALLOCATED,
                    usedBytes = 0L,
                    isBootable = false,
                    flags = emptyList(),
                    typeGuidOrHex = "00000000-0000-0000-0000-000000000000",
                    uuid = "----"
                )
            } else {
                part
            }
        }
        val merged = mergeAdjacentUnallocated(replaced)
        _uiState.update {
            it.copy(
                stagedPartitions = merged,
                hasUncommittedPartitionChanges = true
            )
        }
    }

    fun createPartitionInUnallocated(
        unallocatedId: String,
        label: String,
        fileSystem: FileSystemType,
        sizeMb: Long,
        bootable: Boolean
    ) {
        val drive = _uiState.value.selectedDrive ?: return
        val blockSize = drive.logicalBlockSize
        val current = _uiState.value.stagedPartitions
        val result = mutableListOf<UsbPartition>()

        for (part in current) {
            if (part.id == unallocatedId && part.fileSystem == FileSystemType.UNALLOCATED) {
                // Size from the sector count, which is what is really free on disk.
                val availableBytes = part.sectorCount * blockSize.toLong()
                val targetBytes = (sizeMb * 1024L * 1024L).coerceAtLeast(MIN_PARTITION_BYTES)
                val requestedBytes = targetBytes.coerceAtMost(availableBytes)
                if (requestedBytes < MIN_PARTITION_BYTES) {
                    _uiState.update { it.copy(bannerMessage = "Not enough free space: a FAT32 partition needs at least 34 MB.") }
                    return
                }
                val requestedSectors = requestedBytes / blockSize
                val newPart = UsbPartition(
                    id = "part_${UUID.randomUUID().toString().take(6)}",
                    index = 0,
                    label = label.trim().ifEmpty { "NEW_VOL" }.uppercase().take(11),
                    fileSystem = fileSystem,
                    startLba = part.startLba,
                    sectorCount = requestedSectors,
                    sizeBytes = requestedBytes,
                    usedBytes = 0L,
                    isBootable = bootable,
                    flags = buildList {
                        if (bootable) add("BOOT")
                        add("PRIMARY")
                    },
                    typeGuidOrHex = if (_uiState.value.stagedScheme == PartitionScheme.GPT) {
                        "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
                    } else {
                        String.format("0x%02X", fileSystem.code)
                    },
                    uuid = UUID.randomUUID().toString().uppercase().take(8)
                )
                result.add(newPart)

                val remainingSectors = part.sectorCount - requestedSectors
                if (remainingSectors * blockSize >= MIN_PARTITION_BYTES) {
                    result.add(
                        part.copy(
                            id = "unalloc_${UUID.randomUUID().toString().take(5)}",
                            startLba = part.startLba + requestedSectors,
                            sectorCount = remainingSectors,
                            sizeBytes = remainingSectors * blockSize
                        )
                    )
                }
            } else {
                result.add(part)
            }
        }

        val reindexed = result.mapIndexed { idx, p -> p.copy(index = idx + 1) }
        _uiState.update {
            it.copy(
                stagedPartitions = reindexed,
                hasUncommittedPartitionChanges = true
            )
        }
    }

    fun togglePartitionBootFlag(partitionId: String) {
        val updated = _uiState.value.stagedPartitions.map { part ->
            if (part.id == partitionId && part.fileSystem != FileSystemType.UNALLOCATED) {
                val nextBoot = !part.isBootable
                val nextFlags = if (nextBoot) (part.flags + "BOOT").distinct() else part.flags - "BOOT"
                part.copy(isBootable = nextBoot, flags = nextFlags)
            } else {
                part
            }
        }
        _uiState.update {
            it.copy(
                stagedPartitions = updated,
                hasUncommittedPartitionChanges = true
            )
        }
    }

    fun resetStagedPartitionChanges() {
        syncStagedPartitionsFromSelectedDrive()
    }

    fun applyStagedPartitionTable() {
        val state = _uiState.value
        val drive = state.selectedDrive ?: return
        if (rejectIfBusy(state)) return
        _uiState.update { it.copy(isCommittingPartitions = true) }
        launchDiskOperation("Writing partition table to ${drive.name}") {
            try {
                val updatedDrive = engine.commitPartitionTable(
                    drive = drive,
                    newScheme = state.stagedScheme,
                    newPartitions = state.stagedPartitions
                )
                _uiState.update { s ->
                    val updatedList = s.drives.map { if (it.id == updatedDrive.id) updatedDrive else it }
                    s.copy(
                        drives = updatedList,
                        stagedScheme = updatedDrive.partitionScheme,
                        stagedPartitions = updatedDrive.partitions,
                        hasUncommittedPartitionChanges = false,
                        isCommittingPartitions = false,
                        bannerMessage = "Committed ${updatedDrive.partitionScheme.name} partition table (${updatedDrive.partitions.size} entries) to USB LBA 0"
                    )
                }
                appendLog(
                    "PART-COMMIT",
                    "Wrote ${updatedDrive.partitionScheme.name} table (${updatedDrive.partitions.size} slices) to ${drive.name}"
                )
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to commit partition table to ${drive.name}."
                appendLog("PART-ERR", msg, isError = true)
                _uiState.update { it.copy(bannerMessage = msg, isCommittingPartitions = false) }
            }
        }
    }

    private fun rejectIfBusy(state: UsbStudioUiState): Boolean {
        if (!state.isDiskOperationRunning) return false
        _uiState.update { it.copy(bannerMessage = "Another drive operation is still running. Wait for it to finish.") }
        return true
    }

    /**
     * Runs a drive write outside viewModelScope, inside a foreground service with a wake lock,
     * so closing the screen or turning it off cannot stop the write halfway.
     */
    private fun launchDiskOperation(title: String, block: suspend () -> Unit) {
        val app = getApplication<Application>()
        DiskOperationService.start(app, title)
        DiskOperationScope.scope.launch {
            try {
                block()
            } finally {
                DiskOperationService.stop(app)
            }
        }
    }

    private fun mergeAdjacentUnallocated(list: List<UsbPartition>): List<UsbPartition> {
        val merged = mutableListOf<UsbPartition>()
        for (part in list) {
            val prev = merged.lastOrNull()
            if (prev != null &&
                prev.fileSystem == FileSystemType.UNALLOCATED &&
                part.fileSystem == FileSystemType.UNALLOCATED
            ) {
                merged[merged.lastIndex] = prev.copy(
                    sectorCount = prev.sectorCount + part.sectorCount,
                    sizeBytes = prev.sizeBytes + part.sizeBytes
                )
            } else {
                merged.add(part)
            }
        }
        return merged.mapIndexed { idx, p -> p.copy(index = idx + 1) }
    }

    private fun appendLog(tag: String, message: String, isError: Boolean = false) {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = OperationLogEntry(timestamp = timeStr, tag = tag, message = message, isError = isError)
        _uiState.update { state ->
            state.copy(logs = (listOf(entry) + state.logs).take(60))
        }
    }
}

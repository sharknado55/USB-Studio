package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.UsbDriveDevice
import com.example.model.UsbFileEntry
import com.example.ui.components.ExpressiveHighlightPill
import com.example.ui.components.ExpressiveScallopEmblem
import com.example.ui.components.Md3Alert
import com.example.ui.components.Md3BadgeVariant
import com.example.ui.components.Md3Button
import com.example.ui.components.Md3ButtonVariant
import com.example.ui.components.Md3Card
import com.example.ui.components.Md3ConfirmDialog
import com.example.ui.components.Md3Input
import com.example.ui.components.Md3NoDriveEmptyState
import com.example.ui.theme.Coral400
import com.example.ui.theme.Lavender300
import com.example.ui.theme.OrchidPink
import com.example.ui.theme.Violet900
import com.example.util.formatBytes

@Composable
fun FileManagerScreen(
    drive: UsbDriveDevice?,
    currentPath: String,
    files: List<UsbFileEntry>,
    searchQuery: String,
    editingFile: UsbFileEntry?,
    editingText: String,
    onSearchQueryChange: (String) -> Unit,
    onNavigateToPath: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onSaveTextFile: (String, String, UsbFileEntry?) -> Unit,
    onOpenFileForEdit: (UsbFileEntry) -> Unit,
    onCloseFileEditor: () -> Unit,
    onImportExternalFileClick: () -> Unit,
    onInspectFileHex: (UsbFileEntry) -> Unit,
    onRenameEntry: (UsbFileEntry, String) -> Unit,
    onDeleteEntry: (UsbFileEntry) -> Unit,
    onMountSafVolume: () -> Unit,
    onScanUsbBus: () -> Unit,
    onBackToDrives: () -> Unit,
    onOpenDirectory: (UsbFileEntry) -> Unit = { onNavigateToPath(it.path) },
    fileListError: String? = null,
    isCurrentTab: Boolean = true,
    topContentPadding: Dp = 14.dp,
    bottomContentPadding: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isCurrentTab) {
        if (editingFile != null) {
            onCloseFileEditor()
        } else if (currentPath != "/" && currentPath.isNotBlank()) {
            onNavigateUp()
        } else {
            onBackToDrives()
        }
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<UsbFileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<UsbFileEntry?>(null) }

    if (drive == null) {
        Md3NoDriveEmptyState(
            screenTitle = "files",
            screenSubtitle = "Volume Browser",
            accentIcon = Icons.Filled.Folder,
            accentColor = Lavender300,
            onMountSafVolume = onMountSafVolume,
            onScanUsbBus = onScanUsbBus,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            testTag = "file_manager_empty_state",
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("file_manager_screen"),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topContentPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Expressive Header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "files",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = drive.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    ExpressiveScallopEmblem(
                        icon = Icons.Filled.Folder,
                        size = 66.dp,
                        useCloverInner = false,
                        outerColor = Lavender300,
                        innerColor = Violet900,
                        iconTint = Lavender300
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveHighlightPill(text = "${files.size} Items")
                    if (drive.totalBytes > 0L) {
                        ExpressiveHighlightPill(
                            text = "${formatBytes((drive.totalBytes - drive.usedBytes).coerceAtLeast(0L))} Free",
                            inverted = false
                        )
                    }
                }

                // Pill Breadcrumb Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPath != "/" && currentPath.isNotBlank()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Up directory",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { onNavigateUp() }
                                .testTag("file_nav_up_button")
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = "Root",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onNavigateToPath("/") }
                    )

                    val segments = currentPath.trim('/').split('/').filter { it.isNotEmpty() }
                    segments.forEachIndexed { index, seg ->
                        val accumulated = "/" + segments.take(index + 1).joinToString("/")
                        Text(
                            text = "  /  ",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = seg,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (index == segments.lastIndex) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontWeight = if (index == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { onNavigateToPath(accumulated) }
                        )
                    }
                }

                Md3Input(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = "Search files and folders...",
                    leadingIcon = Icons.Outlined.Search,
                    testTag = "file_search_input"
                )

                if (!drive.supportsFileWrites) {
                    Md3Alert(
                        title = "Mount Folder Access to Edit Files",
                        description = "Direct USB OTG mode reads FAT32 partitions. Mount this drive via Android folder access (SAF) to create, edit, import, rename, and delete files on FAT32, exFAT, or NTFS.",
                        icon = Icons.Outlined.Info,
                        variant = Md3BadgeVariant.Gold
                    )
                    Md3Button(
                        text = "Mount Folder Access (SAF)",
                        icon = Icons.Outlined.FolderOpen,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = onMountSafVolume,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "file_manager_mount_saf_button"
                    )
                } else {
                    Md3Button(
                        text = "Import File to Drive",
                        icon = Icons.Outlined.UploadFile,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = onImportExternalFileClick,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "import_file_button"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Md3Button(
                            text = "New Folder",
                            icon = Icons.Outlined.CreateNewFolder,
                            variant = Md3ButtonVariant.Secondary,
                            onClick = { showNewFolderDialog = true },
                            modifier = Modifier.weight(1f),
                            testTag = "new_folder_button"
                        )
                        Md3Button(
                            text = "New File",
                            icon = Icons.AutoMirrored.Outlined.NoteAdd,
                            variant = Md3ButtonVariant.Outline,
                            onClick = { showNewFileDialog = true },
                            modifier = Modifier.weight(1f),
                            testTag = "new_file_button"
                        )
                    }
                }
            }
        }

        // 2. Directory Items or Error State
        if (fileListError != null) {
            item {
                Md3Alert(
                    title = "Unable to Read USB Directory",
                    description = fileListError,
                    icon = Icons.Outlined.Info,
                    variant = Md3BadgeVariant.Destructive
                )
            }
        } else if (files.isEmpty()) {
            item {
                Md3Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ExpressiveScallopEmblem(
                            icon = Icons.Outlined.Folder,
                            size = 64.dp,
                            useCloverInner = true
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "This folder is empty",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (drive.supportsFileWrites) {
                                "Import a file or create a folder above."
                            } else {
                                "No files found in this directory."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(files, key = { it.documentId ?: it.path }) { entry ->
                FileEntryRowCard(
                    entry = entry,
                    onOpen = {
                        if (entry.isDirectory) {
                            onOpenDirectory(entry)
                        } else {
                            onOpenFileForEdit(entry)
                        }
                    },
                    onRename = { renameTarget = entry },
                    onDelete = { deleteTarget = entry },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    if (showNewFolderDialog) {
        SingleTextPromptDialog(
            title = "Create Folder",
            subtitle = "In $currentPath",
            label = "Folder Name",
            initialValue = "",
            confirmText = "Create",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                showNewFolderDialog = false
                onCreateFolder(name)
            }
        )
    }

    if (showNewFileDialog) {
        FileEditorDialog(
            title = "New File",
            initialFileName = "",
            initialContent = "",
            readOnlyName = false,
            canWrite = true,
            onDismiss = { showNewFileDialog = false },
            onSave = { name, content ->
                showNewFileDialog = false
                onSaveTextFile(name, content, null)
            }
        )
    }

    if (editingFile != null) {
        FileEditorDialog(
            title = editingFile.name,
            initialFileName = editingFile.name,
            initialContent = editingText,
            readOnlyName = true,
            canWrite = editingFile.canWrite && drive.supportsFileWrites,
            onDismiss = onCloseFileEditor,
            onSave = { name, content ->
                onSaveTextFile(name, content, editingFile)
            }
        )
    }

    val activeDelete = deleteTarget
    if (activeDelete != null) {
        Md3ConfirmDialog(
            title = if (activeDelete.isDirectory) "Delete this folder?" else "Delete this file?",
            message = if (activeDelete.isDirectory) {
                "The folder and everything inside it will be deleted. This cannot be undone."
            } else {
                "The file will be deleted. This cannot be undone."
            },
            details = listOf(
                "Name" to activeDelete.name,
                "Path" to activeDelete.path,
                "Drive" to drive.name
            ),
            confirmText = "Delete",
            onConfirm = {
                deleteTarget = null
                onDeleteEntry(activeDelete)
            },
            onDismiss = { deleteTarget = null },
            confirmTestTag = "confirm_delete_button"
        )
    }

    val activeRename = renameTarget
    if (activeRename != null) {
        SingleTextPromptDialog(
            title = "Rename Item",
            subtitle = activeRename.name,
            label = "New Name",
            initialValue = activeRename.name,
            confirmText = "Save",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                onRenameEntry(activeRename, newName)
            }
        )
    }
}

@Composable
private fun FileEntryRowCard(
    entry: UsbFileEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Md3Card(
        onClick = onOpen,
        contentPadding = PaddingValues(16.dp),
        modifier = modifier.testTag("file_item_${entry.name}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (entry.isDirectory) OrchidPink.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            entry.isDirectory -> Icons.Filled.Folder
                            entry.extension in listOf("TXT", "MD", "JSON", "XML", "CFG", "LOG") -> Icons.Outlined.Description
                            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = if (entry.isDirectory) OrchidPink else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (entry.isDirectory) {
                            if (entry.sizeBytes >= 0L) "${entry.sizeBytes} item(s)" else "Folder"
                        } else {
                            "${formatBytes(entry.sizeBytes)} • ${entry.extension}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (entry.canWrite) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRename) {
                        Icon(
                            imageVector = Icons.Outlined.DriveFileRenameOutline,
                            contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_file_${entry.name}")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete",
                            tint = Coral400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleTextPromptDialog(
    title: String,
    subtitle: String,
    label: String,
    initialValue: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    val shape = RoundedCornerShape(30.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .clip(shape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), shape),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(18.dp))
                Md3Input(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = label,
                    label = label,
                    testTag = "dialog_text_input"
                )
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Md3Button(
                        text = "Cancel",
                        variant = Md3ButtonVariant.Ghost,
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Md3Button(
                        text = confirmText,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = { onConfirm(value) },
                        testTag = "dialog_confirm_button"
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEditorDialog(
    title: String,
    initialFileName: String,
    initialContent: String,
    readOnlyName: Boolean,
    canWrite: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var fileName by remember { mutableStateOf(initialFileName) }
    var content by remember { mutableStateOf(initialContent) }
    val shape = RoundedCornerShape(30.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .clip(shape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), shape),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (!readOnlyName) {
                    Md3Input(
                        value = fileName,
                        onValueChange = { fileName = it },
                        placeholder = "filename.txt",
                        label = "File Name",
                        monospace = true,
                        testTag = "editor_filename_input"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Md3Input(
                    value = content,
                    onValueChange = { if (canWrite) content = it },
                    placeholder = "Enter file content...",
                    label = if (canWrite) "Content" else "Content (Read-Only)",
                    monospace = true,
                    singleLine = false,
                    modifier = Modifier.height(180.dp),
                    testTag = "editor_content_input"
                )
                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Md3Button(
                        text = if (canWrite) "Cancel" else "Close",
                        variant = Md3ButtonVariant.Ghost,
                        onClick = onDismiss
                    )
                    if (canWrite) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Md3Button(
                            text = "Save File",
                            variant = Md3ButtonVariant.GoldAccent,
                            onClick = { onSave(fileName, content) },
                            testTag = "editor_save_button"
                        )
                    }
                }
            }
        }
    }
}

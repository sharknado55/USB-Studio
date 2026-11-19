package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.FileSystemType
import com.example.model.FormatOperationStatus
import com.example.model.PartitionScheme
import com.example.model.UsbDriveDevice
import com.example.ui.components.ExpressiveScallopEmblem
import com.example.ui.components.Md3Alert
import com.example.ui.components.Md3Badge
import com.example.ui.components.Md3BadgeVariant
import com.example.ui.components.Md3Button
import com.example.ui.components.Md3ButtonVariant
import com.example.ui.components.Md3Card
import com.example.ui.components.Md3Input
import com.example.ui.components.Md3NoDriveEmptyState
import com.example.ui.components.Md3ProgressBar
import com.example.ui.components.Md3SelectChip
import com.example.ui.theme.OrchidPink
import com.example.ui.theme.Violet900
import com.example.util.formatBytes

@Composable
fun FormatToolScreen(
    drive: UsbDriveDevice?,
    volumeLabel: String,
    selectedFileSystem: FileSystemType,
    selectedClusterSize: Int,
    selectedScheme: PartitionScheme,
    quickFormat: Boolean,
    wipeFullDrive: Boolean,
    targetPartitionId: String?,
    formatStatus: FormatOperationStatus,
    onUpdateConfig: (
        label: String?,
        fs: FileSystemType?,
        cluster: Int?,
        scheme: PartitionScheme?,
        quick: Boolean?,
        wipeFull: Boolean?,
        partId: String?
    ) -> Unit,
    onExecuteFormat: () -> Unit,
    onInspectVbrSector: () -> Unit,
    onMountSafVolume: () -> Unit,
    onScanUsbBus: () -> Unit,
    onBackToDrives: () -> Unit,
    onRequestUsbPermission: (UsbDriveDevice) -> Unit = {},
    isCurrentTab: Boolean = true,
    topContentPadding: Dp = 14.dp,
    bottomContentPadding: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = isCurrentTab) { onBackToDrives() }

    var confirmFormatArmed by remember(drive?.id, selectedFileSystem, volumeLabel) {
        mutableStateOf(false)
    }

    if (drive == null) {
        Md3NoDriveEmptyState(
            screenTitle = "format",
            screenSubtitle = "Volume Formatter",
            accentIcon = Icons.Filled.Build,
            accentColor = OrchidPink,
            onMountSafVolume = onMountSafVolume,
            onScanUsbBus = onScanUsbBus,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            testTag = "format_tool_empty_state",
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("format_tool_screen"),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topContentPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Expressive Title Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "format",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (drive.totalBytes > 0L) {
                            "${drive.name} • ${formatBytes(drive.totalBytes)}"
                        } else {
                            drive.name
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                ExpressiveScallopEmblem(
                    icon = Icons.Filled.Build,
                    size = 66.dp,
                    useCloverInner = true,
                    outerColor = OrchidPink,
                    innerColor = Violet900,
                    iconTint = OrchidPink
                )
            }
        }

        // 1b. Hardware Permission / SAF-Only Notice
        if (drive.supportsRawBlockIo && !drive.hasUsbPermission) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Md3Alert(
                        title = "USB Host Permission Required",
                        description = "Grant direct USB OTG access so USB Studio can write real MBR/GPT tables and ${selectedFileSystem.label} filesystem structures to ${drive.name}.",
                        icon = Icons.Outlined.Usb,
                        variant = Md3BadgeVariant.Gold
                    )
                    Md3Button(
                        text = "Grant USB OTG Access",
                        icon = Icons.Outlined.Usb,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = { onRequestUsbPermission(drive) },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "format_grant_usb_permission_button"
                    )
                }
            }
        } else if (!drive.supportsRawBlockIo) {
            item {
                Md3Alert(
                    title = "SAF Folder Mount Mode",
                    description = "Android folder access (SAF) can erase all files and folders on ${drive.name}, but cannot rewrite LBA 0 partition tables or change filesystem types. Connect directly via USB-C OTG for full low-level formatting.",
                    icon = Icons.Outlined.WarningAmber,
                    variant = Md3BadgeVariant.Gold
                )
            }
        }

        // 2. Volume Name & File System Card (for raw USB OTG block formatting)
        if (drive.supportsRawBlockIo) {
            item {
                Md3Card {
                    Md3Input(
                        value = volumeLabel,
                        onValueChange = { onUpdateConfig(it.take(11).uppercase(), null, null, null, null, null, null) },
                        placeholder = "VOLUME_NAME",
                        label = "Volume Name",
                        monospace = true,
                        testTag = "format_label_input"
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "File System",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val supportedFs = listOf(
                        FileSystemType.FAT32,
                        FileSystemType.EXFAT,
                        FileSystemType.EXT4,
                        FileSystemType.NTFS
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        supportedFs.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                pair.forEach { fs ->
                                    Md3SelectChip(
                                        label = fs.label,
                                        subtitle = fs.maxFileSizeLabel,
                                        selected = selectedFileSystem == fs,
                                        onClick = { onUpdateConfig(null, fs, null, null, null, null, null) },
                                        modifier = Modifier.weight(1f),
                                        testTag = "fs_chip_${fs.label}"
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = selectedFileSystem.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. Partition Scheme & Quick Format Card
            item {
                Md3Card {
                    Text(
                        text = "Partition Scheme",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PartitionScheme.entries.forEach { scheme ->
                            Md3SelectChip(
                                label = scheme.name,
                                subtitle = if (scheme == PartitionScheme.GPT) "Modern UEFI" else "Legacy BIOS",
                                selected = selectedScheme == scheme,
                                onClick = { onUpdateConfig(null, null, null, scheme, null, null, null) },
                                modifier = Modifier.weight(1f),
                                testTag = "scheme_chip_${scheme.name}"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Quick Format",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (quickFormat) {
                                    "Clears partition table and writes fresh superblocks."
                                } else {
                                    "Zero-wipes MBR, GPT, backup GPT & FAT header sectors first."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = quickFormat,
                            onCheckedChange = { onUpdateConfig(null, null, null, null, it, null, null) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        }

        // 4. Progress, Error & Action
        item {
            if (formatStatus.errorMessage != null) {
                Md3Alert(
                    title = "Format Failed",
                    description = formatStatus.errorMessage,
                    icon = Icons.Outlined.ErrorOutline,
                    variant = Md3BadgeVariant.Destructive
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            AnimatedVisibility(
                visible = formatStatus.isRunning || formatStatus.completedVolumeUuid != null,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Md3Card(isHighlighted = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (formatStatus.isRunning) "Formatting..." else "Complete",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Md3Badge(
                                text = "${(formatStatus.progress * 100).toInt()}%",
                                variant = Md3BadgeVariant.Gold
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Md3ProgressBar(progress = formatStatus.progress)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = formatStatus.stepDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Md3Alert(
                title = "All data on ${drive.name} will be erased",
                description = if (drive.supportsRawBlockIo) {
                    "Make sure important files are backed up before formatting as ${selectedFileSystem.label}."
                } else {
                    "All files and folders inside the mounted SAF volume will be permanently deleted."
                },
                icon = Icons.Outlined.WarningAmber,
                variant = if (confirmFormatArmed) Md3BadgeVariant.Destructive else Md3BadgeVariant.Gold
            )

            Spacer(modifier = Modifier.height(16.dp))

            val canExecute = !formatStatus.isRunning && (!drive.supportsRawBlockIo || drive.hasUsbPermission)
            AnimatedContent(
                targetState = confirmFormatArmed,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)))
                        .togetherWith(fadeOut(tween(150)))
                },
                label = "formatConfirmButtonTransition"
            ) { armed ->
                if (!armed) {
                    Md3Button(
                        text = if (drive.supportsRawBlockIo) {
                            "Format as ${selectedFileSystem.label}"
                        } else {
                            "Erase All Files on Volume"
                        },
                        icon = Icons.Outlined.Build,
                        variant = Md3ButtonVariant.GoldAccent,
                        enabled = canExecute,
                        onClick = { confirmFormatArmed = true },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "arm_format_button"
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Md3Button(
                            text = "Cancel",
                            variant = Md3ButtonVariant.Outline,
                            onClick = { confirmFormatArmed = false },
                            modifier = Modifier.weight(1f)
                        )
                        Md3Button(
                            text = "Confirm Erase",
                            icon = Icons.Outlined.Build,
                            variant = Md3ButtonVariant.Destructive,
                            enabled = canExecute,
                            onClick = {
                                confirmFormatArmed = false
                                onExecuteFormat()
                            },
                            modifier = Modifier.weight(1.5f),
                            testTag = "execute_format_button"
                        )
                    }
                }
            }
        }
    }
}

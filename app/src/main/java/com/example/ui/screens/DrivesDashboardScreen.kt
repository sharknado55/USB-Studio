package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.DriveConnectionType
import com.example.model.OperationLogEntry
import com.example.model.UsbDriveDevice
import com.example.ui.components.ExpressiveHighlightPill
import com.example.ui.components.ExpressiveScallopEmblem
import com.example.ui.components.Md3Badge
import com.example.ui.components.Md3BadgeVariant
import com.example.ui.components.Md3Button
import com.example.ui.components.Md3ButtonVariant
import com.example.ui.components.Md3Card
import com.example.ui.components.Md3ProgressBar
import com.example.ui.theme.FlamingoRose
import com.example.ui.theme.Lavender300
import com.example.ui.theme.OrchidPink
import com.example.ui.theme.PeriwinkleSky
import com.example.ui.theme.Violet900
import com.example.util.formatBytes
import com.example.viewmodel.WorkbenchTab

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrivesDashboardScreen(
    drives: List<UsbDriveDevice>,
    selectedDrive: UsbDriveDevice?,
    logs: List<OperationLogEntry>,
    onSelectDrive: (String) -> Unit,
    onScanUsbBus: () -> Unit,
    onMountSafVolume: () -> Unit,
    onRequestUsbPermission: (UsbDriveDevice) -> Unit,
    onInspectSector: (Long, String) -> Unit,
    onNavigateToTool: (WorkbenchTab) -> Unit,
    topContentPadding: Dp = 14.dp,
    bottomContentPadding: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("drives_dashboard_list"),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topContentPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Expressive Hero Header (Big Bold Title + Airy Subtitle + Scalloped Badge + Crisp Highlight Pills)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "storage",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "OTG Disk Hub",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    ExpressiveScallopEmblem(
                        icon = Icons.Filled.Usb,
                        size = 76.dp,
                        useCloverInner = true,
                        outerColor = MaterialTheme.colorScheme.primary,
                        innerColor = MaterialTheme.colorScheme.onPrimary,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                }

                // Crisp Reference-Style Highlight Pills
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val countLabel = when (drives.size) {
                        0 -> "No Drives Connected"
                        1 -> "1 Drive Ready"
                        else -> "${drives.size} Drives Ready"
                    }
                    ExpressiveHighlightPill(text = countLabel)
                    ExpressiveHighlightPill(text = "USB-C OTG & SDXC")
                    if (selectedDrive != null) {
                        ExpressiveHighlightPill(
                            text = "${selectedDrive.primaryFileSystem.label} • ${selectedDrive.partitionScheme.name}",
                            inverted = false
                        )
                    }
                }

                // Big Expressive Pill Actions
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Md3Button(
                        text = "Mount External Drive / SD Card",
                        icon = Icons.Outlined.FolderOpen,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = onMountSafVolume,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "mount_saf_button"
                    )
                    Md3Button(
                        text = "Rescan USB Bus",
                        icon = Icons.Outlined.Refresh,
                        variant = Md3ButtonVariant.Outline,
                        onClick = onScanUsbBus,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "scan_usb_bus_button"
                    )
                }
            }
        }

        // 2. Drive Cards or Expressive Empty State
        if (drives.isEmpty()) {
            item(key = "no_drives_empty_card") {
                Md3Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("drives_empty_state_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveScallopEmblem(
                            icon = Icons.Filled.Usb,
                            size = 68.dp,
                            useCloverInner = false,
                            outerColor = MaterialTheme.colorScheme.primary,
                            innerColor = MaterialTheme.colorScheme.onPrimary,
                            iconTint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "No USB drive connected",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Connect a USB-C OTG flash drive or SD card reader, or tap Mount External Drive / SD Card above to grant folder access.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(drives, key = { it.id }) { drive ->
                val isSelected = drive.id == selectedDrive?.id
                DriveHardwareCard(
                    drive = drive,
                    isSelected = isSelected,
                    onClick = { onSelectDrive(drive.id) },
                    onRequestPermission = { onRequestUsbPermission(drive) },
                    modifier = Modifier.animateItem()
                )
            }
        }

        // 3. Playful Expressive Disk Tools
        if (selectedDrive != null) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "disk tools",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ToolRowCard(
                        title = "File Manager",
                        subtitle = "Browse folders, edit files & transfer",
                        badge = selectedDrive.primaryFileSystem.label,
                        icon = Icons.Filled.Folder,
                        badgeColor = Lavender300,
                        useClover = false,
                        onClick = { onNavigateToTool(WorkbenchTab.FILES) },
                        testTag = "quick_tool_files"
                    )
                    ToolRowCard(
                        title = "USB Formatter",
                        subtitle = "Format as exFAT, FAT32, EXT4 or NTFS",
                        badge = "FORMAT",
                        icon = Icons.Filled.Build,
                        badgeColor = OrchidPink,
                        useClover = true,
                        onClick = { onNavigateToTool(WorkbenchTab.FORMAT) },
                        testTag = "quick_tool_format"
                    )
                    ToolRowCard(
                        title = "ISO Writer",
                        subtitle = "Flash bootable ISO & IMG disk images",
                        badge = "BOOT",
                        icon = Icons.Filled.Album,
                        badgeColor = FlamingoRose,
                        useClover = false,
                        onClick = { onNavigateToTool(WorkbenchTab.ISO_WRITER) },
                        testTag = "quick_tool_iso"
                    )
                    ToolRowCard(
                        title = "Partition Manager",
                        subtitle = "Create, delete & organize GPT/MBR",
                        badge = selectedDrive.partitionScheme.name,
                        icon = Icons.Filled.PieChart,
                        badgeColor = PeriwinkleSky,
                        useClover = true,
                        onClick = { onNavigateToTool(WorkbenchTab.PARTITIONS) },
                        testTag = "quick_tool_partitions"
                    )
                }
            }
        }
    }
}

@Composable
private fun DriveHardwareCard(
    drive: UsbDriveDevice,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasKnownCapacity = drive.totalBytes > 0L
    val usedFraction = if (hasKnownCapacity) {
        (drive.usedBytes.toFloat() / drive.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val freeBytes = (drive.totalBytes - drive.usedBytes).coerceAtLeast(0L)

    Md3Card(
        isHighlighted = isSelected,
        onClick = onClick,
        modifier = modifier.testTag("drive_card_${drive.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                ExpressiveScallopEmblem(
                    icon = when (drive.connectionType) {
                        DriveConnectionType.SD_CARD_READER -> Icons.Filled.SdCard
                        DriveConnectionType.SAF_MOUNTED_VOLUME -> Icons.Filled.Folder
                        DriveConnectionType.USB_OTG_HARDWARE -> Icons.Filled.Usb
                    },
                    size = 54.dp,
                    useCloverInner = drive.connectionType == DriveConnectionType.SD_CARD_READER,
                    outerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    innerColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surface,
                    iconTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = drive.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    val sliceLabel = if (drive.partitions.size == 1) "1 volume" else "${drive.partitions.size} slices"
                    val subtitleLine = if (!drive.hasUsbPermission) {
                        "USB Host permission needed • ${drive.vidPidHex}"
                    } else if (drive.partitions.isEmpty()) {
                        "${drive.protocolName} • ${drive.vidPidHex}"
                    } else {
                        "${drive.primaryFileSystem.label} • ${drive.partitionScheme.name} • $sliceLabel"
                    }
                    Text(
                        text = subtitleLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))
            Md3Badge(
                text = if (isSelected) "ACTIVE" else drive.connectionType.badge,
                variant = if (isSelected) Md3BadgeVariant.Gold else Md3BadgeVariant.Secondary
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (hasKnownCapacity) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(drive.usedBytes)} used",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${formatBytes(freeBytes)} free",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Md3ProgressBar(
                progress = usedFraction,
                height = 14.dp
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (drive.usedBytes > 0L) "${formatBytes(drive.usedBytes)} indexed" else "Mounted ready",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (!drive.hasUsbPermission) "Permission required" else "Capacity not reported",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!drive.hasUsbPermission) {
            Spacer(modifier = Modifier.height(16.dp))
            Md3Button(
                text = "Grant USB OTG Access",
                icon = Icons.Outlined.Security,
                variant = Md3ButtonVariant.GoldAccent,
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ToolRowCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    badgeColor: Color,
    useClover: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Md3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        onClick = onClick,
        contentPadding = PaddingValues(18.dp)
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
                ExpressiveScallopEmblem(
                    icon = icon,
                    size = 52.dp,
                    useCloverInner = useClover,
                    outerColor = badgeColor,
                    innerColor = Violet900,
                    iconTint = badgeColor
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Md3Badge(text = badge, variant = Md3BadgeVariant.Outline)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

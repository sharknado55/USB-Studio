package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.FileSystemType
import com.example.model.PartitionScheme
import com.example.model.UsbDriveDevice
import com.example.model.UsbPartition
import com.example.ui.components.ExpressiveHighlightPill
import com.example.ui.components.ExpressiveScallopEmblem
import com.example.ui.components.Md3Alert
import com.example.ui.components.Md3Badge
import com.example.ui.components.Md3BadgeVariant
import com.example.ui.components.Md3Button
import com.example.ui.components.Md3ButtonVariant
import com.example.ui.components.Md3Card
import com.example.ui.components.Md3Input
import com.example.ui.components.Md3NoDriveEmptyState
import com.example.ui.components.Md3SelectChip
import com.example.ui.theme.Cosmic700
import com.example.ui.theme.FlamingoRose
import com.example.ui.theme.Lavender300
import com.example.ui.theme.OrchidPink
import com.example.ui.theme.PeriwinkleSky
import com.example.ui.theme.SoftPeach
import com.example.ui.theme.Violet900
import com.example.util.formatBytes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartitionManagerScreen(
    drive: UsbDriveDevice?,
    stagedScheme: PartitionScheme,
    stagedPartitions: List<UsbPartition>,
    hasUncommittedChanges: Boolean,
    onSelectScheme: (PartitionScheme) -> Unit,
    onCreatePartition: (unallocatedId: String, label: String, fs: FileSystemType, sizeMb: Long, bootable: Boolean) -> Unit,
    onDeletePartition: (String) -> Unit,
    onToggleBootFlag: (String) -> Unit,
    onResetChanges: () -> Unit,
    onCommitTable: () -> Unit,
    onInspectMbrSector: () -> Unit,
    onInspectGptSector: () -> Unit,
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

    var createTargetUnalloc by remember { mutableStateOf<UsbPartition?>(null) }

    if (drive == null) {
        Md3NoDriveEmptyState(
            screenTitle = "partitions",
            screenSubtitle = "GPT & MBR Table",
            accentIcon = Icons.Filled.PieChart,
            accentColor = PeriwinkleSky,
            onMountSafVolume = onMountSafVolume,
            onScanUsbBus = onScanUsbBus,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            testTag = "partition_manager_empty_state",
            modifier = modifier
        )
        return
    }

    val activeVolumeCount = remember(stagedPartitions) {
        stagedPartitions.count { it.fileSystem != FileSystemType.UNALLOCATED }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("partition_manager_screen"),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topContentPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Expressive Header (Clean non-wrapping drive subtitle + pill summary)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "partitions",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = drive.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    ExpressiveScallopEmblem(
                        icon = Icons.Filled.PieChart,
                        size = 64.dp,
                        useCloverInner = true,
                        outerColor = PeriwinkleSky,
                        innerColor = Violet900,
                        iconTint = PeriwinkleSky
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (drive.totalBytes > 0L) {
                        ExpressiveHighlightPill(text = formatBytes(drive.totalBytes))
                    }
                    ExpressiveHighlightPill(
                        text = "$activeVolumeCount Volume${if (activeVolumeCount == 1) "" else "s"}",
                        inverted = false
                    )
                    ExpressiveHighlightPill(
                        text = stagedScheme.name,
                        inverted = false
                    )
                }
            }
        }

        // 1b. Direct USB Host Block Access Check
        if (drive.supportsRawBlockIo && !drive.hasUsbPermission) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Md3Alert(
                        title = "USB Host Permission Required",
                        description = "Grant direct USB OTG access to read and write real GPT/MBR partition tables on ${drive.name}.",
                        icon = Icons.Outlined.Usb,
                        variant = Md3BadgeVariant.Gold
                    )
                    Md3Button(
                        text = "Grant USB OTG Access",
                        icon = Icons.Outlined.Usb,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = { onRequestUsbPermission(drive) },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "part_grant_usb_permission_button"
                    )
                }
            }
        } else if (!drive.supportsRawBlockIo) {
            item {
                Md3Alert(
                    title = "Direct USB-C OTG Required to Commit Table",
                    description = "Editing and writing GPT/MBR partition tables modifies raw LBA 0..33 sectors and requires a direct USB-C OTG hardware connection.",
                    icon = Icons.Outlined.Info,
                    variant = Md3BadgeVariant.Gold
                )
            }
        }

        // 2. Visual Allocation Map + Overflow-Free Legend & Scheme Selector
        item {
            Md3Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Disk Allocation",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Md3Badge(
                        text = if (hasUncommittedChanges) "UNSAVED" else stagedScheme.name,
                        variant = Md3BadgeVariant.Gold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Sleek Proportional Segmented Bar (no crammed/clipped text inside narrow slivers)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val totalSize = stagedPartitions.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                    stagedPartitions.forEach { part ->
                        val targetWeight = (part.sizeBytes.toFloat() / totalSize.toFloat()).coerceIn(0.06f, 1f)
                        val animatedWeight by animateFloatAsState(
                            targetValue = targetWeight,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            ),
                            label = "partitionSliceWeight"
                        )
                        val isFree = part.fileSystem == FileSystemType.UNALLOCATED
                        val sliceColor = expressiveColorForFileSystem(part.fileSystem)
                        Box(
                            modifier = Modifier
                                .weight(animatedWeight)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    if (isFree) sliceColor.copy(alpha = 0.35f) else sliceColor
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Clean, Wrapping Partition Legend — every label & size fully legible
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stagedPartitions.forEach { part ->
                        val isFree = part.fileSystem == FileSystemType.UNALLOCATED
                        val sliceColor = expressiveColorForFileSystem(part.fileSystem)
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isFree) sliceColor.copy(alpha = 0.55f) else sliceColor)
                            )
                            Text(
                                text = if (isFree) "Free" else part.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatBytes(part.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Partition Table Standard",
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
                            selected = stagedScheme == scheme,
                            onClick = { onSelectScheme(scheme) },
                            modifier = Modifier.weight(1f),
                            testTag = "part_scheme_${scheme.name}"
                        )
                    }
                }
            }
        }

        // 3. Pending Commit Card
        item {
            AnimatedVisibility(
                visible = hasUncommittedChanges,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Md3Card(isHighlighted = true) {
                    Text(
                        text = "Pending Partition Changes",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Apply your changes to update the ${stagedScheme.name} partition table on disk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Md3Button(
                            text = "Reset",
                            icon = Icons.Outlined.Restore,
                            variant = Md3ButtonVariant.Outline,
                            compact = true,
                            onClick = onResetChanges,
                            modifier = Modifier.weight(1f),
                            testTag = "discard_partition_changes_button"
                        )
                        Md3Button(
                            text = "Apply Changes",
                            icon = Icons.Outlined.Check,
                            variant = Md3ButtonVariant.GoldAccent,
                            compact = true,
                            onClick = onCommitTable,
                            modifier = Modifier.weight(1.45f),
                            testTag = "commit_partition_table_button"
                        )
                    }
                }
            }
        }

        // 4. Partition Slice Cards
        items(stagedPartitions, key = { it.id }) { part ->
            PartitionSliceCard(
                partition = part,
                onAllocateClick = { createTargetUnalloc = part },
                onToggleBoot = { onToggleBootFlag(part.id) },
                onDeleteClick = { onDeletePartition(part.id) },
                modifier = Modifier.animateItem()
            )
        }
    }

    val unallocTarget = createTargetUnalloc
    if (unallocTarget != null) {
        CreatePartitionDialog(
            unallocatedSlice = unallocTarget,
            onDismiss = { createTargetUnalloc = null },
            onConfirm = { label, fs, sizeMb, boot ->
                createTargetUnalloc = null
                onCreatePartition(unallocTarget.id, label, fs, sizeMb, boot)
            }
        )
    }
}

@Composable
private fun PartitionSliceCard(
    partition: UsbPartition,
    onAllocateClick: () -> Unit,
    onToggleBoot: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnallocated = partition.fileSystem == FileSystemType.UNALLOCATED
    val accent = expressiveColorForFileSystem(partition.fileSystem)

    Md3Card(
        isHighlighted = !isUnallocated && partition.isBootable,
        modifier = modifier.testTag("partition_card_${partition.index}")
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
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isUnallocated) "Unallocated Space" else partition.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${partition.fileSystem.label} • ${formatBytes(partition.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            if (partition.isBootable) {
                Md3Badge(
                    text = "BOOTABLE",
                    variant = Md3BadgeVariant.Gold
                )
            } else {
                Md3Badge(
                    text = "#${partition.index}",
                    variant = Md3BadgeVariant.Outline
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (!isUnallocated) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Md3Button(
                    text = if (partition.isBootable) "Clear Boot" else "Set Boot",
                    icon = Icons.Outlined.Flag,
                    variant = if (partition.isBootable) {
                        Md3ButtonVariant.Secondary
                    } else {
                        Md3ButtonVariant.Outline
                    },
                    compact = true,
                    onClick = onToggleBoot,
                    modifier = Modifier.weight(1.15f),
                    testTag = "toggle_boot_part_${partition.index}"
                )
                Md3Button(
                    text = "Delete",
                    icon = Icons.Outlined.DeleteOutline,
                    variant = Md3ButtonVariant.Destructive,
                    compact = true,
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    testTag = "delete_part_${partition.index}"
                )
            }
        } else {
            Md3Button(
                text = "Create Partition Here",
                icon = Icons.Outlined.Add,
                variant = Md3ButtonVariant.GoldAccent,
                compact = true,
                onClick = onAllocateClick,
                modifier = Modifier.fillMaxWidth(),
                testTag = "allocate_part_${partition.index}"
            )
        }
    }
}

@Composable
private fun CreatePartitionDialog(
    unallocatedSlice: UsbPartition,
    onDismiss: () -> Unit,
    onConfirm: (label: String, fs: FileSystemType, sizeMb: Long, bootable: Boolean) -> Unit
) {
    val maxMb = (unallocatedSlice.sizeBytes / (1024L * 1024L)).coerceAtLeast(64L)
    var label by remember { mutableStateOf("") }
    var fs by remember { mutableStateOf(FileSystemType.EXFAT) }
    var fraction by remember { mutableFloatStateOf(1.0f) }
    var bootable by remember { mutableStateOf(false) }

    val selectedMb = (maxMb * fraction).toLong().coerceIn(32L, maxMb)
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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    text = "New Partition",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Available: ${formatBytes(unallocatedSlice.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))
                Md3Input(
                    value = label,
                    onValueChange = { label = it.take(11).uppercase() },
                    placeholder = "DATA_VOL",
                    label = "Volume Name",
                    monospace = true,
                    testTag = "new_partition_label_input"
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Size",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatBytes(selectedMb * 1024L * 1024L),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = fraction,
                    onValueChange = { fraction = it.coerceIn(0.1f, 1.0f) },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "File System",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        listOf(FileSystemType.EXFAT, FileSystemType.FAT32),
                        listOf(FileSystemType.EXT4, FileSystemType.NTFS)
                    ).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { item ->
                                Md3SelectChip(
                                    label = item.label,
                                    selected = fs == item,
                                    onClick = { fs = item },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bootable Partition",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = bootable,
                        onCheckedChange = { bootable = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    Md3Button(
                        text = "Cancel",
                        variant = Md3ButtonVariant.Ghost,
                        compact = true,
                        onClick = onDismiss
                    )
                    Md3Button(
                        text = "Create",
                        variant = Md3ButtonVariant.GoldAccent,
                        compact = true,
                        onClick = { onConfirm(label, fs, selectedMb, bootable) },
                        testTag = "confirm_new_partition_button"
                    )
                }
            }
        }
    }
}

private fun expressiveColorForFileSystem(fs: FileSystemType): Color = when (fs) {
    FileSystemType.EXFAT -> Lavender300
    FileSystemType.FAT32 -> OrchidPink
    FileSystemType.EXT4 -> PeriwinkleSky
    FileSystemType.NTFS -> SoftPeach
    FileSystemType.ISO9660 -> FlamingoRose
    FileSystemType.UNALLOCATED -> Cosmic700
}

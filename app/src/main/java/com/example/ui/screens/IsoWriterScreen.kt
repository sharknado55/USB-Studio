package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.IsoFlashStatus
import com.example.model.UsbDriveDevice
import com.example.ui.components.ExpressiveScallopEmblem
import com.example.ui.components.Md3Alert
import com.example.ui.components.Md3Badge
import com.example.ui.components.Md3BadgeVariant
import com.example.ui.components.Md3Button
import com.example.ui.components.Md3ButtonVariant
import com.example.ui.components.Md3Card
import com.example.ui.components.Md3KeyValueRow
import com.example.ui.components.Md3NoDriveEmptyState
import com.example.ui.components.Md3ProgressBar
import com.example.ui.theme.FlamingoRose
import com.example.ui.theme.Violet900
import com.example.util.formatBytes

@Composable
fun IsoWriterScreen(
    drive: UsbDriveDevice?,
    customIsoName: String?,
    customIsoSize: Long,
    verifySha256: Boolean,
    flashStatus: IsoFlashStatus,
    onPickExternalIsoFile: () -> Unit,
    onToggleVerifySha256: (Boolean) -> Unit,
    onExecuteFlash: () -> Unit,
    onInspectIsoPvdSector: () -> Unit,
    onInspectHybridMbrSector: () -> Unit,
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

    if (drive == null) {
        Md3NoDriveEmptyState(
            screenTitle = "iso writer",
            screenSubtitle = "Bootable Images",
            accentIcon = Icons.Filled.Album,
            accentColor = FlamingoRose,
            onMountSafVolume = onMountSafVolume,
            onScanUsbBus = onScanUsbBus,
            topContentPadding = topContentPadding,
            bottomContentPadding = bottomContentPadding,
            testTag = "iso_writer_empty_state",
            modifier = modifier
        )
        return
    }

    val hasSelectedImage = !customIsoName.isNullOrBlank()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("iso_writer_screen"),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topContentPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Expressive Header & Local Image Picker
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "iso writer",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Bootable Images",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    ExpressiveScallopEmblem(
                        icon = Icons.Filled.Album,
                        size = 66.dp,
                        useCloverInner = false,
                        outerColor = FlamingoRose,
                        innerColor = Violet900,
                        iconTint = FlamingoRose
                    )
                }

                Md3Button(
                    text = if (hasSelectedImage) {
                        "Change Image: $customIsoName"
                    } else {
                        "Pick .ISO or .IMG from Device"
                    },
                    icon = Icons.Outlined.FolderOpen,
                    variant = if (hasSelectedImage) Md3ButtonVariant.Secondary else Md3ButtonVariant.GoldAccent,
                    onClick = onPickExternalIsoFile,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "browse_external_iso_button"
                )
            }
        }

        // 1b. Hardware Block Access Check
        if (drive.supportsRawBlockIo && !drive.hasUsbPermission) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Md3Alert(
                        title = "USB Host Permission Required",
                        description = "Grant direct USB OTG access so USB Studio can write raw .ISO/.IMG sectors directly to ${drive.name}.",
                        icon = Icons.Outlined.Usb,
                        variant = Md3BadgeVariant.Gold
                    )
                    Md3Button(
                        text = "Grant USB OTG Access",
                        icon = Icons.Outlined.Usb,
                        variant = Md3ButtonVariant.GoldAccent,
                        onClick = { onRequestUsbPermission(drive) },
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "iso_grant_usb_permission_button"
                    )
                }
            }
        } else if (!drive.supportsRawBlockIo) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Md3Alert(
                        title = "Direct USB-C OTG Required",
                        description = "Flashing raw bootable .ISO or .IMG images writes directly to LBA 0 sectors and requires a direct USB-C OTG hardware connection (not a folder-only SAF mount).",
                        icon = Icons.Outlined.Info,
                        variant = Md3BadgeVariant.Gold
                    )
                    Md3Button(
                        text = "Rescan USB Bus",
                        icon = Icons.Outlined.Refresh,
                        variant = Md3ButtonVariant.Outline,
                        onClick = onScanUsbBus,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. Prompt Card when no image is selected yet
        if (!hasSelectedImage) {
            item {
                Md3Alert(
                    title = "No disk image selected",
                    description = "Choose a local .iso or .img file from your device storage to write directly to ${drive.name}.",
                    icon = Icons.Outlined.Info,
                    variant = Md3BadgeVariant.Gold
                )
            }
        }

        // 3. Verification Option & Flash Action
        item {
            Md3Card {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Verify Write (SHA-256)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Checks written sectors after flashing to verify boot integrity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = verifySha256,
                        onCheckedChange = onToggleVerifySha256,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(16.dp))

                Md3KeyValueRow("Image", customIsoName ?: "None selected")
                Md3KeyValueRow("Size", if (hasSelectedImage && customIsoSize > 0L) formatBytes(customIsoSize) else "—")
                Md3KeyValueRow("Target Drive", drive.name, monospaceValue = false)

                Spacer(modifier = Modifier.height(18.dp))

                Md3Button(
                    text = if (flashStatus.isRunning) "Flashing Image..." else "Flash to Drive",
                    icon = Icons.Outlined.Bolt,
                    variant = Md3ButtonVariant.GoldAccent,
                    enabled = hasSelectedImage && !flashStatus.isRunning,
                    onClick = onExecuteFlash,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "execute_iso_flash_button"
                )
            }
        }

        // 4. Error Banner (if any)
        if (flashStatus.errorMessage != null) {
            item {
                Md3Alert(
                    title = "Flash Failed",
                    description = flashStatus.errorMessage,
                    icon = Icons.Outlined.ErrorOutline,
                    variant = Md3BadgeVariant.Destructive
                )
            }
        }

        // 5. Animated Progress Card
        item {
            AnimatedVisibility(
                visible = flashStatus.isRunning || flashStatus.sha256Hex.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Md3Card(isHighlighted = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = flashStatus.stage,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Md3Badge(
                            text = if (flashStatus.isRunning) {
                                "${String.format("%.1f", flashStatus.speedMbps)} MB/s"
                            } else {
                                "COMPLETE"
                            },
                            variant = Md3BadgeVariant.Gold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Md3ProgressBar(progress = flashStatus.progress)
                    Spacer(modifier = Modifier.height(14.dp))

                    Md3KeyValueRow(
                        "Written",
                        "${formatBytes(flashStatus.bytesProcessed)} / ${formatBytes(flashStatus.totalBytes)}"
                    )
                    if (flashStatus.sha256Hex.isNotEmpty()) {
                        Md3KeyValueRow(
                            "SHA-256",
                            "${flashStatus.sha256Hex.take(12)}...${flashStatus.sha256Hex.takeLast(8)}"
                        )
                    }
                }
            }
        }
    }
}

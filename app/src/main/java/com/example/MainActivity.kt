package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import com.example.data.UsbDiskEngine
import com.example.model.UsbDriveDevice
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ui.components.ExpressiveAtmosphereBackground
import com.example.ui.components.ExpressiveScallopEmblem
import com.example.ui.components.LegacyBlurSourceContainer
import com.example.ui.components.LegacyRenderScriptBackdropBlur
import com.example.ui.components.Md3HexViewerDialog
import com.example.ui.screens.DrivesDashboardScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.FormatToolScreen
import com.example.ui.screens.IsoWriterScreen
import com.example.ui.screens.PartitionManagerScreen
import com.example.ui.theme.Cosmic900
import com.example.ui.theme.Cosmic950
import com.example.ui.theme.Lavender100
import com.example.ui.theme.Lavender200
import com.example.ui.theme.Lavender300
import com.example.ui.theme.LilacCanvas
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Violet600
import com.example.ui.theme.Violet900
import com.example.util.DisplayRefreshRateController
import com.example.viewmodel.UsbStudioUiState
import com.example.viewmodel.UsbStudioViewModel
import com.example.viewmodel.WorkbenchTab

class MainActivity : ComponentActivity() {

    private val viewModel: UsbStudioViewModel by viewModels()
    private lateinit var refreshRateController: DisplayRefreshRateController

    private val usbEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                UsbDiskEngine.ACTION_USB_PERMISSION -> {
                    viewModel.refreshUsbBus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MyApplication)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splash ->
                animateLaunchExit(splash, splash.iconView, splash::remove)
            }
        }
        refreshRateController = DisplayRefreshRateController(this)
        refreshRateController.start()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        registerUsbReceiver()

        var effectsReady by mutableStateOf(false)
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (state.darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (state.darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            MyApplicationTheme(darkTheme = state.darkTheme) {
                UsbStudioApp(
                    state = state,
                    viewModel = viewModel,
                    effectsReady = effectsReady
                )
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && savedInstanceState == null) {
            showLegacyLaunchScreen()
        }
        window.decorView.doOnPreDraw { decor ->
            decor.postOnAnimation {
                if (!isDestroyed) {
                    effectsReady = true
                    viewModel.refreshUsbBus()
                }
            }
        }
    }

    private fun showLegacyLaunchScreen() {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_launcher_foreground) }
        val logoSize = resources.getDimensionPixelSize(R.dimen.launch_logo_size)
        val splash = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.launcher_purple))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(logo, FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER))
        }
        content.addView(splash, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        splash.doOnPreDraw {
            splash.postOnAnimation { animateLaunchExit(splash, logo) { content.removeView(splash) } }
        }
    }

    private fun animateLaunchExit(splash: View, logo: View?, remove: () -> Unit) {
        logo?.let { it.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180L).start() }
        splash.animate().alpha(0f).setDuration(180L).withEndAction { remove() }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            intent.action == UsbDiskEngine.ACTION_USB_PERMISSION
        ) {
            viewModel.refreshUsbBus()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRateController.onUserInteraction()
    }

    override fun onPause() {
        super.onPause()
        refreshRateController.onInactive()
    }

    // Called by Activity for every touch, key or trackball event: switch back to the highest rate.
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::refreshRateController.isInitialized) {
            refreshRateController.onUserInteraction()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::refreshRateController.isInitialized) {
            refreshRateController.applyOptimalRefreshRate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::refreshRateController.isInitialized) {
            refreshRateController.stop()
        }
        runCatching { unregisterReceiver(usbEventReceiver) }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbDiskEngine.ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, usbEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

@Composable
fun UsbStudioApp(
    state: UsbStudioUiState,
    viewModel: UsbStudioViewModel,
    effectsReady: Boolean = true
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val safTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.mountSafVolume(uri)
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val meta = queryUriNameAndSize(context, uri)
            viewModel.importExternalFileToCurrentDir(uri, meta.first)
        }
    }

    val pickIsoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryUriNameAndSize(context, uri)
            viewModel.selectCustomIsoUri(uri, name, size)
        }
    }

    val supportsRenderEffectBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val backdropLayer = if (effectsReady && supportsRenderEffectBlur) rememberGraphicsLayer() else null
    val grainBrush = if (effectsReady) remember(state.darkTheme) {
        createFrostedGrainBrush(darkTheme = state.darkTheme)
    } else null
    var legacyBlurSourceView by remember { mutableStateOf<View?>(null) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 680.dp

        val topContentPadding = remember(topBarHeightPx) {
            if (topBarHeightPx > 0) with(density) { topBarHeightPx.toDp() } + 8.dp else 112.dp
        }
        val bottomContentPadding = remember(bottomBarHeightPx, isWideScreen) {
            if (isWideScreen) {
                24.dp
            } else if (bottomBarHeightPx > 0) {
                with(density) { bottomBarHeightPx.toDp() } + 10.dp
            } else {
                108.dp
            }
        }

        // LAYER 1: Full-bleed atmospheric background + scrolling content.
        // - Android 12+ (API 31+): Record into a hardware GraphicsLayer for RenderEffect blur.
        // - Android 11 & below (API <= 30): Host Layer 1 in its own sibling ComposeView so
        //   RenderScript backdrop capture snapshots ONLY Layer 1 and never hides or touches the top bar.
        val mainContentLayer: @Composable () -> Unit = {
            ExpressiveAtmosphereBackground(
                darkTheme = state.darkTheme,
                showAtmosphere = effectsReady,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (backdropLayer != null) {
                            Modifier.drawWithContent {
                                backdropLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(backdropLayer)
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                if (isWideScreen) {
                    ExpressiveSideNavigationRail(
                        currentTab = state.currentTab,
                        onSelectTab = viewModel::selectTab,
                        topPadding = topContentPadding
                    )
                }

                // Crisp deterministic Shared-Axis screen transitions (zero Android 11 HWUI scale-layer stalls)
                AnimatedContent(
                    targetState = state.currentTab,
                    contentKey = { it },
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        val slideDirection = if (forward) 1 else -1
                        androidx.compose.animation.ContentTransform(
                            targetContentEnter = slideInHorizontally(
                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> (fullWidth * 0.12f * slideDirection).toInt() }
                            ) + fadeIn(
                                animationSpec = tween(190, easing = FastOutSlowInEasing)
                            ),
                            initialContentExit = slideOutHorizontally(
                                animationSpec = tween(170, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> (-fullWidth * 0.08f * slideDirection).toInt() }
                            ) + fadeOut(
                                animationSpec = tween(130, easing = FastOutSlowInEasing)
                            ),
                            targetContentZIndex = 1f,
                            sizeTransform = SizeTransform(clip = true)
                        )
                    },
                    label = "expressiveTabTransition",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) { targetTab ->
                    val isCurrent = state.currentTab == targetTab
                    when (targetTab) {
                        WorkbenchTab.DRIVES -> {
                            DrivesDashboardScreen(
                                drives = state.drives,
                                selectedDrive = state.selectedDrive,
                                logs = state.logs,
                                onSelectDrive = viewModel::selectDrive,
                                onScanUsbBus = viewModel::refreshUsbBus,
                                onMountSafVolume = { safTreeLauncher.launch(null) },
                                onRequestUsbPermission = viewModel::requestHardwareUsbPermission,
                                onInspectSector = viewModel::inspectDriveSector,
                                onNavigateToTool = viewModel::selectTab,
                                topContentPadding = topContentPadding,
                                bottomContentPadding = bottomContentPadding
                            )
                        }

                        WorkbenchTab.FILES -> {
                            FileManagerScreen(
                                drive = state.selectedDrive,
                                currentPath = state.currentPath,
                                files = state.filteredFiles,
                                searchQuery = state.fileSearchQuery,
                                editingFile = state.selectedFileForEdit,
                                editingText = state.editingFileText,
                                onSearchQueryChange = viewModel::updateFileSearchQuery,
                                onNavigateToPath = viewModel::navigateToPath,
                                onNavigateUp = viewModel::navigateUpDirectory,
                                onCreateFolder = viewModel::createNewFolder,
                                onSaveTextFile = viewModel::createOrSaveTextFile,
                                onOpenFileForEdit = viewModel::openFileForEditing,
                                onCloseFileEditor = viewModel::closeFileEditor,
                                onImportExternalFileClick = {
                                    importFileLauncher.launch(arrayOf("*/*"))
                                },
                                onInspectFileHex = viewModel::inspectFileHex,
                                onRenameEntry = viewModel::renameFileEntry,
                                onDeleteEntry = viewModel::deleteFileEntry,
                                onMountSafVolume = { safTreeLauncher.launch(null) },
                                onScanUsbBus = viewModel::refreshUsbBus,
                                onBackToDrives = { viewModel.selectTab(WorkbenchTab.DRIVES) },
                                onOpenDirectory = viewModel::openDirectory,
                                fileListError = state.fileListError,
                                isCurrentTab = isCurrent,
                                topContentPadding = topContentPadding,
                                bottomContentPadding = bottomContentPadding
                            )
                        }

                        WorkbenchTab.FORMAT -> {
                            FormatToolScreen(
                                drive = state.selectedDrive,
                                volumeLabel = state.formatLabel,
                                selectedFileSystem = state.formatFileSystem,
                                selectedClusterSize = state.formatClusterSize,
                                selectedScheme = state.formatScheme,
                                quickFormat = state.formatQuick,
                                wipeFullDrive = state.formatWipeFullDrive,
                                targetPartitionId = state.formatTargetPartitionId,
                                formatStatus = state.formatStatus,
                                onUpdateConfig = viewModel::updateFormatConfig,
                                onExecuteFormat = viewModel::executeFormatOperation,
                                onInspectVbrSector = {
                                    viewModel.inspectDriveSector(
                                        64L,
                                        "LBA 64 • ${state.formatFileSystem.label} Volume Boot Record"
                                    )
                                },
                                onMountSafVolume = { safTreeLauncher.launch(null) },
                                onScanUsbBus = viewModel::refreshUsbBus,
                                onBackToDrives = { viewModel.selectTab(WorkbenchTab.DRIVES) },
                                onRequestUsbPermission = viewModel::requestHardwareUsbPermission,
                                isCurrentTab = isCurrent,
                                topContentPadding = topContentPadding,
                                bottomContentPadding = bottomContentPadding
                            )
                        }

                        WorkbenchTab.ISO_WRITER -> {
                            IsoWriterScreen(
                                drive = state.selectedDrive,
                                customIsoName = state.customIsoName,
                                customIsoSize = state.customIsoSize,
                                verifySha256 = state.verifyIsoSha256,
                                flashStatus = state.isoFlashStatus,
                                onPickExternalIsoFile = {
                                    pickIsoLauncher.launch(arrayOf("*/*"))
                                },
                                onToggleVerifySha256 = viewModel::toggleVerifyIsoSha256,
                                onExecuteFlash = viewModel::executeIsoFlash,
                                onInspectIsoPvdSector = {
                                    viewModel.inspectDriveSector(
                                        64L,
                                        "ISO-9660 Primary Volume Descriptor"
                                    )
                                },
                                onInspectHybridMbrSector = {
                                    viewModel.inspectDriveSector(
                                        0L,
                                        "LBA 0 • Hybrid Bootable MBR"
                                    )
                                },
                                onMountSafVolume = { safTreeLauncher.launch(null) },
                                onScanUsbBus = viewModel::refreshUsbBus,
                                onBackToDrives = { viewModel.selectTab(WorkbenchTab.DRIVES) },
                                onRequestUsbPermission = viewModel::requestHardwareUsbPermission,
                                isCurrentTab = isCurrent,
                                topContentPadding = topContentPadding,
                                bottomContentPadding = bottomContentPadding
                            )
                        }

                        WorkbenchTab.PARTITIONS -> {
                            PartitionManagerScreen(
                                drive = state.selectedDrive,
                                stagedScheme = state.stagedScheme,
                                stagedPartitions = state.stagedPartitions,
                                hasUncommittedChanges = state.hasUncommittedPartitionChanges,
                                onSelectScheme = viewModel::setStagedPartitionScheme,
                                onCreatePartition = viewModel::createPartitionInUnallocated,
                                onDeletePartition = viewModel::deleteStagedPartition,
                                onToggleBootFlag = viewModel::togglePartitionBootFlag,
                                onResetChanges = viewModel::resetStagedPartitionChanges,
                                onCommitTable = viewModel::applyStagedPartitionTable,
                                onInspectMbrSector = {
                                    viewModel.inspectDriveSector(
                                        0L,
                                        "LBA 0 • Master Boot Record"
                                    )
                                },
                                onInspectGptSector = {
                                    viewModel.inspectDriveSector(
                                        1L,
                                        "LBA 1 • GPT Header"
                                    )
                                },
                                onMountSafVolume = { safTreeLauncher.launch(null) },
                                onScanUsbBus = viewModel::refreshUsbBus,
                                onBackToDrives = { viewModel.selectTab(WorkbenchTab.DRIVES) },
                                onRequestUsbPermission = viewModel::requestHardwareUsbPermission,
                                isCurrentTab = isCurrent,
                                topContentPadding = topContentPadding,
                                bottomContentPadding = bottomContentPadding
                            )
                        }
                    }
                }
            }
        }
        }

        if (supportsRenderEffectBlur) {
            mainContentLayer()
        } else {
            LegacyBlurSourceContainer(
                onSourceViewReady = { legacyBlurSourceView = it },
                modifier = Modifier.fillMaxSize(),
                content = mainContentLayer
            )
        }

        // LAYER 2: Transparent Floating Top Bar with Real-Time Glassy Backdrop Blur
        // (RenderEffect on Android 12+, RenderScript ScriptIntrinsicBlur on Android 11 & below)
        ExpressiveGlassyTopHeader(
            selectedDrive = state.selectedDrive,
            currentTab = state.currentTab,
            darkTheme = state.darkTheme,
            backdropLayer = backdropLayer,
            legacyBlurSourceView = legacyBlurSourceView.takeIf { effectsReady },
            grainBrush = grainBrush,
            onToggleTheme = viewModel::toggleTheme,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeightPx = it.height }
        )

        // LAYER 3: Expressive Pill Notification Toast floating beneath the glassy top bar
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = topContentPadding)
        ) {
            AnimatedVisibility(
                visible = state.bannerMessage != null,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(220)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(tween(180))
            ) {
                val msg = state.bannerMessage.orEmpty()
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Lavender100 else Violet600)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (isDark) Violet900 else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isDark) Violet900 else Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = viewModel::dismissBanner,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Dismiss notification",
                            tint = if (isDark) Violet900 else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // LAYER 4: Floating Expressive Glassy Pill Bottom Navigation Bar
        // (RenderEffect blur on Android 12+, RenderScript ScriptIntrinsicBlur on Android 11 & below)
        if (!isWideScreen) {
            ExpressiveFloatingBottomBar(
                currentTab = state.currentTab,
                darkTheme = state.darkTheme,
                backdropLayer = backdropLayer,
                legacyBlurSourceView = legacyBlurSourceView.takeIf { effectsReady },
                grainBrush = grainBrush,
                onSelectTab = viewModel::selectTab,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomBarHeightPx = it.height }
            )
        }
    }

    val hexPayload = state.activeHexInspection
    if (hexPayload != null) {
        Md3HexViewerDialog(
            title = hexPayload.title,
            subtitle = hexPayload.subtitle,
            sectorData = hexPayload.bytes,
            baseOffset = hexPayload.baseOffset,
            onDismiss = viewModel::closeHexInspector
        )
    }
}

@Composable
private fun ExpressiveGlassyTopHeader(
    selectedDrive: UsbDriveDevice?,
    currentTab: WorkbenchTab,
    darkTheme: Boolean,
    backdropLayer: GraphicsLayer?,
    legacyBlurSourceView: View?,
    grainBrush: Brush?,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderEffectSupported = backdropLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val glassTintBrush = remember(darkTheme) {
        if (darkTheme) {
            Brush.verticalGradient(
                colors = listOf(
                    Cosmic950.copy(alpha = 0.48f),
                    Cosmic900.copy(alpha = 0.30f),
                    Cosmic900.copy(alpha = 0.16f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    LilacCanvas.copy(alpha = 0.58f),
                    LilacCanvas.copy(alpha = 0.38f),
                    LilacCanvas.copy(alpha = 0.22f)
                )
            )
        }
    }

    val frostedSheenBrush = remember(darkTheme) {
        Brush.horizontalGradient(
            colors = if (darkTheme) {
                listOf(
                    Lavender300.copy(alpha = 0.14f),
                    Lavender200.copy(alpha = 0.06f),
                    Color.Transparent,
                    Lavender300.copy(alpha = 0.10f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.52f),
                    Lavender200.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.38f)
                )
            }
        )
    }

    val specularLineBrush = remember(darkTheme) {
        Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                if (darkTheme) Lavender200.copy(alpha = 0.28f) else Violet600.copy(alpha = 0.20f),
                if (darkTheme) Color.White.copy(alpha = 0.38f) else Violet600.copy(alpha = 0.30f),
                if (darkTheme) Lavender200.copy(alpha = 0.28f) else Violet600.copy(alpha = 0.20f),
                Color.Transparent
            )
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // 1. Live Frosted Backdrop Blur:
            //    - Android 12+ (API 31+): Hardware RenderEffect GraphicsLayer blur
            //    - Android 11 & below (API <= 30): Sibling ComposeView strip capture + RenderScript ScriptIntrinsicBlur
            if (renderEffectSupported) {
                val liveBackdrop = backdropLayer!!
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clipToBounds()
                        .blur(
                            radius = 18.dp,
                            edgeTreatment = BlurredEdgeTreatment.Rectangle
                        )
                        .drawBehind {
                            if (liveBackdrop.size.width > 0 && liveBackdrop.size.height > 0) {
                                drawLayer(liveBackdrop)
                            }
                        }
                )
            } else if (legacyBlurSourceView != null) {
                val fallbackColor = if (darkTheme) Cosmic950 else LilacCanvas
                LegacyRenderScriptBackdropBlur(
                    sourceView = legacyBlurSourceView,
                    fallbackBackgroundColor = fallbackColor,
                    refreshKey = Pair(currentTab, darkTheme),
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("legacy_renderscript_blur_${currentTab.route}")
                )
            }

            // 2. Translucent Frosted Glass Tint, Etched Micro-Grain & Optical Refraction Sheen
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(glassTintBrush)
                    .then(if (grainBrush == null) Modifier else Modifier.background(grainBrush))
                    .background(frostedSheenBrush)
            )

            // 3. Delicate Glass Specular Bottom Edge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(specularLineBrush)
            )

            // 4. Crisp Unblurred Foreground Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                ExpressiveScallopEmblem(
                    icon = Icons.Filled.Usb,
                    size = 46.dp,
                    useCloverInner = false
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "usb-studio",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    AnimatedContent(
                        targetState = selectedDrive?.name ?: "No drive attached",
                        transitionSpec = {
                            (slideInVertically { h -> h / 2 } + fadeIn(tween(200)))
                                .togetherWith(slideOutVertically { h -> -h / 2 } + fadeOut(tween(150)))
                        },
                        label = "headerDriveNameAnimation"
                    ) { driveName ->
                        Text(
                            text = driveName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
                    .border(
                        1.dp,
                        if (darkTheme) Lavender200.copy(alpha = 0.24f) else Violet600.copy(alpha = 0.24f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.testTag("toggle_theme_button")
                ) {
                    AnimatedContent(
                        targetState = darkTheme,
                        transitionSpec = {
                            (scaleIn(
                                initialScale = 0.55f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                            ) + fadeIn())
                                .togetherWith(scaleOut(targetScale = 0.55f) + fadeOut())
                        },
                        label = "themeIconTransition"
                    ) { isDark ->
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = "Toggle Theme",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        }
    }
}

private fun createFrostedGrainBrush(darkTheme: Boolean): Brush {
    val size = 64
    val pixels = IntArray(size * size)
    var seed = 0x5F3759DF
    val maxAlpha = if (darkTheme) 10 else 7
    val baseRgb = if (darkTheme) 0x00FFFFFF else 0x004C1D95
    for (i in pixels.indices) {
        // Fast deterministic LCG noise for subtle etched frosted-glass grain
        seed = seed * 1664525 + 1013904223
        val alpha = (seed ushr 24) % (maxAlpha + 1)
        pixels[i] = (alpha shl 24) or baseRgb
    }
    val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

@Composable
private fun ExpressiveFloatingBottomBar(
    currentTab: WorkbenchTab,
    darkTheme: Boolean,
    backdropLayer: GraphicsLayer?,
    legacyBlurSourceView: View?,
    grainBrush: Brush?,
    onSelectTab: (WorkbenchTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = darkTheme
    val renderEffectSupported = backdropLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val pillShape = remember { RoundedCornerShape(32.dp) }
    var pillPositionInRoot by remember { mutableStateOf(Offset.Zero) }

    val bottomVignetteBrush = remember(isDark) {
        val base = if (isDark) Cosmic950 else LilacCanvas
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                base.copy(alpha = 0.14f),
                base.copy(alpha = 0.34f)
            )
        )
    }

    val pillTintBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Cosmic900.copy(alpha = 0.42f),
                    Cosmic950.copy(alpha = 0.54f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    LilacCanvas.copy(alpha = 0.54f),
                    Color.White.copy(alpha = 0.44f)
                )
            )
        }
    }

    val pillSheenBrush = remember(isDark) {
        Brush.horizontalGradient(
            colors = if (isDark) {
                listOf(
                    Lavender300.copy(alpha = 0.12f),
                    Color.Transparent,
                    Lavender200.copy(alpha = 0.08f),
                    Lavender300.copy(alpha = 0.10f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.50f),
                    Lavender200.copy(alpha = 0.18f),
                    Color.White.copy(alpha = 0.42f)
                )
            }
        )
    }

    val pillBorderBrush = remember(isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    Color.White.copy(alpha = 0.32f),
                    Lavender200.copy(alpha = 0.20f),
                    Lavender300.copy(alpha = 0.14f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.85f),
                    Violet600.copy(alpha = 0.22f),
                    Violet600.copy(alpha = 0.16f)
                )
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bottomVignetteBrush)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    pillPositionInRoot = coords.positionInRoot()
                }
                .clip(pillShape)
                .border(1.dp, pillBorderBrush, pillShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // 1. Live Frosted Backdrop Blur clipped to the pill:
            //    - Android 12+ (API 31+): Hardware RenderEffect GraphicsLayer blur aligned via root offset
            //    - Android 11 & below (API <= 30): Sibling ComposeView strip capture + RenderScript ScriptIntrinsicBlur
            if (renderEffectSupported) {
                val liveBackdrop = backdropLayer!!
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(pillShape)
                        .blur(
                            radius = 18.dp,
                            edgeTreatment = BlurredEdgeTreatment.Rectangle
                        )
                        .drawBehind {
                            if (liveBackdrop.size.width > 0 && liveBackdrop.size.height > 0) {
                                translate(
                                    left = -pillPositionInRoot.x,
                                    top = -pillPositionInRoot.y
                                ) {
                                    drawLayer(liveBackdrop)
                                }
                            }
                        }
                        .testTag("rendereffect_blur_bottom_bar")
                )
            } else if (legacyBlurSourceView != null) {
                val fallbackColor = if (isDark) Cosmic950 else LilacCanvas
                LegacyRenderScriptBackdropBlur(
                    sourceView = legacyBlurSourceView,
                    fallbackBackgroundColor = fallbackColor,
                    refreshKey = Pair(currentTab, isDark),
                    cornerRadius = 32.dp,
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("legacy_renderscript_blur_bottom_bar")
                )
            }

            // 2. Translucent Frosted Glass Tint, Etched Micro-Grain & Refraction Sheen
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(pillTintBrush)
                    .then(if (grainBrush == null) Modifier else Modifier.background(grainBrush))
                    .background(pillSheenBrush)
            )

            // 3. Crisp Unblurred Navigation Items
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WorkbenchTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    val pillBgState = animateColorAsState(
                        targetValue = when {
                            selected && isDark -> Lavender300
                            selected && !isDark -> Violet600
                            else -> Color.Transparent
                        },
                        animationSpec = tween(170),
                        label = "navPillBg"
                    )
                    val contentTint by animateColorAsState(
                        targetValue = when {
                            selected && isDark -> Violet900
                            selected && !isDark -> Color.White
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(170),
                        label = "navPillFg"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .drawBehind {
                                val bg = pillBgState.value
                                if (bg.alpha > 0.001f) {
                                    drawRoundRect(
                                        color = bg,
                                        cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                                    )
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(color = MaterialTheme.colorScheme.primary),
                                onClick = { onSelectTab(tab) }
                            )
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                            .testTag("nav_tab_${tab.route}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (selected) filledIconForTab(tab) else outlinedIconForTab(tab),
                            contentDescription = tab.title,
                            tint = contentTint,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentTint,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveSideNavigationRail(
    currentTab: WorkbenchTab,
    onSelectTab: (WorkbenchTab) -> Unit,
    topPadding: Dp
) {
    Row(modifier = Modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = Color.Transparent,
            modifier = Modifier.padding(top = topPadding)
        ) {
            WorkbenchTab.entries.forEach { tab ->
                val selected = currentTab == tab
                NavigationRailItem(
                    selected = selected,
                    onClick = { onSelectTab(tab) },
                    icon = {
                        Icon(
                            imageVector = if (selected) filledIconForTab(tab) else outlinedIconForTab(tab),
                            contentDescription = tab.title
                        )
                    },
                    label = {
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("rail_tab_${tab.route}")
                )
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        )
    }
}

private fun filledIconForTab(tab: WorkbenchTab): ImageVector = when (tab) {
    WorkbenchTab.DRIVES -> Icons.Filled.Usb
    WorkbenchTab.FILES -> Icons.Filled.Folder
    WorkbenchTab.FORMAT -> Icons.Filled.Build
    WorkbenchTab.ISO_WRITER -> Icons.Filled.Album
    WorkbenchTab.PARTITIONS -> Icons.Filled.PieChart
}

private fun outlinedIconForTab(tab: WorkbenchTab): ImageVector = when (tab) {
    WorkbenchTab.DRIVES -> Icons.Outlined.Usb
    WorkbenchTab.FILES -> Icons.Outlined.FolderOpen
    WorkbenchTab.FORMAT -> Icons.Outlined.Build
    WorkbenchTab.ISO_WRITER -> Icons.Outlined.Album
    WorkbenchTab.PARTITIONS -> Icons.Outlined.PieChart
}

private fun queryUriNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var displayName = "imported_payload.bin"
    var size = 2L * 1024 * 1024
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    displayName = cursor.getString(nameIdx) ?: displayName
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
    }
    return displayName to size
}

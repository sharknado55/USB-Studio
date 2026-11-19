package com.example.util

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window

/**
 * Pure value representation of a display mode for deterministic selection and testing.
 */
data class RefreshRateModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float
)

/**
 * Selects the highest refresh rate mode (e.g., 90Hz, 120Hz, 144Hz, 165Hz) that matches the
 * display's current physical resolution so the app runs at peak native screen FPS without
 * altering display resolution on multi-resolution panels.
 */
fun selectOptimalRefreshRateMode(
    currentMode: RefreshRateModeSpec?,
    supportedModes: List<RefreshRateModeSpec>
): RefreshRateModeSpec? {
    if (supportedModes.isEmpty()) return currentMode
    val sameResolutionModes = if (currentMode != null) {
        supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
    } else {
        emptyList()
    }
    val candidates = if (sameResolutionModes.isNotEmpty()) sameResolutionModes else supportedModes
    return candidates.maxByOrNull { it.refreshRate } ?: currentMode
}

/**
 * Locks the Activity window onto the display's highest supported native refresh rate (90Hz, 120Hz,
 * 144Hz, etc.), configures Android 15+ Adaptive Refresh Rate (ARR) touch boost and frame-rate
 * hints, and dynamically re-applies when display modes change (e.g., foldables or Quick Settings).
 */
class DisplayRefreshRateController(private val activity: Activity) {

    private val displayManager: DisplayManager? =
        activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val activeDisplayId = resolveDisplay()?.displayId ?: return
            if (displayId == activeDisplayId) {
                applyOptimalRefreshRate()
            }
        }
    }

    fun start() {
        applyOptimalRefreshRate()
        if (!isRegistered) {
            runCatching {
                displayManager?.registerDisplayListener(displayListener, mainHandler)
                isRegistered = true
            }
        }
    }

    fun stop() {
        if (isRegistered) {
            runCatching {
                displayManager?.unregisterDisplayListener(displayListener)
            }
            isRegistered = false
        }
    }

    fun applyOptimalRefreshRate(): Float {
        val window: Window = activity.window ?: return 60f
        val display = resolveDisplay() ?: return 60f

        val currentMode = display.mode?.toSpec()
        val supportedModes = display.supportedModes?.map { it.toSpec() }.orEmpty()
        val bestMode = selectOptimalRefreshRateMode(currentMode, supportedModes)
        val targetHz = bestMode?.refreshRate ?: display.refreshRate.coerceAtLeast(60f)

        val attrs = window.attributes
        var changed = false
        if (bestMode != null && attrs.preferredDisplayModeId != bestMode.modeId) {
            attrs.preferredDisplayModeId = bestMode.modeId
            changed = true
        }
        if (attrs.preferredRefreshRate != targetHz) {
            attrs.preferredRefreshRate = targetHz
            changed = true
        }
        if (changed) {
            window.attributes = attrs
        }

        // Android 15+ (API 35) Adaptive Refresh Rate (ARR) & Touch Boost configuration:
        // Ensure touch interactions boost immediately and UI animations run at native screen Hz.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                window.setFrameRateBoostOnTouchEnabled(true)
                window.setFrameRatePowerSavingsBalanced(false)
                window.decorView.requestedFrameRate =
                    if (targetHz > 60f) targetHz else View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            }
        }

        return targetHz
    }

    @Suppress("DEPRECATION")
    private fun resolveDisplay(): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activity.display }.getOrNull()
                ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            activity.windowManager?.defaultDisplay
        }
    }

    private fun Display.Mode.toSpec(): RefreshRateModeSpec = RefreshRateModeSpec(
        modeId = modeId,
        physicalWidth = physicalWidth,
        physicalHeight = physicalHeight,
        refreshRate = refreshRate
    )
}

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
 * Selects the power-saving idle mode: the same-resolution mode closest to [idleHz] (60 Hz),
 * preferring a rate at or above it so the UI never drops below 60 FPS.
 */
fun selectIdleRefreshRateMode(
    currentMode: RefreshRateModeSpec?,
    supportedModes: List<RefreshRateModeSpec>,
    idleHz: Float = 60f
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
    val atOrAbove = candidates.filter { it.refreshRate >= idleHz - 0.5f }
    return (atOrAbove.ifEmpty { candidates }).minByOrNull { kotlin.math.abs(it.refreshRate - idleHz) }
        ?: currentMode
}

/**
 * Runs the Activity window at the display's highest supported native refresh rate (90Hz, 120Hz,
 * 144Hz, etc.) while the user is interacting, and drops to 60 Hz after [idleTimeoutMs] without
 * touch or key input, or while the app is paused. Configures Android 15+ Adaptive Refresh Rate
 * (ARR) touch boost and frame-rate hints, and re-applies when display modes change.
 */
class DisplayRefreshRateController(
    private val activity: Activity,
    private val idleTimeoutMs: Long = 5_000L
) {

    private val displayManager: DisplayManager? =
        activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRegistered = false

    /** True while the app is inactive (no recent input, or paused): run at 60 Hz to save power. */
    var isIdle: Boolean = false
        private set

    private val enterIdleRunnable = Runnable { setIdle(true) }

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
        isIdle = false
        applyOptimalRefreshRate()
        scheduleIdle()
        if (!isRegistered) {
            runCatching {
                displayManager?.registerDisplayListener(displayListener, mainHandler)
                isRegistered = true
            }
        }
    }

    /** Call on every touch or key event: returns to the highest rate and restarts the idle timer. */
    fun onUserInteraction() {
        if (isIdle) setIdle(false)
        scheduleIdle()
    }

    /** Call from onPause: the app is not in use, so drop to 60 Hz right away. */
    fun onInactive() {
        mainHandler.removeCallbacks(enterIdleRunnable)
        setIdle(true)
    }

    private fun scheduleIdle() {
        mainHandler.removeCallbacks(enterIdleRunnable)
        mainHandler.postDelayed(enterIdleRunnable, idleTimeoutMs)
    }

    private fun setIdle(idle: Boolean) {
        if (isIdle == idle) return
        isIdle = idle
        applyOptimalRefreshRate()
    }

    fun stop() {
        mainHandler.removeCallbacks(enterIdleRunnable)
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
        val bestMode = if (isIdle) {
            selectIdleRefreshRateMode(currentMode, supportedModes)
        } else {
            selectOptimalRefreshRateMode(currentMode, supportedModes)
        }
        val targetHz = bestMode?.refreshRate ?: if (isIdle) 60f else display.refreshRate.coerceAtLeast(60f)

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
                // Idle: let the system save power. Active: keep the native maximum.
                window.setFrameRatePowerSavingsBalanced(isIdle)
                window.decorView.requestedFrameRate = when {
                    isIdle -> View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL
                    targetHz > 60f -> targetHz
                    else -> View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                }
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

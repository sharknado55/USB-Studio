package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.view.ViewOutlineProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Isolates the scrollable background + content layer into its own sibling [ComposeView] on
 * Android 11 (API 30) and below.
 *
 * Because the floating top bar lives outside this view hierarchy, [LegacyRenderScriptBackdropBlur]
 * can snapshot and blur ONLY this content view without ever hiding, skipping, or invalidating the
 * top bar's hardware RenderNode display list.
 */
@Composable
fun LegacyBlurSourceContainer(
    onSourceViewReady: (View) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val parentContext = rememberCompositionContext()
    val currentContentState = rememberUpdatedState(content)
    val onReadyState = rememberUpdatedState(onSourceViewReady)

    AndroidView(
        factory = { ctx ->
            ComposeView(ctx).apply {
                setParentCompositionContext(parentContext)
                setContent {
                    currentContentState.value()
                }
                onReadyState.value(this)
            }
        },
        update = { view ->
            onReadyState.value(view)
        },
        modifier = modifier
    )
}

/**
 * Real live frosted backdrop blur for Android 11 (API 30) and below.
 *
 * Samples ONLY the top strip of [sourceView] (the sibling [LegacyBlurSourceContainer]) at 1/6 scale,
 * blurs it using Android's GPU-accelerated RenderScript [ScriptIntrinsicBlur] (with a fast
 * pure-Kotlin separable Gaussian-approximation box blur fallback if RenderScript is unavailable),
 * and draws the blurred texture with bilinear filtering behind the floating top header.
 */
@Composable
fun LegacyRenderScriptBackdropBlur(
    sourceView: View?,
    fallbackBackgroundColor: Color,
    refreshKey: Any,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    blurRadius: Float = 11f,
    downscaleFactor: Float = 6f
) {
    if (sourceView == null) return
    val fallbackArgb = fallbackBackgroundColor.toArgb()
    val cornerRadiusPx = with(LocalDensity.current) { cornerRadius.toPx() }

    AndroidView(
        factory = { context ->
            RenderScriptBackdropBlurView(
                context = context,
                blurRadius = blurRadius,
                downscaleFactor = downscaleFactor
            ).apply {
                setSourceView(sourceView)
                setFallbackBackgroundColor(fallbackArgb)
                setCornerRadiusPx(cornerRadiusPx)
            }
        },
        update = { blurView ->
            blurView.setSourceView(sourceView)
            blurView.setFallbackBackgroundColor(fallbackArgb)
            blurView.setCornerRadiusPx(cornerRadiusPx)
            blurView.onRefreshKeyChanged(refreshKey)
        },
        modifier = modifier
    )
}

internal class RenderScriptBackdropBlurView(
    context: Context,
    private val blurRadius: Float = 11f,
    private val downscaleFactor: Float = 6f
) : View(context) {

    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val clipRectF = RectF()
    private val clipPath = Path()
    private val sourceLoc = IntArray(2)
    private val selfLoc = IntArray(2)

    private var sourceView: View? = null
    private var fallbackBgColor: Int = android.graphics.Color.BLACK
    private var cornerRadiusPx: Float = 0f
    private var lastRefreshKey: Any? = null

    private var inputBitmap: Bitmap? = null
    private var outputBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var pixelScratchA: IntArray? = null
    private var pixelScratchB: IntArray? = null

    @Suppress("DEPRECATION")
    private var renderScript: RenderScript? = null
    @Suppress("DEPRECATION")
    private var blurScript: ScriptIntrinsicBlur? = null
    @Suppress("DEPRECATION")
    private var allocIn: Allocation? = null
    @Suppress("DEPRECATION")
    private var allocOut: Allocation? = null
    private var renderScriptFailed: Boolean = false

    private var isCapturing: Boolean = false
    private var lastCaptureTimeMs: Long = 0L
    private var lastSampleHash: Long = Long.MIN_VALUE
    private var skipNextSelfPreDraw: Boolean = false
    private var hasBlurredFrame: Boolean = false
    private var listenerAttached: Boolean = false
    private var forceNextCapture: Boolean = true

    private val trailingSettleRunnable = Runnable {
        if (isAttachedToWindow && width > 0 && height > 0) {
            captureAndBlur(force = true)
        }
    }

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (isCapturing || width <= 0 || height <= 0 || visibility != VISIBLE) {
            return@OnPreDrawListener true
        }
        if (skipNextSelfPreDraw) {
            skipNextSelfPreDraw = false
            return@OnPreDrawListener true
        }

        val now = SystemClock.uptimeMillis()
        removeCallbacks(trailingSettleRunnable)
        if (forceNextCapture || !hasBlurredFrame || (now - lastCaptureTimeMs) >= MIN_CAPTURE_INTERVAL_MS) {
            captureAndBlur(force = forceNextCapture || !hasBlurredFrame)
        } else {
            postDelayed(trailingSettleRunnable, SETTLE_DELAY_MS)
        }
        true
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    fun setSourceView(view: View) {
        if (sourceView !== view) {
            detachPreDrawListener()
            sourceView = view
            forceNextCapture = true
            lastSampleHash = Long.MIN_VALUE
            if (isAttachedToWindow) {
                attachPreDrawListener()
                post(trailingSettleRunnable)
            }
        }
    }

    fun setFallbackBackgroundColor(argb: Int) {
        if (fallbackBgColor != argb) {
            fallbackBgColor = argb
            forceNextCapture = true
            lastSampleHash = Long.MIN_VALUE
            if (isAttachedToWindow && width > 0 && height > 0) {
                removeCallbacks(trailingSettleRunnable)
                post(trailingSettleRunnable)
            }
        }
    }

    fun setCornerRadiusPx(radiusPx: Float) {
        val clamped = radiusPx.coerceAtLeast(0f)
        if (cornerRadiusPx != clamped) {
            cornerRadiusPx = clamped
            if (clamped > 0f) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                    }
                }
                clipToOutline = true
            } else {
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = false
            }
            updateClipPath(width, height)
            invalidateOutline()
            invalidate()
        }
    }

    fun onRefreshKeyChanged(key: Any) {
        if (lastRefreshKey != key) {
            lastRefreshKey = key
            forceNextCapture = true
            if (isAttachedToWindow && width > 0 && height > 0) {
                removeCallbacks(trailingSettleRunnable)
                post(trailingSettleRunnable)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachPreDrawListener()
        forceNextCapture = true
        post(trailingSettleRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(trailingSettleRunnable)
        detachPreDrawListener()
        releaseResources()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            updateClipPath(w, h)
            releaseBitmapBuffers()
            forceNextCapture = true
            if (w > 0 && h > 0 && isAttachedToWindow) {
                post(trailingSettleRunnable)
            }
        }
    }

    private fun updateClipPath(w: Int, h: Int) {
        clipPath.reset()
        if (cornerRadiusPx > 0f && w > 0 && h > 0) {
            clipRectF.set(0f, 0f, w.toFloat(), h.toFloat())
            clipPath.addRoundRect(clipRectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val blurred = outputBitmap
        if (blurred != null && !blurred.isRecycled && hasBlurredFrame) {
            srcRect.set(0, 0, blurred.width, blurred.height)
            dstRect.set(0, 0, width, height)
            if (cornerRadiusPx > 0f) {
                val save = canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawBitmap(blurred, srcRect, dstRect, drawPaint)
                canvas.restoreToCount(save)
            } else {
                canvas.drawBitmap(blurred, srcRect, dstRect, drawPaint)
            }
        }
    }

    private fun attachPreDrawListener() {
        val target = sourceView ?: return
        if (!listenerAttached && target.viewTreeObserver.isAlive) {
            target.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            listenerAttached = true
        }
    }

    private fun detachPreDrawListener() {
        val target = sourceView
        if (listenerAttached && target != null && target.viewTreeObserver.isAlive) {
            target.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
        listenerAttached = false
    }

    private fun ensureBuffers(viewWidth: Int, viewHeight: Int): Boolean {
        val scaledW = (viewWidth / downscaleFactor).toInt().coerceAtLeast(2)
        val scaledH = (viewHeight / downscaleFactor).toInt().coerceAtLeast(2)
        val existing = inputBitmap
        if (existing != null && !existing.isRecycled && existing.width == scaledW && existing.height == scaledH) {
            return true
        }

        releaseBitmapBuffers()

        return try {
            val inBmp = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
            val outBmp = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
            inputBitmap = inBmp
            outputBitmap = outBmp
            captureCanvas = Canvas(inBmp)
            pixelScratchA = IntArray(scaledW * scaledH)
            pixelScratchB = IntArray(scaledW * scaledH)
            ensureRenderScriptAllocations(inBmp)
            true
        } catch (_: Throwable) {
            releaseBitmapBuffers()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureRenderScriptAllocations(inBmp: Bitmap) {
        if (renderScriptFailed) return
        try {
            if (renderScript == null) {
                val rs = RenderScript.create(context.applicationContext)
                val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                script.setRadius(blurRadius.coerceIn(1f, 25f))
                renderScript = rs
                blurScript = script
            }
            val rs = renderScript ?: return
            allocIn?.destroy()
            allocOut?.destroy()
            val newIn = Allocation.createFromBitmap(
                rs,
                inBmp,
                Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_SCRIPT
            )
            allocIn = newIn
            allocOut = Allocation.createTyped(rs, newIn.type)
        } catch (_: Throwable) {
            renderScriptFailed = true
            destroyRenderScript()
        }
    }

    private fun captureAndBlur(force: Boolean) {
        val source = sourceView ?: return
        if (isCapturing || !source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return
        if (width <= 0 || height <= 0) return
        if (!ensureBuffers(width, height)) return

        val inBmp = inputBitmap ?: return
        val outBmp = outputBitmap ?: return
        val canvas = captureCanvas ?: return

        isCapturing = true
        try {
            inBmp.eraseColor(fallbackBgColor)

            source.getLocationInWindow(sourceLoc)
            getLocationInWindow(selfLoc)
            val offsetX = (selfLoc[0] - sourceLoc[0]).toFloat()
            val offsetY = (selfLoc[1] - sourceLoc[1]).toFloat()
            val scaleX = inBmp.width.toFloat() / width.toFloat()
            val scaleY = inBmp.height.toFloat() / height.toFloat()

            val saveCount = canvas.save()
            try {
                canvas.scale(scaleX, scaleY)
                canvas.translate(-offsetX, -offsetY)
                canvas.clipRect(offsetX, offsetY, offsetX + width, offsetY + height)
                source.draw(canvas)
            } finally {
                canvas.restoreToCount(saveCount)
            }
        } catch (_: Throwable) {
            return
        } finally {
            isCapturing = false
        }

        lastCaptureTimeMs = SystemClock.uptimeMillis()
        forceNextCapture = false

        val sampleHash = computeFastBitmapFingerprint(inBmp)
        if (!force && hasBlurredFrame && sampleHash == lastSampleHash) {
            return
        }
        lastSampleHash = sampleHash

        val blurredWithRs = runRenderScriptBlur(inBmp, outBmp)
        if (!blurredWithRs) {
            runFallbackSoftwareBlur(inBmp, outBmp)
        }

        hasBlurredFrame = true
        skipNextSelfPreDraw = true
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun runRenderScriptBlur(inBmp: Bitmap, outBmp: Bitmap): Boolean {
        if (renderScriptFailed) return false
        val script = blurScript ?: return false
        val inAlloc = allocIn ?: return false
        val outAlloc = allocOut ?: return false
        return try {
            inAlloc.copyFrom(inBmp)
            script.setInput(inAlloc)
            script.forEach(outAlloc)
            outAlloc.copyTo(outBmp)
            true
        } catch (_: Throwable) {
            renderScriptFailed = true
            destroyRenderScript()
            false
        }
    }

    private fun runFallbackSoftwareBlur(inBmp: Bitmap, outBmp: Bitmap) {
        val w = inBmp.width
        val h = inBmp.height
        val bufA = pixelScratchA ?: return
        val bufB = pixelScratchB ?: return
        if (bufA.size < w * h || bufB.size < w * h) return

        inBmp.getPixels(bufA, 0, w, 0, 0, w, h)
        val radius = blurRadius.toInt().coerceIn(2, 16)
        applyFastGaussianBoxBlur(bufA, bufB, w, h, radius)
        outBmp.setPixels(bufA, 0, w, 0, 0, w, h)
    }

    private fun computeFastBitmapFingerprint(bitmap: Bitmap): Long {
        val w = bitmap.width
        val h = bitmap.height
        val stepX = (w / 8).coerceAtLeast(1)
        val stepY = (h / 4).coerceAtLeast(1)
        var hash = 1125899906842597L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                hash = hash * 31L + bitmap.getPixel(x, y).toLong()
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun releaseBitmapBuffers() {
        @Suppress("DEPRECATION")
        runCatching { allocIn?.destroy() }
        @Suppress("DEPRECATION")
        runCatching { allocOut?.destroy() }
        allocIn = null
        allocOut = null
        captureCanvas = null
        inputBitmap?.recycle()
        outputBitmap?.recycle()
        inputBitmap = null
        outputBitmap = null
        pixelScratchA = null
        pixelScratchB = null
        hasBlurredFrame = false
        lastSampleHash = Long.MIN_VALUE
    }

    @Suppress("DEPRECATION")
    private fun destroyRenderScript() {
        runCatching { allocIn?.destroy() }
        runCatching { allocOut?.destroy() }
        runCatching { blurScript?.destroy() }
        runCatching { renderScript?.destroy() }
        allocIn = null
        allocOut = null
        blurScript = null
        renderScript = null
    }

    private fun releaseResources() {
        releaseBitmapBuffers()
        destroyRenderScript()
    }

    companion object {
        // Backdrop sampling shares the UI thread with the 90 Hz transitions.
        private const val MIN_CAPTURE_INTERVAL_MS = 32L
        private const val SETTLE_DELAY_MS = 36L
    }
}

/**
 * Fast O(W*H) 2-pass separable box blur approximating a smooth Gaussian blur in-place.
 * Used as a zero-allocation fallback when RenderScript is unavailable (e.g., Robolectric JVM tests
 * or stripped custom ROMs).
 */
internal fun applyFastGaussianBoxBlur(
    pixels: IntArray,
    temp: IntArray,
    width: Int,
    height: Int,
    radius: Int
) {
    if (width <= 1 || height <= 1 || radius <= 0) return
    val clampedRadius = radius.coerceAtMost(maxOf(width, height) - 1)
    if (clampedRadius <= 0) return

    repeat(2) {
        val windowSize = clampedRadius * 2 + 1
        for (y in 0 until height) {
            val rowOffset = y * width
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -clampedRadius..clampedRadius) {
                val sampleX = k.coerceIn(0, width - 1)
                val c = pixels[rowOffset + sampleX]
                rSum += (c shr 16) and 0xFF
                gSum += (c shr 8) and 0xFF
                bSum += c and 0xFF
            }
            for (x in 0 until width) {
                temp[rowOffset + x] = (0xFF shl 24) or
                    ((rSum / windowSize) shl 16) or
                    ((gSum / windowSize) shl 8) or
                    (bSum / windowSize)

                val removeX = (x - clampedRadius).coerceIn(0, width - 1)
                val addX = (x + clampedRadius + 1).coerceIn(0, width - 1)
                val cOut = pixels[rowOffset + removeX]
                val cIn = pixels[rowOffset + addX]
                rSum += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                gSum += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                bSum += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }

        for (x in 0 until width) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (k in -clampedRadius..clampedRadius) {
                val sampleY = k.coerceIn(0, height - 1)
                val c = temp[sampleY * width + x]
                rSum += (c shr 16) and 0xFF
                gSum += (c shr 8) and 0xFF
                bSum += c and 0xFF
            }
            for (y in 0 until height) {
                pixels[y * width + x] = (0xFF shl 24) or
                    ((rSum / windowSize) shl 16) or
                    ((gSum / windowSize) shl 8) or
                    (bSum / windowSize)

                val removeY = (y - clampedRadius).coerceIn(0, height - 1)
                val addY = (y + clampedRadius + 1).coerceIn(0, height - 1)
                val cOut = temp[removeY * width + x]
                val cIn = temp[addY * width + x]
                rSum += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                gSum += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                bSum += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }
}

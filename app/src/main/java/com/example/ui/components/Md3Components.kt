package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.Coral400
import com.example.ui.theme.Coral600
import com.example.ui.theme.Coral950
import com.example.ui.theme.Cosmic950
import com.example.ui.theme.ExpressiveProgressBrush
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.Lavender100
import com.example.ui.theme.Lavender300
import com.example.ui.theme.Violet500
import com.example.ui.theme.Violet600
import com.example.ui.theme.Violet900
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ============================================================================
// Material 3 Expressive Custom Organic Shapes (Precomputed Unit-Vector Tables)
// ============================================================================

private const val ORGANIC_SHAPE_STEPS = 72

private fun buildUnitBurstTable(lobes: Int, amplitude: Float): Pair<FloatArray, FloatArray> {
    val unitX = FloatArray(ORGANIC_SHAPE_STEPS + 1)
    val unitY = FloatArray(ORGANIC_SHAPE_STEPS + 1)
    val scaleFactor = 1f - amplitude
    for (i in 0..ORGANIC_SHAPE_STEPS) {
        val theta = (i.toDouble() / ORGANIC_SHAPE_STEPS.toDouble()) * 2.0 * PI
        val r = scaleFactor * (1.0 + amplitude * cos(lobes * theta))
        unitX[i] = (r * cos(theta)).toFloat()
        unitY[i] = (r * sin(theta)).toFloat()
    }
    return unitX to unitY
}

private val ScallopUnitVectors = buildUnitBurstTable(lobes = 12, amplitude = 0.085f)
private val CloverUnitVectors = buildUnitBurstTable(lobes = 4, amplitude = 0.14f)

private fun buildBurstPath(
    unitVectors: Pair<FloatArray, FloatArray>,
    cx: Float,
    cy: Float,
    radius: Float
): Path {
    val (ux, uy) = unitVectors
    return Path().apply {
        moveTo(cx + ux[0] * radius, cy + uy[0] * radius)
        for (i in 1..ORGANIC_SHAPE_STEPS) {
            lineTo(cx + ux[i] * radius, cy + uy[i] * radius)
        }
        close()
    }
}

// ============================================================================
// Expressive Atmospheric Background (Cached GPU Shaders + Batched Halftone Paths)
// ============================================================================

@Composable
fun ExpressiveAtmosphereBackground(
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    showAtmosphere: Boolean = true,
    content: @Composable () -> Unit
) {
    val bgColor = MaterialTheme.colorScheme.background
    val orbPrimary = if (darkTheme) Color(0xFF4C2A9B).copy(alpha = 0.34f) else Color(0xFFC4B0FF).copy(alpha = 0.45f)
    val orbSecondary = if (darkTheme) Color(0xFF7B3FB8).copy(alpha = 0.24f) else Color(0xFFF3B8FF).copy(alpha = 0.35f)
    val dotColor = if (darkTheme) Lavender300.copy(alpha = 0.085f) else Violet500.copy(alpha = 0.085f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .drawWithCache {
                if (!showAtmosphere) return@drawWithCache onDrawBehind {}
                // Pre-allocate radial glow shaders and batched halftone paths ONCE per size/theme
                val primaryCenter = Offset(size.width * 0.88f, size.height * 0.14f)
                val primaryRadius = (size.width * 0.72f).coerceAtLeast(1f)
                val primaryBrush = Brush.radialGradient(
                    colors = listOf(orbPrimary, Color.Transparent),
                    center = primaryCenter,
                    radius = primaryRadius
                )

                val secondaryCenter = Offset(size.width * 0.10f, size.height * 0.78f)
                val secondaryRadius = (size.width * 0.65f).coerceAtLeast(1f)
                val secondaryBrush = Brush.radialGradient(
                    colors = listOf(orbSecondary, Color.Transparent),
                    center = secondaryCenter,
                    radius = secondaryRadius
                )

                // Batch halftone dots into 3 alpha-bucketed Paths (3 GPU draw calls vs ~120)
                val bucketPaths = arrayOf(Path(), Path(), Path())
                val bucketColors = arrayOf(
                    dotColor.copy(alpha = dotColor.alpha * 0.55f),
                    dotColor.copy(alpha = dotColor.alpha * 0.88f),
                    dotColor.copy(alpha = dotColor.alpha * 1.22f)
                )
                val spacing = 18.dp.toPx().coerceAtLeast(8f)
                val startX = size.width * 0.48f
                val endY = size.height * 0.42f
                val invRx = 1f / (size.width * 0.42f).coerceAtLeast(1f)
                val invRy = 1f / (size.height * 0.26f).coerceAtLeast(1f)
                val cx = size.width * 0.85f
                val cy = size.height * 0.16f
                var y = spacing
                while (y < endY) {
                    var x = startX
                    while (x < size.width) {
                        val dx = (x - cx) * invRx
                        val dy = (y - cy) * invRy
                        val distSq = dx * dx + dy * dy
                        if (distSq < 1.0f) {
                            val factor = (1f - distSq).coerceIn(0f, 1f)
                            val r = (1.6f + 1.8f * factor).dp.toPx()
                            val bucket = when {
                                factor < 0.33f -> 0
                                factor < 0.66f -> 1
                                else -> 2
                            }
                            bucketPaths[bucket].addOval(
                                androidx.compose.ui.geometry.Rect(
                                    left = x - r,
                                    top = y - r,
                                    right = x + r,
                                    bottom = y + r
                                )
                            )
                        }
                        x += spacing
                    }
                    y += spacing
                }

                onDrawBehind {
                    drawCircle(brush = primaryBrush, radius = primaryRadius, center = primaryCenter)
                    drawCircle(brush = secondaryBrush, radius = secondaryRadius, center = secondaryCenter)
                    for (b in 0..2) {
                        drawPath(path = bucketPaths[b], color = bucketColors[b])
                    }
                }
            }
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

// ============================================================================
// Expressive Scalloped Emblem Badge (100% GPU drawPath — Zero clipPath Fallback)
// ============================================================================

@Composable
fun ExpressiveScallopEmblem(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    useCloverInner: Boolean = false,
    outerColor: Color = MaterialTheme.colorScheme.primary,
    innerColor: Color = MaterialTheme.colorScheme.onPrimary,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val cx = this.size.width * 0.5f
                val cy = this.size.height * 0.5f
                val outerRadius = min(this.size.width, this.size.height) * 0.5f
                val outerPath = buildBurstPath(ScallopUnitVectors, cx, cy, outerRadius)
                val innerSize = min(this.size.width, this.size.height) * 0.60f
                val innerCloverPath = if (useCloverInner) {
                    buildBurstPath(CloverUnitVectors, cx, cy, innerSize * 0.5f)
                } else {
                    null
                }
                val innerTopLeft = Offset(cx - innerSize * 0.5f, cy - innerSize * 0.5f)
                val innerRectSize = Size(innerSize, innerSize)
                val innerCorner = CornerRadius(14.dp.toPx(), 14.dp.toPx())

                onDrawBehind {
                    drawPath(path = outerPath, color = outerColor)
                    if (innerCloverPath != null) {
                        drawPath(path = innerCloverPath, color = innerColor)
                    } else {
                        drawRoundRect(
                            color = innerColor,
                            topLeft = innerTopLeft,
                            size = innerRectSize,
                            cornerRadius = innerCorner
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(size * 0.33f)
        )
    }
}

// ============================================================================
// Expressive Highlight Pill Tag (Crisp High-Contrast Reference Style)
// ============================================================================

@Composable
fun ExpressiveHighlightPill(
    text: String,
    modifier: Modifier = Modifier,
    inverted: Boolean = true
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = when {
        inverted && isDark -> Lavender100
        inverted && !isDark -> Violet900
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        inverted && isDark -> Violet900
        inverted && !isDark -> Lavender100
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ============================================================================
// Core App Primitives (Rebuilt for Material 3 Expressive)
// ============================================================================

enum class Md3ButtonVariant {
    Default,
    GoldAccent,
    Secondary,
    Outline,
    Ghost,
    Destructive
}

enum class Md3BadgeVariant {
    Default,
    Secondary,
    Outline,
    Gold,
    Destructive
}

private val CardDefaultShape = RoundedCornerShape(24.dp)
private val CardAccentShape = RoundedCornerShape(28.dp)

@Composable
fun Md3Card(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    isHighlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(22.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val isAccent = isHighlighted || borderColor == MaterialTheme.colorScheme.primary || borderColor == Lavender300
    val shape = if (isAccent) CardAccentShape else CardDefaultShape

    val containerColor by animateColorAsState(
        targetValue = if (isAccent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        },
        animationSpec = tween(220),
        label = "m3eCardBg"
    )

    val strokeColor by animateColorAsState(
        targetValue = if (isAccent) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        },
        animationSpec = tween(220),
        label = "m3eCardBorder"
    )

    val interactiveModifier = if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scaleState = animateFloatAsState(
            targetValue = if (isPressed) 0.972f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "m3eCardScale"
        )
        modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(containerColor, shape)
            .border(if (isAccent) 1.75.dp else 1.dp, strokeColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(color = MaterialTheme.colorScheme.primary),
                onClick = onClick
            )
            .padding(contentPadding)
    } else {
        modifier
            .clip(shape)
            .background(containerColor, shape)
            .border(if (isAccent) 1.75.dp else 1.dp, strokeColor, shape)
            .padding(contentPadding)
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(modifier = interactiveModifier, content = content)
    }
}

@Composable
fun Md3Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: Md3ButtonVariant = Md3ButtonVariant.Default,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    testTag: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.955f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3eButtonScale"
    )

    // Expressive shape morph: full pill resting -> soft squircle when pressed!
    val cornerRadius by animateDpAsState(
        targetValue = if (enabled && isPressed) 16.dp else 26.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3eButtonCorner"
    )
    val shape = RoundedCornerShape(cornerRadius)

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            variant == Md3ButtonVariant.Default || variant == Md3ButtonVariant.GoldAccent ->
                if (isDark) Lavender300 else Violet600
            variant == Md3ButtonVariant.Secondary ->
                if (isDark) Lavender100 else MaterialTheme.colorScheme.primaryContainer
            variant == Md3ButtonVariant.Destructive -> Coral600
            else -> Color.Transparent
        },
        animationSpec = tween(200),
        label = "m3eBtnBg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            variant == Md3ButtonVariant.Default || variant == Md3ButtonVariant.GoldAccent ->
                if (isDark) Violet900 else Color.White
            variant == Md3ButtonVariant.Secondary -> Violet900
            variant == Md3ButtonVariant.Destructive -> Color.White
            variant == Md3ButtonVariant.Outline -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "m3eBtnFg"
    )

    val borderStroke = when {
        !enabled -> null
        variant == Md3ButtonVariant.Outline -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> null
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .minimumInteractiveComponentSize()
            .heightIn(min = if (compact) 48.dp else 54.dp)
            .let { if (testTag != null) it.testTag(testTag) else it }
            .clip(shape)
            .background(bgColor, shape)
            .let { mod ->
                if (borderStroke != null) {
                    mod.border(1.5.dp, borderStroke, shape)
                } else {
                    mod
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(color = contentColor),
                enabled = enabled,
                onClick = onClick
            )
            .padding(
                horizontal = if (compact) 14.dp else 18.dp,
                vertical = if (compact) 10.dp else 14.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
            }
            Text(
                text = text,
                color = contentColor,
                style = if (compact) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun Md3Badge(
    text: String,
    modifier: Modifier = Modifier,
    variant: Md3BadgeVariant = Md3BadgeVariant.Default
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg) = when (variant) {
        Md3BadgeVariant.Default,
        Md3BadgeVariant.Gold -> if (isDark) {
            Lavender100 to Violet900
        } else {
            Violet600 to Color.White
        }
        Md3BadgeVariant.Secondary -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
        Md3BadgeVariant.Outline -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) to MaterialTheme.colorScheme.primary
        Md3BadgeVariant.Destructive -> Coral950 to Coral400
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun Md3Input(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    testTag: String? = null
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        val textStyle = if (monospace) {
            MaterialTheme.typography.labelLarge.copy(
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
        }
        val shape = RoundedCornerShape(20.dp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .let { if (testTag != null) it.testTag(testTag) else it },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), shape)
                        .border(
                            1.5.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            shape
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = textStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                )
                            )
                        }
                        innerTextField()
                    }
                    if (trailingContent != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        trailingContent()
                    }
                }
            }
        )
    }
}

@Composable
fun Md3SelectChip(
    label: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    testTag: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.955f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3eChipScale"
    )

    val corner by animateDpAsState(
        targetValue = if (selected) 24.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "m3eChipCorner"
    )
    val shape = RoundedCornerShape(corner)

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val bgColor by animateColorAsState(
        targetValue = when {
            selected && isDark -> Lavender300
            selected && !isDark -> Violet600
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        },
        animationSpec = tween(220),
        label = "m3eChipBg"
    )

    val titleColor by animateColorAsState(
        targetValue = when {
            selected && isDark -> Violet900
            selected && !isDark -> Color.White
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(220),
        label = "m3eChipTitle"
    )

    val subtitleColor by animateColorAsState(
        targetValue = when {
            selected && isDark -> Violet900.copy(alpha = 0.78f)
            selected && !isDark -> Color.White.copy(alpha = 0.85f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(220),
        label = "m3eChipSub"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(bgColor, shape)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(color = titleColor),
                onClick = onClick
            )
            .let { if (testTag != null) it.testTag(testTag) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun Md3ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 14.dp
) {
    val animatedProgressState = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "m3eProgress"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // Pure GPU draw-phase progress rendering — zero composition or relayout at 90Hz/120Hz!
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawWithCache {
                val inset = 2.dp.toPx()
                val outerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                val innerHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                val innerRadius = CornerRadius(innerHeight * 0.5f, innerHeight * 0.5f)
                val maxInnerWidth = (size.width - inset * 2f).coerceAtLeast(1f)
                onDrawBehind {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = outerRadius
                    )
                    val fraction = animatedProgressState.value
                    if (fraction > 0.004f) {
                        val fillWidth = (maxInnerWidth * fraction)
                            .coerceIn(innerHeight, maxInnerWidth)
                        drawRoundRect(
                            brush = ExpressiveProgressBrush,
                            topLeft = Offset(inset, inset),
                            size = Size(fillWidth, innerHeight),
                            cornerRadius = innerRadius
                        )
                    }
                }
            }
    )
}

@Composable
fun Md3Alert(
    title: String,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    variant: Md3BadgeVariant = Md3BadgeVariant.Gold
) {
    val (bg, fg, border) = when (variant) {
        Md3BadgeVariant.Destructive -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        )
        else -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
    }

    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .border(1.25.dp, border, shape)
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .padding(18.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(fg.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Md3KeyValueRow(
    keyLabel: String,
    valueText: String,
    monospaceValue: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = keyLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = valueText,
            style = if (monospaceValue) {
                MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily)
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun Md3HexViewerDialog(
    title: String,
    subtitle: String,
    sectorData: ByteArray,
    baseOffset: Long = 0L,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(30.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 560.dp)
                .clip(shape)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), shape),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
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
                            icon = Icons.Outlined.Memory,
                            size = 42.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_hex_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close Hex Inspector",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(14.dp))

                val hexLines = remember(sectorData, baseOffset) {
                    formatHexDump(sectorData, baseOffset)
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Cosmic950)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "OFFSET    00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F   ASCII",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 11.sp,
                        color = Lavender300,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    hexLines.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Lavender100
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Md3Badge(
                        text = "${sectorData.size} BYTES",
                        variant = Md3BadgeVariant.Gold
                    )
                    Md3Button(
                        text = "Close",
                        onClick = onDismiss,
                        variant = Md3ButtonVariant.Secondary
                    )
                }
            }
        }
    }
}

fun formatHexDump(bytes: ByteArray, baseOffset: Long = 0L): List<String> {
    val lines = mutableListOf<String>()
    val limit = minOf(bytes.size, 512)
    var i = 0
    while (i < limit) {
        val offsetStr = String.format("%08X", baseOffset + i)
        val hexPart = StringBuilder()
        val asciiPart = StringBuilder()
        for (j in 0 until 16) {
            if (i + j < limit) {
                val b = bytes[i + j].toInt() and 0xFF
                hexPart.append(String.format("%02X ", b))
                val ch = if (b in 32..126) b.toChar() else '.'
                asciiPart.append(ch)
            } else {
                hexPart.append("   ")
                asciiPart.append(' ')
            }
            if (j == 7) hexPart.append(' ')
        }
        lines.add("$offsetStr  $hexPart |$asciiPart|")
        i += 16
    }
    return lines
}

@Composable
fun Md3NoDriveEmptyState(
    screenTitle: String,
    screenSubtitle: String,
    accentIcon: ImageVector = Icons.Filled.Usb,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onMountSafVolume: () -> Unit,
    onScanUsbBus: () -> Unit,
    topContentPadding: Dp = 14.dp,
    bottomContentPadding: Dp = 14.dp,
    testTag: String = "no_drive_empty_state",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = topContentPadding,
                bottom = bottomContentPadding
            )
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = screenTitle,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = screenSubtitle,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            ExpressiveScallopEmblem(
                icon = accentIcon,
                size = 66.dp,
                useCloverInner = true,
                outerColor = accentColor,
                innerColor = Violet900,
                iconTint = accentColor
            )
        }

        Md3Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveScallopEmblem(
                    icon = Icons.Filled.Usb,
                    size = 74.dp,
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
                    text = "Plug in a USB-C OTG flash drive or SD card reader, or mount an external storage folder to use this tool.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Md3Button(
                    text = "Mount External Drive / SD Card",
                    icon = Icons.Outlined.FolderOpen,
                    variant = Md3ButtonVariant.GoldAccent,
                    onClick = onMountSafVolume,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "${testTag}_mount_button"
                )
                Md3Button(
                    text = "Rescan USB Bus",
                    icon = Icons.Outlined.Refresh,
                    variant = Md3ButtonVariant.Outline,
                    onClick = onScanUsbBus,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "${testTag}_rescan_button"
                )
            }
        }
    }
}


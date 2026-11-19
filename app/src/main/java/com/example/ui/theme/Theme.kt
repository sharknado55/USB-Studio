package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = Lavender300,
    onPrimary = Violet900,
    primaryContainer = Violet700,
    onPrimaryContainer = Lavender100,
    secondary = OrchidPink,
    onSecondary = Violet900,
    secondaryContainer = Cosmic800,
    onSecondaryContainer = Lavender200,
    tertiary = FlamingoRose,
    onTertiary = Violet900,
    tertiaryContainer = Color(0xFF3E1832),
    onTertiaryContainer = FlamingoRose,
    background = Cosmic950,
    onBackground = Cosmic50,
    surface = Cosmic900,
    onSurface = Cosmic50,
    surfaceVariant = Cosmic850,
    onSurfaceVariant = Cosmic400,
    outline = Cosmic700,
    outlineVariant = Cosmic800,
    error = Coral400,
    onError = Violet900,
    errorContainer = Coral950,
    onErrorContainer = Coral400
)

private val ExpressiveLightColorScheme = lightColorScheme(
    primary = Violet600,
    onPrimary = Color.White,
    primaryContainer = Lavender200,
    onPrimaryContainer = Violet900,
    secondary = Violet500,
    onSecondary = Color.White,
    secondaryContainer = LilacSurfaceHigh,
    onSecondaryContainer = LilacInk,
    tertiary = Color(0xFFD9468F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0EE),
    onTertiaryContainer = Color(0xFF4A0E2B),
    background = LilacCanvas,
    onBackground = LilacInk,
    surface = LilacSurfaceLow,
    onSurface = LilacInk,
    surfaceVariant = LilacSurface,
    onSurfaceVariant = LilacInkMuted,
    outline = LilacOutline,
    outlineVariant = LilacSurfaceHigh,
    error = Coral600,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4EA),
    onErrorContainer = Color(0xFF5A0A1E)
)

// Expressive Material 3 Shapes — Big, Soft, Playful Geometry
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(26.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ExpressiveDarkColorScheme else ExpressiveLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpressiveShapes
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}

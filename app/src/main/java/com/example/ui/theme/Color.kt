package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ============================================================================
// Material 3 Expressive — Electric Lavender & Midnight Plum Palette
// ============================================================================

// Dark Mode Surfaces (Deep Midnight Plum / Violet-Black)
val Cosmic950 = Color(0xFF0A0812)
val Cosmic900 = Color(0xFF130F20)
val Cosmic850 = Color(0xFF1C172E)
val Cosmic800 = Color(0xFF26203D)
val Cosmic700 = Color(0xFF362E54)
val Cosmic400 = Color(0xFFABA1C9)
val Cosmic50 = Color(0xFFF8F5FF)

// Light Mode Surfaces (Airy Lilac Mist)
val LilacCanvas = Color(0xFFFAF8FF)
val LilacSurfaceLow = Color(0xFFF3EEFF)
val LilacSurface = Color(0xFFEBE3FF)
val LilacSurfaceHigh = Color(0xFFE0D5FF)
val LilacOutline = Color(0xFFD0C2F2)
val LilacInk = Color(0xFF1A1133)
val LilacInkMuted = Color(0xFF564978)

// Primary Expressive Lavender & Electric Iris
val Lavender100 = Color(0xFFF5F1FF)
val Lavender200 = Color(0xFFE5DAFF)
val Lavender300 = Color(0xFFD4C2FF) // Signature Scallop Badge & Hero Lavender
val Violet500 = Color(0xFF8B5CF6)   // Vibrant Iris Core
val Violet600 = Color(0xFF6D3FE3)
val Violet700 = Color(0xFF5228B8)
val Violet900 = Color(0xFF23104F)   // Deep Ink on Lavender

// Expressive Playful Secondary & Partition Accents
val OrchidPink = Color(0xFFF3B8FF)
val FlamingoRose = Color(0xFFFF9EC4)
val PeriwinkleSky = Color(0xFFA7C2FF)
val SoftPeach = Color(0xFFFFCBA4)

// Destructive / Warning Coral-Red
val Coral400 = Color(0xFFFF7A90)
val Coral600 = Color(0xFFE11D48)
val Coral950 = Color(0xFF360B18)

val ExpressiveProgressBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF8B5CF6),
        Color(0xFFD4C2FF),
        Color(0xFFF3B8FF)
    )
)

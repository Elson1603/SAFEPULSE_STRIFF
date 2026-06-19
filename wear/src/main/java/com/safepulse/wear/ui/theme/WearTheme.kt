package com.safepulse.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography

// SafePulse brand colors adapted for Wear OS
val PrimaryRed = Color(0xFFE53935)
val PrimaryRedDark = Color(0xFFB71C1C)
val SecondaryOrange = Color(0xFFFF7043)
val BackgroundDark = Color(0xFF000000) // Pure black for OLED
val SurfaceDark = Color(0xFF1A1A1A)

val RiskLow = Color(0xFF4CAF50)
val RiskMedium = Color(0xFFFF9800)
val RiskHigh = Color(0xFFF44336)

val SafeGreen = Color(0xFF00C853)
val WarningYellow = Color(0xFFFFD600)
val DangerRed = Color(0xFFFF1744)

val HeartRateRed = Color(0xFFEF5350)

private val WearColorPalette = Colors(
    primary = PrimaryRed,
    primaryVariant = PrimaryRedDark,
    secondary = SecondaryOrange,
    secondaryVariant = SecondaryOrange,
    background = BackgroundDark,
    surface = SurfaceDark,
    error = DangerRed,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color.White.copy(alpha = 0.7f),
    onError = Color.White
)

@Composable
fun SafePulseWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WearColorPalette,
        typography = Typography(),
        content = content
    )
}

package com.safepulse.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Colors
val PrimaryRed = Color(0xFFE53935)
val PrimaryRedDark = Color(0xFFB71C1C)
val SecondaryOrange = Color(0xFFFF7043)
val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val CardDark = Color(0xFF2D2D2D)

val RiskLow = Color(0xFF4CAF50)
val RiskMedium = Color(0xFFFF9800)
val RiskHigh = Color(0xFFF44336)

val SafeGreen = Color(0xFF00C853)
val WarningYellow = Color(0xFFFFD600)
val DangerRed = Color(0xFFFF1744)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryRed,
    onPrimary = Color.White,
    primaryContainer = PrimaryRedDark,
    onPrimaryContainer = Color.White,
    secondary = SecondaryOrange,
    onSecondary = Color.White,
    background = BackgroundDark,
    onBackground = Color.White,
    surface = SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = CardDark,
    onSurfaceVariant = Color.White.copy(alpha = 0.8f),
    error = DangerRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryRed,
    onPrimary = Color.White,
    primaryContainer = PrimaryRedDark,
    onPrimaryContainer = Color.White,
    secondary = SecondaryOrange,
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = DangerRed,
    onError = Color.White
)

@Composable
fun SafePulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

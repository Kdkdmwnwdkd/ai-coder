package com.xuedi.coder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = White,
    primaryContainer = AccentDim,
    onPrimaryContainer = DarkGray,
    secondary = DarkGray,
    onSecondary = White,
    secondaryContainer = VeryLightGray,
    onSecondaryContainer = DarkGray,
    tertiary = Accent,
    tertiaryContainer = AccentDim,
    surface = White,
    onSurface = Black,
    surfaceVariant = VeryLightGray,
    onSurfaceVariant = DarkGray,
    background = VeryLightGray,
    onBackground = Black,
    error = ErrorRed,
    onError = White,
    outline = LightGray,
    surfaceTint = Color.Transparent
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = White,
    primaryContainer = Color(0xFF334A5E),
    onPrimaryContainer = LightGray,
    secondary = LightGray,
    onSecondary = Black,
    secondaryContainer = Color(0xFF232427),
    surface = Color(0xFF18191B),
    onSurface = White,
    background = Color(0xFF121315),
    onBackground = LightGray,
    error = Color(0xFFF2B8B5),
    outline = Color(0xFF4A4B4F),
    surfaceTint = Color.Transparent
)

@Composable
fun AiCoderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
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
        typography = Typography,
        content = content
    )
}

package com.inkrealm.novel.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

private val InkRealmColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    secondary = SecondaryLight,
    onSecondary = OnPrimaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = OutlineLight,
    error = ErrorLight,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryLight
)

@Composable
fun InkRealmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InkRealmColorScheme,
        typography = Typography,
        content = content
    )
}

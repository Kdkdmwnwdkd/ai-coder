package com.xuedi.coder.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.xuedi.coder.App
import com.xuedi.coder.theme.ThemeMode

// ============================================================
// 自定义深色配色 —— 不跟 Material3 默认走，更贴近 TRAE/极简风
// 深色不是纯黑 #000，而是深灰 #121315，避免 OLED 屏刺眼
// primary 用蓝灰 #5C7C9A，浅深两模式下都保持一致的强调感
// ============================================================

// 浅色：蓝灰主强调 + 浅灰背景
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

// 自定义深色：深灰底 + 稍亮的卡片 + 蓝灰强调
private val DarkColors = darkColorScheme(
    primary = Accent,                    // 蓝灰 #5C7C9A 保持一致
    onPrimary = White,
    primaryContainer = Color(0xFF2A3F52), // 深一点的蓝灰容器
    onPrimaryContainer = Color(0xFFB8C9D9),
    secondary = Color(0xFFA7AAAF),
    onSecondary = Color(0xFF18191B),
    secondaryContainer = Color(0xFF232427),
    onSecondaryContainer = Color(0xFFE6E7EB),
    tertiary = Color(0xFF7C9AB5),
    tertiaryContainer = Color(0xFF233545),
    surface = Color(0xFF1A1B1E),          // 卡片背景（比底稍亮）
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF2A2B2E),
    onSurfaceVariant = Color(0xFFA7AAAF),
    background = Color(0xFF121315),        // 页面最深色
    onBackground = Color(0xFFE6E7EB),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    outline = Color(0xFF3A3B3F),
    surfaceTint = Color.Transparent
)

// 跟随系统时也用自定义深色，不用 Material3 默认黑底
private val SystemDarkColors = DarkColors

@Composable
fun AiCoderTheme(
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val themeStore = (ctx.applicationContext as App).themeStore
    val themeMode by themeStore.themeModeFlow.collectAsState(initial = ThemeMode.FOLLOW_SYSTEM)
    val systemDark = isSystemInDarkTheme()

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_SYSTEM -> systemDark
    }

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

package com.xuedi.coder.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 【新 M3 UI 层】全局"照片背景 + 透明度"盒子。
 *
 * 所有页面都包在它里面。M3 阶段：
 *   · 默认背景 URI = null → 显示 Material 纯白 background
 *   · 透明度 Slider 在设置页改的是 [uiAlpha]（UI模拟，先不持久化）
 *   · [backgroundUri] 也是 UI 层 remember 的（ThemeStore DataStore 持久化在 M4 接回）
 *
 * 真正的全局单例状态（backgroundUri / alpha）以后由 ThemeStore（DataStore）提供。
 */
object UiBackground {
    private val _uri = MutableStateFlow<String?>(null)
    val backgroundUri: StateFlow<String?> = _uri.asStateFlow()
    fun setUri(v: String?) { _uri.value = v }

    private val _alpha = MutableStateFlow(0.72f)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()
    fun setAlpha(v: Float) { _alpha.value = v.coerceIn(0f, 1f) }
}

@Composable
fun BackgroundContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // 从全局状态读（后面可以换成 ThemeStore 的 Flow.collectAsStateWithLifecycle）
    var uri: String? by remember { mutableStateOf<String?>(UiBackground.backgroundUri.value) }
    var alpha: Float by remember { mutableFloatStateOf(UiBackground.alpha.value) }

    // 监听全局变化（以后直接 collect）
    UiBackground.backgroundUri.let { flow ->
        androidx.compose.runtime.LaunchedEffect(Unit) {
            flow.collect { uri = it }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        UiBackground.alpha.collect { alpha = it }
    }

    val base = MaterialTheme.colorScheme.background
    Box(modifier = modifier.fillMaxSize()) {
        // 1. 照片底层（有 URI 就显示）
        if (!uri.isNullOrBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(base),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = alpha
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(base)
            )
        }

        // 2. 上面叠一层非常淡的渐变色（让纯白文字/按钮更可读）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            base.copy(alpha = if (uri == null) 1f else (1f - alpha) * 0.55f + 0.1f),
                            base.copy(alpha = if (uri == null) 1f else (1f - alpha) * 0.65f + 0.08f)
                        )
                    )
                )
        )

        // 3. 内容层（带 Material3 透明容器）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            content()
        }
    }
}

package com.xuedi.coder.ui.screen

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 【新 M3 UI 层】全局"照片背景 + 透明度"盒子。
 *
 * 所有页面都包在它里面：
 *   · 默认背景 URI = null → 显示 Material 纯白 background（恢复纯白 / 未选照片时）
 *   · 选照片后：底层照片按 [alpha]（照片不透明度 0..1）绘制，可透出到内容层；
 *     内容层的卡片 / 气泡 / 输入框 / 抽屉做成半透明，形成磨砂毛玻璃观感。
 *   · Android 12+ 用 RenderEffect 对照片做真实高斯模糊（毛玻璃），低版本降级为半透明。
 *
 * 全局单例状态由 ThemeStore（DataStore）经 App.kt 同步过来。
 */
object UiBackground {
    private val _uri = MutableStateFlow<String?>(null)
    val backgroundUri: StateFlow<String?> = _uri.asStateFlow()
    fun setUri(v: String?) { _uri.value = v }

    private val _alpha = MutableStateFlow(0.18f)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()
    fun setAlpha(v: Float) { _alpha.value = v.coerceIn(0f, 1f) }
}

@Composable
fun BackgroundContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var uri: String? by remember { mutableStateOf<String?>(UiBackground.backgroundUri.value) }
    var alpha: Float by remember { mutableFloatStateOf(UiBackground.alpha.value) }

    LaunchedEffect(Unit) {
        UiBackground.backgroundUri.collect { uri = it }
    }
    LaunchedEffect(Unit) {
        UiBackground.alpha.collect { alpha = it }
    }

    val base = MaterialTheme.colorScheme.background
    val hasPhoto = !uri.isNullOrBlank()
    val density = LocalDensity.current

    // 毛玻璃模糊半径：Android 12+ 才支持 RenderEffect，低版本仅做半透明降级。
    // 半径按密度换算（约 4.dp），太重会糊成一团、太轻看不出磨砂。
    val blurRadiusPx = remember(density) { with(density) { 4.dp.toPx() } }
    // API 31+ 才允许触碰 RenderEffect（类加载安全：低版本走不到这里）
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val photoRenderEffect: RenderEffect? = remember(hasPhoto, canBlur) {
        if (hasPhoto && canBlur) {
            // Compose 的 BlurEffect 是跨 API 安全封装：Android 12+ 走真实 GPU 高斯模糊，
            // 更低版本 isSupported=false 自动降级为无模糊（仍有半透明磨砂观感）。
            BlurEffect(
                radiusX = blurRadiusPx,
                radiusY = blurRadiusPx,
                edgeTreatment = TileMode.Mirror
            )
        } else null
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 照片底层（有 URI 就显示；无照片 → 纯白 Material background）
        if (hasPhoto) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 真实毛玻璃：只模糊背景照片这一层，内容层在上面保持清晰；
                        // 略放大 1.02，避免模糊后四周露出底色的细缝。
                        scaleX = 1.02f
                        scaleY = 1.02f
                        renderEffect = photoRenderEffect
                    },
                contentScale = ContentScale.Crop,
                alpha = alpha
            )
        } else {
            Box(Modifier.fillMaxSize().background(base))
        }

        // 2. 上面叠一层很淡的渐变色（保证文字/按钮在照片上仍可读，同时不遮照片太多）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            base.copy(alpha = if (hasPhoto) (1f - alpha) * 0.30f + 0.10f else 1f),
                            base.copy(alpha = if (hasPhoto) (1f - alpha) * 0.36f + 0.08f else 1f)
                        )
                    )
                )
        )

        // 3. 内容层（各页面的卡片/气泡在此之上叠加自己的半透明磨砂底色）
        Box(Modifier.fillMaxSize().background(Color.Transparent)) {
            content()
        }
    }
}

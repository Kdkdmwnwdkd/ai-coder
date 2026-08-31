package com.xuedi.coder.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xuedi.coder.App
import java.io.File

/**
 * 全局根容器：
 * - 没有照片背景：纯 MaterialTheme.background。
 * - 有照片：照片铺满 + alpha 半透明白/黑蒙层（照片越淡越不挡字）。
 * - 保证 UI 极简：无雪花、无装饰、无动画。
 */
@Composable
fun BackgroundContainer(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App

    val bgPath by produceState<String?>(initialValue = null, key1 = Unit) {
        app.themeStore.backgroundPathFlow.collect { value = it }
    }
    val alpha by produceState(initialValue = com.xuedi.coder.theme.ThemeStore.DEFAULT_BG_ALPHA, key1 = Unit) {
        app.themeStore.backgroundAlphaFlow.collect { value = it }
    }

    val file = bgPath?.takeIf { File(it).exists() }?.let { File(it) }
    val isDark = MaterialTheme.colorScheme.onBackground == Color.White

    Box(Modifier.fillMaxSize()) {
        // 基础底色
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        // 照片（只在有文件时显示）
        if (file != null) {
            val model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                .crossfade(false)
                .build()
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 不直接调 alpha，而是叠加蒙层；alpha 越大照片越淡
                    }
            )
            // 蒙层：白或黑，透明度由 alpha 控制，默认 0.18f（很淡）
            val maskColor = if (isDark) Color(0x00000000).copy(alpha = alpha)
            else Color.White.copy(alpha = alpha)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(maskColor, maskColor)))
            )
        }
        // 内容
        Box(Modifier.fillMaxSize()) { content() }
    }
}

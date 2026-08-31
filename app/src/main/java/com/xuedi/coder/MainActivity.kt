package com.xuedi.coder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.xuedi.coder.ui.theme.AiCoderTheme

/**
 * 【M1 最小骨架版本】—— 只有一个纯白极简的欢迎页。
 * 能在 GitHub Actions 上稳定编译出 Debug APK 后，再把聊天/插件/设置页加回来。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AiCoderTheme {
                MinimalHelloScreen()
            }
        }
    }
}

@Composable
private fun MinimalHelloScreen() {
    Scaffold(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AI 编程助手 · M1 工程骨架",
                fontSize = 20.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

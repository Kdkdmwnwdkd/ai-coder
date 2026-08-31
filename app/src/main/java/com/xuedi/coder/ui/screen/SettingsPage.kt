package com.xuedi.coder.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xuedi.coder.BuildConfig

/**
 * 【M3 UI 层】设置页。
 * M3 阶段：
 *  · 选择照片背景：会真的启动 SAF 相册 Intent，然后把 URI 写入 UiBackground（UI 层立刻能看到效果）
 *  · 选择 GGUF 模型：会启动 SAF 文档选择器（占位，选到不做任何处理，M4 真正接 ModelManager 时再复制进私有目录）
 *  · 透明度 Slider：改的是 UiBackground.alpha（UI 层全局立刻生效，照片透明度立刻变）
 */
@Composable
fun SettingsPage(
    currentBg: String?,
    currentAlpha: Float,
    setBg: (String?) -> Unit,
    setAlpha: (Float) -> Unit,
    requestImportModel: () -> Unit,
    requestImportBackground: () -> Unit
) {
    val ctx = LocalContext.current

    // SAF: 选照片（image/*）→ UI层直接生效
    val pickBgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 拿长期权限（否则重启后没权限读）
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val uriStr = uri.toString()
            setBg(uriStr)
            requestImportBackground()  // 回调占位
        }
    }

    // SAF: 选 GGUF / 任意文件（占位）
    val pickModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // M4 再真正处理：把这个 URI 流复制到私有目录 + 写 ModelEntity 进 Room + 启动 LlmEngine。
        requestImportModel()
    }

    var alphaLocal: Float by remember(currentAlpha) { mutableFloatStateOf(currentAlpha) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            "设置 · 外观 / 模型 / 背景",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        // ============== 卡片 1：背景照片 + 透明度 ==============
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("背景照片", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { pickBgLauncher.launch(arrayOf("image/*")) },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.padding(end = 6.dp))
                        Text("选择照片")
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 小预览图（当前背景）
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(64.dp)
                                .fillMaxWidth(0.42f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        ) {
                            if (!currentBg.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentBg)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    alpha = alphaLocal
                                )
                            } else {
                                Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("（纯白）", fontSize = 12.sp)
                                }
                            }
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("透明度", fontSize = 13.sp)
                            Text(
                                "${(alphaLocal * 100).toInt()}%",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Slider(
                    value = alphaLocal,
                    onValueChange = { alphaLocal = it },
                    onValueChangeFinished = { setAlpha(alphaLocal) },
                    valueRange = 0f..1f
                )

                if (currentBg != null) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { setBg(null) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("移除背景（恢复纯白）", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ============== 卡片 2：导入模型 ==============
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("本地 GGUF 模型", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "（M3 UI 演示版：选择文件只是占位。M4=管理层接回 ModelManager 时，会把 URI 文件" +
                        "复制进私有目录 + 写 Room + 校验 GGUF 头 + 后续启动真 JNI llama.cpp 推理。）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { pickModelLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Outlined.Source, null, Modifier.padding(end = 6.dp))
                    Text("选择 GGUF 模型文件")
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "当前构建：${BuildConfig.BUILD_TYPE} · v${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

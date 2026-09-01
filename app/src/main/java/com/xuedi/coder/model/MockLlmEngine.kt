package com.xuedi.coder.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * M3-M5 过渡用：模拟流式打字效果、模拟生成带代码块+ACTION标签的回答，
 * 保证 UI 能先跑通；M6 替换为真 JNI llama.cpp。
 */
class MockLlmEngine : LlmEngine {

    override fun chatFlow(system: String, user: String): Flow<ChatChunk> = flow {
        // 先"思考"一下再出字
        delay(250)
        if (user.contains("出错") || user.contains("error", ignoreCase = true)) {
            emit(ChatChunk.Error(RuntimeException("模拟错误：请检查输入或稍后再试。")))
            return@flow
        }
        val demo = buildDemoAnswer(user)
        val sb = StringBuilder()
        // 一个字符一个字符 emit，每 35ms 发一个；中文、标点、代码块里的换行同理，
        // 模拟真实模型流式输出节奏。
        demo.forEach { ch ->
            delay(35)
            sb.append(ch)
            emit(ChatChunk.Token(ch.toString()))
        }
        emit(ChatChunk.Done(sb.toString()))
    }

    override fun release() {}
    override fun cancel() { /* Mock 是纯协程 flow，chatFlow 的 collect 取消时 flow builder 自动 cancel，不用手动做 */ }

    private fun buildDemoAnswer(user: String): String {
        val q = user.trim()
        val hasAndroid = listOf("android", "compose", "安卓").any { q.contains(it, ignoreCase = true) }
        val hasPy = listOf("python", "爬虫", "pandas").any { q.contains(it, ignoreCase = true) }
        return when {
            hasAndroid -> androidDemo(q)
            hasPy -> pythonDemo(q)
            else -> genericDemo(q)
        }
    }

    private fun androidDemo(q: String): String = buildString {
        append("好的，下面是一个 ")
        if (q.contains("按钮") || q.contains("button", ignoreCase = true)) {
            append("带计数器按钮的极简 Compose 页面，纯白+Material3 扁平样式：\n\n")
            append("""```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CounterPage() {
    var count by remember { mutableIntStateOf(0) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("你点击了 ${'$'}count 次", style = MaterialTheme.typography.titleLarge)
        Button(onClick = { count++ }, Modifier.padding(top = 16.dp)) {
            Text("点我 +1")
        }
    }
}
```
""")
        } else {
            append("完整可运行的 Kotlin 代码示例（已带全部 import）：\n\n")
            append("```kotlin\nfun main() {\n    println(\"Hello, Jetpack Compose!\")\n}\n```\n")
        }
        append("\n使用方式：把上述代码复制到项目即可；复制后若需要直接放到剪贴板，我会在末尾附加 ACTION 标签。")
        append("\n\n <ACTION: copy_to_clipboard \"已经把上面的代码给你，如需要直接复制请点工具栏复制按钮。\">")
    }

    private fun pythonDemo(q: String): String = buildString {
        append("下面是一个极简的 Python3 脚本示例，requests 抓取页面标题（带 type hints 和 requirements）：\n\n")
        append("""```python
# requirements.txt
# httpx>=0.27.0
# beautifulsoup4>=4.12.3
from __future__ import annotations

import httpx
from bs4 import BeautifulSoup

def fetch_title(url: str, timeout: float = 10.0) -> str | None:
    with httpx.Client(timeout=timeout, follow_redirects=True) as client:
        r = client.get(url)
        r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    return soup.title.string if soup.title else None

if __name__ == "__main__":
    print(fetch_title("https://www.example.com"))
```
""")
        append("\n <ACTION: copy_to_clipboard \"上面的 Python 代码已生成\">")
    }

    private fun genericDemo(q: String): String = buildString {
        append("已收到你的问题：")
        append(q.take(60))
        append(if (q.length > 60) "…" else "")
        append("。\n\n现在是 Mock 推理阶段（UI 联调专用），接入真 llama.cpp 后会给出真正的本地推理结果。\n\n")
        append("这是一段可复制的代码示例：\n\n")
        append("```kotlin\n// 一个简单的工具函数示例\nfun fib(n: Int): Long = when (n) {\n    0 -> 0L\n    1 -> 1L\n    else -> fib(n - 1) + fib(n - 2)\n}\n```\n")
        append("\n提示：模型导入后在「设置」页选择并加载，返回聊天页即可本地推理。")
        append("\n\n <ACTION: show_toast \"Mock 推理演示完成\">")
    }
}

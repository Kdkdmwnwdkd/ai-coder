package com.xuedi.coder.plugin

import android.content.Context
import com.xuedi.coder.model.ChatPlugin

/**
 * AI 执行模式插件（纯声明式占位 + onPostReceive 不做逻辑）。
 *
 * 真正的执行链路在 ChatViewModel：
 *   · sendMessage 时按关键词（打开/复制/亮度/设置/安装）动态注入提示词
 *   · Done 分支调用 ActionExecutor.extractActions + ActionExecutor.executeAll
 *
 * 我们保留这个文件的目的：
 *   1. 满足"只加插件，不改引擎 / 不改全局系统提示词"的设计约定（注册进 pluginManager 便于以后开关/扩展）。
 *   2. 不在这里做 onPostReceive 正则流（流式 token 时 JSON / <ACTION...> 标签不完整，会乱匹配；
 *      在 Done 时对 finalText 做一次性正则稳定得多，也不会阻塞主线程）。
 *
 * 线程约束：所有 ChatPlugin 方法必须非阻塞（同步 1ms 内返回）。
 */
class ToolExecutionPlugin(private val context: Context) : ChatPlugin {

    // 不 override（ChatPlugin 接口没声明 displayName，改用文件底部的扩展函数统一读）。
    fun name(): String = "AI执行模式"

    // 故意留空：不拦截 token，不修改输入；执行全在 ChatViewModel Done 分支
    override fun onPreSend(input: String): String = input
    override fun onPostReceive(piece: String): String = piece

    fun contextOrNull(): Context = context
}

/** ChatPlugin 接口在 code 62 baseline 里没声明 displayName，这里补一个扩展方法避免改动接口（最小侵入原则）。 */
fun ChatPlugin.displayName(): String = when (this) {
    is ToolExecutionPlugin -> this.name()
    is WebSearchPlugin -> this.name()
    else -> this::class.java.simpleName
}

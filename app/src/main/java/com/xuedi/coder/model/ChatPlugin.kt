package com.xuedi.coder.model

/**
 * 【v1.3.25-stable 新增】轻量级 Chat 插件接口。
 *
 * 这是"纯 Kotlin 层"的流式回调链，不碰 C++：
 *   · onPreSend ：在 nativeChat 调用前，统一修改用户输入文本（可做上下文注入、
 *                 联网搜索摘要插入、@代码 引用替换等）；
 *   · onPostReceive：在 LlamaJniEngine.Callback.onToken 把 piece 交给 UI 之前，
 *                 流式拦截/替换 token（可做代码高亮、Markdown 即时渲染、敏感词过滤）。
 *
 * 实现原则：
 *   · 所有方法必须非阻塞（同步 1ms 内返回），IO 行为请用 launch/缓存提前做。
 *   · 抛出异常会被 LlamaJniEngine 吞掉并打一条 warn 日志，不影响推理主链路稳定性。
 *   · 多个插件按 plugins 列表顺序：onPreSend 正向折叠、onPostReceive 正向折叠。
 *
 * 接入位置：ChatViewModel.sendMessage 里 app.llmEngine 若为 LlamaJniEngine，
 * 通过 `LlamaJniEngine.plugins` 注册。（后续可与 PluginManager 的 enabled 插件联动）
 */
interface ChatPlugin {
    /**
     * 发送前修改用户输入。
     * @param input 上一个插件 onPreSend 处理完的文本（首个插件是用户原始输入）。
     * @return 修改后的文本（想原样返回直接 return input）。
     */
    fun onPreSend(input: String): String = input

    /**
     * 流式 token 后处理。
     * @param piece LlamaJni 刚吐出的 token 片段（UTF-8，可能包含中文/emoji 多字节）。
     * @return 修改后的片段（原样传就 return piece）。
     */
    fun onPostReceive(piece: String): String = piece
}

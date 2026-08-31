package com.xuedi.coder.plugin


data class PluginConfig(
    val id: String,
    val name: String,
    val description: String,
    val version: Int = 1,
    /** system_prompt / regex_tool / dex_action */
    val type: String = "system_prompt",
    /** 注入到AI system提示词里的正文 */
    val inject_system: String = "",
    val author: String = "AI编程助手"
)

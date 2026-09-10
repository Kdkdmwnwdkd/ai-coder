package com.inkrealm.novel.data.model

data class UserSettings(
    val apiKey: String = "",
    val apiBase: String = "https://api.deepseek.com/v1",
    val modelName: String = "deepseek-chat",
    val demoMode: Boolean = true,
    val enableKb: Boolean = false,
    val embeddingKey: String = "",
    val embeddingUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val embeddingModel: String = "text-embedding-v3",
    val embeddingDim: Int = 1024,
    val enableSummary: Boolean = false,
    val enableEntity: Boolean = false,
    val pollinationsKey: String = ""
)

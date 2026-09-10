package com.novelseek.ultra.data.model

data class UserSettings(
    val apiKey: String = "",
    val apiBase: String = "https://api.deepseek.com/v1",
    val modelName: String = "deepseek-chat",
    val demoMode: Boolean = true
)

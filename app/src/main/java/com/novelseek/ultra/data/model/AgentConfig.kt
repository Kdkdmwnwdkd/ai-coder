package com.novelseek.ultra.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_configs",
    foreignKeys = [
        ForeignKey(
            entity = Work::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workId"])]
)
data class AgentConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workId: Long,
    val writingStyle: String = "",
    val continuePrompt: String = "请根据上文继续创作，保持原有风格和人物设定，续写一段自然流畅的内容。",
    val polishPrompt: String = "请润色以下文字，使其更加生动、流畅，同时保持原有情节和风格不变。",
    val expandPrompt: String = "请扩写以下内容，增加细节描写、环境氛围和人物心理活动，使内容更加丰富饱满。",
    val ideaPrompt: String = "请根据以下背景信息，提供几个创意性的情节发展建议或灵感点子。",
    val updatedAt: Long = System.currentTimeMillis()
)

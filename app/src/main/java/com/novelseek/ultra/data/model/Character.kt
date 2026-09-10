package com.novelseek.ultra.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "characters",
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
data class Character(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workId: Long,
    val name: String,
    val description: String = "",
    val role: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

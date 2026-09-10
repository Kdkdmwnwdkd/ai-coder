package com.novelseek.ultra.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outlines",
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
data class Outline(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workId: Long,
    val content: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

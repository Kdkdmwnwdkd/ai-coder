package com.inkrealm.novel.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "works")
data class Work(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val type: WorkType = WorkType.LONG,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class WorkType { SHORT, LONG }

package com.inkrealm.novel.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "world_settings",
    foreignKeys = [ForeignKey(entity = Work::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["workId"])]
)
data class WorldSetting(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workId: Long,
    val name: String,
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

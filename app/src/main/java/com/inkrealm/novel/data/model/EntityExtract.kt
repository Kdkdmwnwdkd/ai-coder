package com.inkrealm.novel.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entities",
    foreignKeys = [ForeignKey(entity = Work::class, parentColumns = ["id"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["workId"])]
)
data class EntityExtract(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workId: Long,
    val chapterId: Long = 0,
    val type: String,
    val name: String,
    val context: String = "",
    val resolved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

package com.xuedi.coder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "plugin_state")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: Int,
    val enabled: Boolean,
    val folderName: String
)

package com.xuedi.coder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_info")
data class ModelEntity(
    @PrimaryKey val id: String,
    /** 私有目录下的绝对路径 */
    val filePath: String,
    /** 文件名 */
    val fileName: String,
    val sizeBytes: Long,
    /** 例如 "Qwen2.5-Coder-3B-Instruct-Q4_K_M" */
    val displayName: String,
    val architecture: String = "gguf",
    /** 是否为当前选中的默认模型 */
    val selected: Boolean = false,
    /** 是否已做过基本头部校验 */
    val validated: Boolean = false,
    val addedAtMs: Long
) {
    val sizeHuman: String get() {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            sizeBytes >= gb -> "%.2f GB".format(sizeBytes / gb)
            sizeBytes >= mb -> "%.2f MB".format(sizeBytes / mb)
            sizeBytes >= kb -> "%.2f KB".format(sizeBytes / kb)
            else -> "$sizeBytes B"
        }
    }
}

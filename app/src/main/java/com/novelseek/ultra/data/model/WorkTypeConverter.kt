package com.novelseek.ultra.data.model

import androidx.room.TypeConverter

class WorkTypeConverter {
    @TypeConverter
    fun fromString(value: String): WorkType {
        return try {
            WorkType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            WorkType.SHORT
        }
    }

    @TypeConverter
    fun toString(type: WorkType): String {
        return type.name
    }
}

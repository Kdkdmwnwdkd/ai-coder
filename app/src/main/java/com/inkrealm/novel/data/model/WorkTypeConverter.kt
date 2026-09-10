package com.inkrealm.novel.data.model

import androidx.room.TypeConverter

class WorkTypeConverter {
    @TypeConverter
    fun fromString(value: String): WorkType = try { WorkType.valueOf(value) } catch (e: IllegalArgumentException) { WorkType.LONG }
    @TypeConverter
    fun toString(type: WorkType): String = type.name
}

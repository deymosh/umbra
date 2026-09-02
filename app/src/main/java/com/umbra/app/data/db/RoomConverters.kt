package com.umbra.app.data.db

import androidx.room.TypeConverter

class RoomConverters {

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        if (value == null) return null
        return value.joinToString(separator = "\u001F")
    }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        if (value == null) return null
        if (value.isEmpty()) return emptyList()
        return value.split("\u001F")
    }
}

package com.certis.screeneye.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "log_events")
data class LogEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val type: String,
    val message: String?,
    val durationMs: Long?
)

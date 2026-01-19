package com.certis.screeneye.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LogEventDao {
    @Insert
    fun insert(event: LogEvent)

    @Query("SELECT * FROM log_events ORDER BY timestampMs DESC LIMIT :limit")
    fun recent(limit: Int): List<LogEvent>
}

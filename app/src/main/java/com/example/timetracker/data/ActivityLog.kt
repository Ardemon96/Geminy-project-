package com.example.timetracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_logs")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis() // Время сохранения записи для сортировки по дням/неделям
)
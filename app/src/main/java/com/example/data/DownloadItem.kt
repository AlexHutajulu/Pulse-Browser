package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey val id: String, // UUID
    val fileName: String,
    val url: String,
    val domain: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String, // QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    val fileUri: String, // Destination path or uri
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val failureReason: String = "",
    val isIncognito: Boolean = false
)

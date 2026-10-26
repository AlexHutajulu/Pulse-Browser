package com.example.data

import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val dao: DownloadDao) {
    val allDownloads: Flow<List<DownloadItem>> = dao.getAllDownloads()
    val nonIncognitoDownloads: Flow<List<DownloadItem>> = dao.getNonIncognitoDownloads()

    suspend fun insertOrUpdate(downloadItem: DownloadItem) = dao.insertOrUpdate(downloadItem)
    suspend fun getDownloadById(id: String): DownloadItem? = dao.getDownloadById(id)
    suspend fun deleteById(id: String) = dao.deleteById(id)
    suspend fun clearAll() = dao.clearAll()
    suspend fun clearCompleted() = dao.clearCompleted()
    suspend fun clearIncognito() = dao.clearIncognito()
}

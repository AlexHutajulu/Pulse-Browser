package com.example.data

import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val db: AppDatabase) {
    private val historyDao = db.historyDao()
    private val bookmarkDao = db.bookmarkDao()

    val allHistory: Flow<List<HistoryItem>> = historyDao.getAllHistory()
    val allBookmarks: Flow<List<BookmarkItem>> = bookmarkDao.getAllBookmarks()

    suspend fun addHistory(title: String, url: String) {
        // Disregard blank URLs
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        historyDao.insertHistory(HistoryItem(title = title, url = url))
    }

    suspend fun deleteHistory(id: Int) {
        historyDao.deleteHistory(id)
    }

    suspend fun clearHistory() {
        historyDao.clearAllHistory()
    }

    suspend fun addBookmark(title: String, url: String) {
        if (url.isBlank()) return
        val displayTitle = if (title.isBlank()) url else title
        bookmarkDao.insertBookmark(BookmarkItem(title = displayTitle, url = url))
    }

    suspend fun deleteBookmark(id: Int) {
        bookmarkDao.deleteBookmark(id)
    }

    suspend fun deleteBookmarkByUrl(url: String) {
        bookmarkDao.deleteBookmarkByUrl(url)
    }

    suspend fun isBookmarked(url: String): Boolean {
        return bookmarkDao.isBookmarked(url)
    }

    suspend fun clearBookmarks() {
        bookmarkDao.clearAllBookmarks()
    }
}

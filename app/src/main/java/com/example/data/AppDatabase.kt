package com.example.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.manager.EncryptedPreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

enum class DatabaseEncryptionStatus(
    val title: String,
    val detail: String,
    val isEncrypted: Boolean
) {
    ENCRYPTED_SQLCIPHER(
        title = "Terenkripsi (SQLCipher AES-256)",
        detail = "Riwayat, markah, dan unduhan dienkripsi di penyimpanan menggunakan SQLCipher 256-bit.",
        isEncrypted = true
    ),
    DEGRADED_RECOVERY_IN_MEMORY(
        title = "Mode Pemulihan Sesi (Memori Sementara)",
        detail = "Database terenkripsi atau kunci SQLCipher tidak dapat diakses saat ini. File terenkripsi lama dipertahankan utuh dan sesi berjalan di memori.",
        isEncrypted = false
    ),
    @Deprecated(
        "SECURITY FIX (audit 2026-10-26): never used anymore. A failed SQLCipher " +
            "initialisation now falls back to an in-memory database, never to a " +
            "plaintext file on disk. Kept only so old persisted status values do not break compilation."
    )
    PLAINTEXT_FALLBACK(
        title = "Database Lokal Standar (Tanpa SQLCipher)",
        detail = "Tidak lagi digunakan. Lihat DEGRADED_RECOVERY_IN_MEMORY.",
        isEncrypted = false
    )
}

@Database(entities = [HistoryItem::class, BookmarkItem::class, DownloadItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        private const val TAG = "AppDatabase"
        internal const val LEGACY_DB_NAME = "alex_browser_database"
        internal const val ENCRYPTED_DB_NAME = "alex_browser_database_encrypted"
        internal const val KEY_SQLCIPHER_MIGRATED = "db_migrated_to_sqlcipher_v1"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val _encryptionStatus = MutableStateFlow(DatabaseEncryptionStatus.DEGRADED_RECOVERY_IN_MEMORY)
        val encryptionStatusFlow: StateFlow<DatabaseEncryptionStatus> = _encryptionStatus.asStateFlow()

        fun getEncryptionStatus(): DatabaseEncryptionStatus = _encryptionStatus.value

        internal fun setEncryptionStatusForTest(status: DatabaseEncryptionStatus) {
            _encryptionStatus.value = status
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`id` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`domain` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`totalBytes` INTEGER NOT NULL, " +
                        "`downloadedBytes` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`fileUri` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER NOT NULL, " +
                        "`failureReason` TEXT NOT NULL, " +
                        "`isIncognito` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        private fun isUnitTestEnvironment(): Boolean {
            val fp = Build.FINGERPRINT?.lowercase() ?: ""
            return fp.contains("robolectric")
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext ?: context
                    val instance = buildDatabaseWithOptionalEncryption(appContext)
                    INSTANCE = instance
                    instance
                }
            }
        }

        private fun buildDatabaseWithOptionalEncryption(appContext: Context): AppDatabase {
            val encryptedDbFile = appContext.getDatabasePath(ENCRYPTED_DB_NAME)
            val encryptedFileExists = encryptedDbFile.exists() && encryptedDbFile.length() > 0L

            if (!isUnitTestEnvironment()) {
                try {
                    System.loadLibrary("sqlcipher")
                    val passphrase = AppDatabaseEncrypted.getDatabasePassphrase(
                        context = appContext,
                        encryptedDbExistsOverride = encryptedFileExists
                    )
                    val encryptedBuilder = Room.databaseBuilder(
                        appContext,
                        AppDatabase::class.java,
                        ENCRYPTED_DB_NAME
                    )
                        .addMigrations(MIGRATION_1_2)
                        .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))

                    if (com.example.BuildConfig.DEBUG && System.getenv("ALLOW_DESTRUCTIVE_MIGRATION") != null) {
                        encryptedBuilder.fallbackToDestructiveMigration(true)
                    }

                    val encryptedDb = encryptedBuilder.build()
                    // Eagerly open and verify the SQLCipher database before returning it to callers
                    encryptedDb.openHelper.writableDatabase
                    migrateLegacyPlaintextDatabaseSync(appContext, encryptedDb)
                    _encryptionStatus.value = DatabaseEncryptionStatus.ENCRYPTED_SQLCIPHER
                    return encryptedDb
                } catch (t: Throwable) {
                    Log.e(TAG, "SQLCipher initialization error (encryptedFileExists=$encryptedFileExists): ${t.message}", t)
                    if (encryptedFileExists || t is DatabaseKeyUnavailableException) {
                        // CRITICAL: Do NOT delete the existing encrypted DB, do NOT overwrite the passphrase,
                        // and do NOT silently write new browsing data to a plaintext file on disk when an encrypted DB already exists.
                        _encryptionStatus.value = DatabaseEncryptionStatus.DEGRADED_RECOVERY_IN_MEMORY
                        return Room.inMemoryDatabaseBuilder(
                            appContext,
                            AppDatabase::class.java
                        ).build()
                    }
                }
            }

            // SECURITY FIX (audit 2026-10-26): never write browsing data to a
            // plaintext file on disk. If SQLCipher is unavailable, the session
            // runs in memory only and nothing is persisted. The existing
            // encrypted file, if any, is left untouched. Unit tests also use
            // this path, which is safe for Robolectric.
            Log.w(TAG, "SQLCipher unavailable; using in-memory database only, nothing will be persisted to disk")
            _encryptionStatus.value = DatabaseEncryptionStatus.DEGRADED_RECOVERY_IN_MEMORY
            return Room.inMemoryDatabaseBuilder(
                appContext,
                AppDatabase::class.java
            ).build()
        }

        internal fun migrateLegacyPlaintextDatabaseSync(appContext: Context, encryptedDb: AppDatabase): Boolean {
            if (EncryptedPreferencesManager.getBoolean(appContext, KEY_SQLCIPHER_MIGRATED, false)) return true
            val legacyFile = appContext.getDatabasePath(LEGACY_DB_NAME)
            if (!legacyFile.exists() || legacyFile.length() == 0L) {
                EncryptedPreferencesManager.putBoolean(appContext, KEY_SQLCIPHER_MIGRATED, true)
                return true
            }

            var legacyDb: AppDatabase? = null
            return try {
                legacyDb = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    LEGACY_DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries()
                    .build()

                val sourceSqlite = legacyDb.openHelper.ReadableDatabaseCompat()
                val targetSqlite = encryptedDb.openHelper.writableDatabase

                val migratedOk = migrateDatabaseContentsTransactionally(sourceSqlite, targetSqlite)
                legacyDb.close()
                legacyDb = null

                if (migratedOk) {
                    appContext.deleteDatabase(LEGACY_DB_NAME)
                    EncryptedPreferencesManager.putBoolean(appContext, KEY_SQLCIPHER_MIGRATED, true)
                    Log.i(TAG, "Successfully migrated legacy plaintext database into SQLCipher encrypted database")
                    true
                } else {
                    Log.w(TAG, "Legacy database migration did not pass verification; keeping legacy DB intact")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Legacy database migration aborted safely (legacy DB preserved): ${e.message}")
                false
            } finally {
                try {
                    legacyDb?.close()
                } catch (_: Exception) {}
            }
        }

        private fun androidx.sqlite.db.SupportSQLiteOpenHelper.ReadableDatabaseCompat(): SupportSQLiteDatabase =
            this.writableDatabase

        internal fun migrateDatabaseContentsTransactionally(
            sourceDb: SupportSQLiteDatabase,
            targetDb: SupportSQLiteDatabase
        ): Boolean {
            var historySourceCount = 0
            var bookmarksSourceCount = 0
            var downloadsSourceCount = 0

            targetDb.beginTransaction()
            try {
                sourceDb.query("SELECT id, title, url, timestamp FROM history").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val title = cursor.getString(1) ?: ""
                        val url = cursor.getString(2) ?: ""
                        val timestamp = cursor.getLong(3)
                        targetDb.execSQL(
                            "INSERT OR REPLACE INTO history (id, title, url, timestamp) VALUES (?, ?, ?, ?)",
                            arrayOf<Any>(id, title, url, timestamp)
                        )
                        historySourceCount++
                    }
                }

                sourceDb.query("SELECT id, title, url, timestamp FROM bookmarks").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val title = cursor.getString(1) ?: ""
                        val url = cursor.getString(2) ?: ""
                        val timestamp = cursor.getLong(3)
                        targetDb.execSQL(
                            "INSERT OR REPLACE INTO bookmarks (id, title, url, timestamp) VALUES (?, ?, ?, ?)",
                            arrayOf<Any>(id, title, url, timestamp)
                        )
                        bookmarksSourceCount++
                    }
                }

                sourceDb.query(
                    "SELECT id, fileName, url, domain, mimeType, totalBytes, downloadedBytes, status, fileUri, createdAt, completedAt, failureReason, isIncognito FROM downloads WHERE isIncognito = 0"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val fileName = cursor.getString(1) ?: ""
                        val url = cursor.getString(2) ?: ""
                        val domain = cursor.getString(3) ?: ""
                        val mimeType = cursor.getString(4) ?: ""
                        val totalBytes = cursor.getLong(5)
                        val downloadedBytes = cursor.getLong(6)
                        val status = cursor.getString(7) ?: ""
                        val fileUri = cursor.getString(8) ?: ""
                        val createdAt = cursor.getLong(9)
                        val completedAt = cursor.getLong(10)
                        val failureReason = cursor.getString(11) ?: ""
                        val isIncognito = cursor.getInt(12)
                        targetDb.execSQL(
                            "INSERT OR REPLACE INTO downloads (id, fileName, url, domain, mimeType, totalBytes, downloadedBytes, status, fileUri, createdAt, completedAt, failureReason, isIncognito) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf<Any>(
                                id, fileName, url, domain, mimeType, totalBytes, downloadedBytes,
                                status, fileUri, createdAt, completedAt, failureReason, isIncognito
                            )
                        )
                        downloadsSourceCount++
                    }
                }

                val targetHistoryCount = queryRowCount(targetDb, "SELECT COUNT(*) FROM history")
                val targetBookmarksCount = queryRowCount(targetDb, "SELECT COUNT(*) FROM bookmarks")
                val targetDownloadsCount = queryRowCount(targetDb, "SELECT COUNT(*) FROM downloads WHERE isIncognito = 0")

                if (targetHistoryCount < historySourceCount ||
                    targetBookmarksCount < bookmarksSourceCount ||
                    targetDownloadsCount < downloadsSourceCount
                ) {
                    return false
                }

                targetDb.setTransactionSuccessful()
                return true
            } finally {
                targetDb.endTransaction()
            }
        }

        private fun queryRowCount(db: SupportSQLiteDatabase, sql: String): Int {
            db.query(sql).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }
}


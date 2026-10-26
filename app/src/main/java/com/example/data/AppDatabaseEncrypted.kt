package com.example.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.manager.EncryptedPreferencesManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class DatabaseKeyUnavailableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

@Database(entities = [HistoryItem::class, BookmarkItem::class, DownloadItem::class], version = 2, exportSchema = false)
abstract class AppDatabaseEncrypted : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        internal const val ENCRYPTED_DB_NAME = "alex_browser_database_encrypted"
        internal const val KEY_DB_PASSPHRASE = "db_passphrase"

        @Volatile
        private var INSTANCE: AppDatabaseEncrypted? = null

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

        fun getDatabase(context: Context): AppDatabaseEncrypted {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    val appContext = context.applicationContext ?: context
                    val passphrase = getDatabasePassphrase(appContext)

                    val builder = Room.databaseBuilder(
                        appContext,
                        AppDatabaseEncrypted::class.java,
                        ENCRYPTED_DB_NAME
                    ).addMigrations(MIGRATION_1_2)

                    // Use official SQLCipher SupportOpenHelperFactory
                    builder.openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)))

                    if (com.example.BuildConfig.DEBUG &&
                        System.getenv("ALLOW_DESTRUCTIVE_MIGRATION") != null
                    ) {
                        builder.fallbackToDestructiveMigration(true)
                        Log.w("AppDatabaseEncrypted", "WARNING: Destructive migration allowed in DEBUG mode")
                    }

                    val instance = builder.build()
                    // Eagerly verify that SQLCipher can open the database with the passphrase
                    instance.openHelper.writableDatabase
                    INSTANCE = instance
                    Log.i("AppDatabaseEncrypted", "Database initialized with SQLCipher encryption")
                    instance
                } catch (e: Exception) {
                    Log.e("AppDatabaseEncrypted", "Failed to initialize encrypted database", e)
                    throw RuntimeException("Critical: Database encryption failed", e)
                }
            }
        }

        fun getDatabasePassphrase(
            context: Context,
            encryptedDbExistsOverride: Boolean? = null
        ): String {
            val appContext = context.applicationContext ?: context
            val encryptedFile = appContext.getDatabasePath(ENCRYPTED_DB_NAME)
            val encryptedDbExists = encryptedDbExistsOverride
                ?: (encryptedFile.exists() && encryptedFile.length() > 0L)

            val prefs = try {
                EncryptedPreferencesManager.getPreferences(appContext)
            } catch (e: Exception) {
                throw DatabaseKeyUnavailableException("Tidak dapat mengakses penyimpanan kunci terenkripsi", e)
            }

            val stored = try {
                prefs.getString(KEY_DB_PASSPHRASE, null)?.trim().orEmpty()
            } catch (e: Exception) {
                throw DatabaseKeyUnavailableException("Gagal membaca passphrase database dari penyimpanan kunci", e)
            }

            if (stored.isNotEmpty()) {
                return stored
            }

            // CRITICAL: If the encrypted database file already exists on disk, NEVER generate a new passphrase!
            if (encryptedDbExists) {
                Log.e(
                    "AppDatabaseEncrypted",
                    "Passphrase missing while encrypted database file already exists; refusing to overwrite key"
                )
                throw DatabaseKeyUnavailableException(
                    "Passphrase untuk database terenkripsi yang sudah ada tidak ditemukan; kunci tidak ditimpa demi mencegah kerusakan data."
                )
            }

            val newPassphrase = java.util.UUID.randomUUID().toString() + "-" + java.util.UUID.randomUUID().toString()
            val committed = prefs.edit().putString(KEY_DB_PASSPHRASE, newPassphrase).commit()
            val verified = prefs.getString(KEY_DB_PASSPHRASE, null)
            if (!committed || verified != newPassphrase) {
                throw DatabaseKeyUnavailableException("Gagal menyimpan passphrase database baru secara persisten")
            }
            return newPassphrase
        }
    }
}


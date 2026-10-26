package com.example.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure wrapper for SharedPreferences with AES256-GCM encryption.
 * All sensitive data (permissions, settings, scripts) are encrypted at rest using Android Keystore.
 *
 * SECURITY FIX (audit 2026-10-26): the old fallback to application-private
 * SharedPreferences was removed. A plaintext file would have stored the
 * SQLCipher passphrase and site permissions in the clear, defeating database
 * encryption. If the Keystore is unavailable, preferences now live only in
 * memory for the lifetime of the process: nothing sensitive is written to
 * disk, and legacy files are NOT migrated or cleared in that case.
 *
 * AUDIT FIX: Replaces plaintext SharedPreferences
 * Issue: CWE-312 - Cleartext Storage of Sensitive Information
 * Severity: CRITICAL
 */
object EncryptedPreferencesManager {
    private const val TAG = "EncryptedPreferencesManager"
    private const val PREFS_NAME = "encrypted_browser_prefs"
    
    @Volatile
    private var prefsInstance: SharedPreferences? = null
    
    /**
     * Get or initialize preferences. Uses EncryptedSharedPreferences with AES256-GCM
     * via Android Keystore. On Keystore failure, returns an in-memory store
     * (never a plaintext file) and skips legacy migration, so no persisted
     * data is lost or exposed.
     */
    fun getPreferences(context: Context): SharedPreferences {
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: run {
                val appContext = context.applicationContext ?: context
                val encrypted: SharedPreferences? = try {
                    val masterKey = MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()

                    EncryptedSharedPreferences.create(
                        appContext,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware keystore unavailable, using in-memory preferences only (nothing persisted): ${e.message}")
                    null
                }
                val instance = encrypted ?: InMemorySharedPreferences()
                if (encrypted != null) {
                    migrateLegacyPreferencesIfNeeded(appContext, instance)
                }
                prefsInstance = instance
                instance
            }
        }
    }

    /**
     * Minimal in-memory SharedPreferences. Used only when encrypted storage
     * is unavailable, so sensitive values are never written to disk in clear.
     * Data lives only for the lifetime of the process.
     */
    private class InMemorySharedPreferences : SharedPreferences {
        private val data = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun contains(key: String): Boolean = data.containsKey(key)

        @Suppress("UNCHECKED_CAST")
        override fun getAll(): Map<String, *> = HashMap(data)

        override fun getString(key: String, defValue: String?): String? =
            data[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun edit(): SharedPreferences.Editor = InMemoryEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value; removals.remove(key); return this
            }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values?.toSet(); removals.remove(key); return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value; removals.remove(key); return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value; removals.remove(key); return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value; removals.remove(key); return this
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value; removals.remove(key); return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                removals.add(key); pending.remove(key); return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true; pending.clear(); removals.clear(); return this
            }

            override fun commit(): Boolean {
                applyChanges(); return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                val changedKeys = HashSet<String>()
                if (clearAll) {
                    changedKeys.addAll(data.keys); data.clear()
                }
                for (key in removals) {
                    if (data.remove(key) != null) changedKeys.add(key)
                }
                for ((key, value) in pending) {
                    if (value == null) data.remove(key) else data[key] = value
                    changedKeys.add(key)
                }
                for (key in changedKeys) {
                    for (listener in listeners) {
                        try { listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    internal fun migrateLegacyPreferencesIfNeeded(appContext: Context, targetPrefs: SharedPreferences) {
        try {
            if (targetPrefs.getBoolean("__legacy_prefs_migrated_v1__", false)) return
            val legacyNames = listOf("alex_browser_prefs", "website_permissions_prefs", "custom_js_toolbar_prefs")
            val editor = targetPrefs.edit()
            var migratedAny = false
            for (legacyName in legacyNames) {
                val legacy = appContext.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
                val allEntries = legacy.all
                if (allEntries.isNotEmpty()) {
                    for ((k, v) in allEntries) {
                        if (targetPrefs.contains(k)) continue
                        when (v) {
                            is String -> editor.putString(k, v)
                            is Boolean -> editor.putBoolean(k, v)
                            is Int -> editor.putInt(k, v)
                            is Long -> editor.putLong(k, v)
                            is Float -> editor.putFloat(k, v)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                (v as? Set<String>)?.let { editor.putStringSet(k, it) }
                            }
                        }
                        migratedAny = true
                    }
                    legacy.edit().clear().apply()
                }
            }
            editor.putBoolean("__legacy_prefs_migrated_v1__", true)
            editor.apply()
            if (migratedAny) {
                Log.i(TAG, "Successfully migrated legacy plaintext SharedPreferences to encrypted storage")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy preferences migration warning: ${e.message}")
        }
    }
    
    fun getString(context: Context, key: String, defaultValue: String? = ""): String? {
        return try {
            getPreferences(context).getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted string: $key", e)
            defaultValue
        }
    }
    
    fun putString(context: Context, key: String, value: String) {
        try {
            getPreferences(context).edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted string: $key", e)
        }
    }
    
    fun getInt(context: Context, key: String, defaultValue: Int = 0): Int {
        return try {
            getPreferences(context).getInt(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted int: $key", e)
            defaultValue
        }
    }
    
    fun putInt(context: Context, key: String, value: Int) {
        try {
            getPreferences(context).edit().putInt(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted int: $key", e)
        }
    }
    
    fun getBoolean(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return try {
            getPreferences(context).getBoolean(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted boolean: $key", e)
            defaultValue
        }
    }
    
    fun putBoolean(context: Context, key: String, value: Boolean) {
        try {
            getPreferences(context).edit().putBoolean(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted boolean: $key", e)
        }
    }
    
    fun getLong(context: Context, key: String, defaultValue: Long = 0L): Long {
        return try {
            getPreferences(context).getLong(key, defaultValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading encrypted long: $key", e)
            defaultValue
        }
    }
    
    fun putLong(context: Context, key: String, value: Long) {
        try {
            getPreferences(context).edit().putLong(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing encrypted long: $key", e)
        }
    }
    
    fun remove(context: Context, key: String) {
        try {
            getPreferences(context).edit().remove(key).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error removing encrypted preference: $key", e)
        }
    }
    
    fun clear(context: Context) {
        try {
            getPreferences(context).edit().clear().apply()
            Log.i(TAG, "All encrypted preferences cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing encrypted preferences", e)
        }
    }
}

package com.example.manager

import android.content.Context
import android.content.SharedPreferences

enum class AppTheme {
    SYSTEM, LIGHT, DARK
}

enum class DisplayMode {
    MOBILE, DESKTOP
}

enum class AdBlockMode {
    OFF, BALANCED, AGGRESSIVE
}

enum class PrivacyShieldMode {
    STANDARD, STRICT
}

class SettingsManager(private val context: Context) {
    // AUDIT FIX: CWE-312 - Use encrypted preferences instead of plaintext
    private val prefs: SharedPreferences
        get() = EncryptedPreferencesManager.getPreferences(context)

    companion object {
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_AD_BLOCK = "ad_block_enabled"
        private const val KEY_TRACKER_BLOCK = "tracker_block_enabled"
        private const val KEY_THEME = "app_theme"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_STREAMING_BOOST = "streaming_boost_enabled"
        private const val KEY_DOWNLOAD_ACCEL = "download_acceleration"
        private const val KEY_DATA_SAVER = "data_saver_enabled"

        private const val KEY_LAST_OPENED_URL = "last_opened_url"
        private const val KEY_INSPECT_TOOLS = "inspect_tools_enabled"
        private const val KEY_REMOTE_DEBUGGING = "remote_debugging_enabled"
        private const val KEY_ALLOW_SCREEN_RECORDING = "allow_screen_recording"
        private const val KEY_AGGRESSIVE_AD_BLOCK = "aggressive_ad_block"
        private const val KEY_BACKGROUND_PLAYBACK = "background_playback_enabled"
        
        private const val KEY_PRIVACY_SHIELD_MODE = "privacy_shield_mode"
        private const val KEY_PREVENT_WEBRTC_LEAK = "prevent_webrtc_leak"
        private const val KEY_STRICT_WEBRTC_MODE = "strict_webrtc_mode"
        private const val KEY_CLEAR_COOKIES_ON_EXIT = "clear_cookies_on_exit"
        
        // Counters
        private const val KEY_TRACKER_COOKIES_BLOCKED = "tracker_cookies_blocked"
        private const val KEY_FINGERPRINTING_BLOCKED = "fingerprinting_blocked"
        private const val KEY_THIRD_PARTY_COOKIES_BLOCKED = "third_party_cookies_blocked"
    }

    // --- Counters ---
    var trackersCookiesBlockedCount: Long
        get() = prefs.getLong(KEY_TRACKER_COOKIES_BLOCKED, 0L)
        set(value) = prefs.edit().putLong(KEY_TRACKER_COOKIES_BLOCKED, value).apply()
        
    var fingerprintingBlockedCount: Long
        get() = prefs.getLong(KEY_FINGERPRINTING_BLOCKED, 0L)
        set(value) = prefs.edit().putLong(KEY_FINGERPRINTING_BLOCKED, value).apply()
        
    var thirdPartyCookiesBlockedCount: Long
        get() = prefs.getLong(KEY_THIRD_PARTY_COOKIES_BLOCKED, 0L)
        set(value) = prefs.edit().putLong(KEY_THIRD_PARTY_COOKIES_BLOCKED, value).apply()
        
    @Synchronized
    fun incrementTrackerCookies() { trackersCookiesBlockedCount++ }
    @Synchronized
    fun incrementFingerprinting() { fingerprintingBlockedCount++ }
    @Synchronized
    fun incrementThirdPartyCookies() { thirdPartyCookiesBlockedCount++ }

    // --- Privacy Features ---
    var privacyShieldMode: PrivacyShieldMode
        get() {
            val name = prefs.getString(KEY_PRIVACY_SHIELD_MODE, PrivacyShieldMode.STANDARD.name) ?: PrivacyShieldMode.STANDARD.name
            return try { PrivacyShieldMode.valueOf(name) } catch (e: Exception) { PrivacyShieldMode.STANDARD }
        }
        set(value) = prefs.edit().putString(KEY_PRIVACY_SHIELD_MODE, value.name).apply()
        
    var preventWebRtcLeak: Boolean
        get() = prefs.getBoolean(KEY_PREVENT_WEBRTC_LEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_PREVENT_WEBRTC_LEAK, value).apply()
        
    var strictWebRtcMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT_WEBRTC_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_WEBRTC_MODE, value).apply()
        
    var clearCookiesOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_COOKIES_ON_EXIT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEAR_COOKIES_ON_EXIT, value).apply()

    var isBackgroundPlaybackEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_PLAYBACK, true) // Default ON per requirements
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_PLAYBACK, value).apply()

    // SECURITY FIX (audit 2026-10-26): secure by default. FLAG_SECURE is set
    // in MainActivity when this is false, so screenshots / screen recording
    // are blocked unless the user explicitly opts in.
    var allowScreenRecording: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SCREEN_RECORDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_SCREEN_RECORDING, value).apply()

    var isInspectToolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_INSPECT_TOOLS, false)
        set(value) = prefs.edit().putBoolean(KEY_INSPECT_TOOLS, value).apply()

    var isRemoteDebuggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_DEBUGGING, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_DEBUGGING, value).apply()

    var lastOpenedUrl: String
        get() = prefs.getString(KEY_LAST_OPENED_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_OPENED_URL, value).apply()

    var isStreamingBoostEnabled: Boolean
        get() = prefs.getBoolean(KEY_STREAMING_BOOST, false)
        set(value) = prefs.edit().putBoolean(KEY_STREAMING_BOOST, value).apply()

    var downloadAcceleration: String
        get() = prefs.getString(KEY_DOWNLOAD_ACCEL, "Balanced") ?: "Balanced"
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_ACCEL, value).apply()

    var isDataSaverEnabled: Boolean
        get() = prefs.getBoolean(KEY_DATA_SAVER, false)
        set(value) = prefs.edit().putBoolean(KEY_DATA_SAVER, value).apply()

    var displayMode: DisplayMode
        get() {
            val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
            val defaultMode = if (isTablet) DisplayMode.DESKTOP.name else DisplayMode.MOBILE.name
            val name = prefs.getString(KEY_DISPLAY_MODE, defaultMode) ?: defaultMode
            
            // Migration from old modes
            if (name == "DESKTOP_1" || name == "DESKTOP_2") {
                prefs.edit().putString(KEY_DISPLAY_MODE, DisplayMode.DESKTOP.name).apply()
                return DisplayMode.DESKTOP
            }
            
            return try {
                DisplayMode.valueOf(name)
            } catch (e: Exception) {
                if (isTablet) DisplayMode.DESKTOP else DisplayMode.MOBILE
            }
        }
        set(value) = prefs.edit().putString(KEY_DISPLAY_MODE, value.name).apply()

    var selectedSearchEngine: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, "Google") ?: "Google"
        set(value) = prefs.edit().putString(KEY_SEARCH_ENGINE, value).apply()

    var adBlockMode: AdBlockMode
        get() {
            val name = prefs.getString("ad_block_mode", AdBlockMode.BALANCED.name) ?: AdBlockMode.BALANCED.name
            return try {
                AdBlockMode.valueOf(name)
            } catch (e: Exception) {
                AdBlockMode.BALANCED
            }
        }
        set(value) = prefs.edit().putString("ad_block_mode", value.name).apply()


    var isTrackerBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKER_BLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_TRACKER_BLOCK, value).apply()
        
    var openPopupsInNewTab: Boolean
        get() = prefs.getBoolean("open_popups_new_tab_v2", false)
        set(value) = prefs.edit().putBoolean("open_popups_new_tab_v2", value).apply()

    var blockSuspiciousRedirects: Boolean
        get() = prefs.getBoolean("block_suspicious_redirects", true)
        set(value) = prefs.edit().putBoolean("block_suspicious_redirects", value).apply()

    fun getAdBlockDisabledDomains(): Set<String> {
        return prefs.getStringSet("adblock_disabled_domains", emptySet())?.toSet() ?: emptySet()
    }

    fun setAdBlockDisabledForDomain(domain: String, disabled: Boolean) {
        val normalized = domain.lowercase().trim().removePrefix("www.")
        if (normalized.isBlank()) return
        val current = getAdBlockDisabledDomains().map { it.lowercase().trim().removePrefix("www.") }.toMutableSet()
        if (disabled) {
            current.add(normalized)
        } else {
            current.remove(normalized)
        }
        prefs.edit().putStringSet("adblock_disabled_domains", current).apply()
    }

    fun clearAdBlockExceptions() {
        prefs.edit().remove("adblock_disabled_domains").apply()
    }

    // --- Custom Element Zapper Rules per Domain (JSON with legacy delimiter fallback) ---
    fun getCustomZapperRules(domain: String): List<String> {
        val normalized = domain.lowercase().trim().removePrefix("www.")
        if (normalized.isBlank()) return emptyList()
        val raw = prefs.getString("zapper_rules_$normalized", null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        if (raw.trimStart().startsWith("[")) {
            return try {
                val arr = org.json.JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotBlank()) list.add(item)
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
        return raw.split("|||zap|||").filter { it.isNotBlank() }
    }

    fun saveCustomZapperRules(domain: String, selectors: List<String>) {
        val normalized = domain.lowercase().trim().removePrefix("www.")
        if (normalized.isBlank()) return
        val editor = prefs.edit()
        if (selectors.isEmpty()) {
            editor.remove("zapper_rules_$normalized")
        } else {
            val arr = org.json.JSONArray()
            selectors.filter { it.isNotBlank() }.forEach { arr.put(it.trim()) }
            editor.putString("zapper_rules_$normalized", arr.toString())
        }
        editor.apply()
    }

    var stealthTrapsDefusedCount: Long
        get() = prefs.getLong("stealth_traps_defused_count", 0L)
        set(value) = prefs.edit().putLong("stealth_traps_defused_count", value).apply()

    @Synchronized
    fun incrementStealthTrapsDefused() {
        stealthTrapsDefusedCount++
    }

    var appTheme: AppTheme
        get() {
            val name = prefs.getString(KEY_THEME, AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name
            return try {
                AppTheme.valueOf(name)
            } catch (e: Exception) {
                AppTheme.SYSTEM
            }
        }
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var forceWebPageDarkMode: Boolean
        get() = prefs.getBoolean("force_web_page_dark_mode", false)
        set(value) = prefs.edit().putBoolean("force_web_page_dark_mode", value).apply()

    fun saveTabs(tabs: List<com.example.ui.viewmodel.BrowserTab>, activeTabId: String) {
        val jsonArray = org.json.JSONArray()
        tabs.filter { !it.isPrivate }.forEach { tab ->
            val obj = org.json.JSONObject().apply {
                put("id", tab.id)
                put("url", tab.url)
                put("title", tab.title)
                put("isPrivate", false)
            }
            jsonArray.put(obj)
        }
        prefs.edit()
            .putString("persisted_tabs_v3_json", jsonArray.toString())
            .remove("persisted_tabs_v2")
            .putString("persisted_active_tab_id_v2", activeTabId)
            .apply()
    }

    fun loadTabs(): List<com.example.ui.viewmodel.BrowserTab>? {
        val jsonStr = prefs.getString("persisted_tabs_v3_json", null)
        if (jsonStr != null) {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(jsonStr)
                val result = mutableListOf<com.example.ui.viewmodel.BrowserTab>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val isPrivate = obj.optBoolean("isPrivate", false)
                    if (isPrivate) continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
                    val url = obj.optString("url", "")
                    val title = obj.optString("title", "New Tab")
                    result.add(
                        com.example.ui.viewmodel.BrowserTab(
                            id = id,
                            url = url,
                            title = title,
                            isPrivate = false
                        )
                    )
                }
                result
            } catch (_: Exception) {
                null
            }
        }

        // Legacy fallback for persisted_tabs_v2
        val serialized = prefs.getString("persisted_tabs_v2", null) ?: return null
        if (serialized.isBlank()) return emptyList()
        val parts = serialized.split("|||tab|||")
        return parts.mapNotNull { part ->
            val fields = part.split("|||field|||")
            if (fields.size >= 4) {
                val isPrivate = fields[3].toBoolean()
                if (isPrivate) null
                else com.example.ui.viewmodel.BrowserTab(
                    id = fields[0],
                    url = fields[1],
                    title = fields[2],
                    isPrivate = false
                )
            } else {
                null
            }
        }
    }

    fun getActiveTabId(): String? {
        return prefs.getString("persisted_active_tab_id_v2", null)
    }

    fun clearPersistedTabs() {
        prefs.edit()
            .remove("persisted_tabs_v3_json")
            .remove("persisted_tabs_v2")
            .remove("persisted_active_tab_id_v2")
            .apply()
    }
}

package com.example.manager

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONObject

enum class PermissionState {
    ASK, ALLOW, BLOCK
}

enum class SitePermissionType(val key: String) {
    CAMERA("camera"),
    MICROPHONE("microphone"),
    LOCATION("location"),
    NOTIFICATION("notification"),
    PROTECTED_MEDIA("protected_media"),
    MIDI("midi")
}

class PermissionManager(private val context: Context) {
    private val TAG = "PermissionManager"

    companion object {
        internal const val KEY_PREFIX = "site_perm_v2_"

        /**
         * Normalizes a URL or origin string into a canonical `scheme://host[:port]` origin.
         * Rejects dangerous/internal schemes (`file:`, `content:`, `javascript:`, `data:`, `about:`, etc.).
         */
        fun normalizePermissionOrigin(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val trimmed = url.trim()
            if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("about:blank", ignoreCase = true)) {
                return null
            }
            return try {
                val candidate = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
                val uri = Uri.parse(candidate)
                val scheme = uri.scheme?.lowercase(java.util.Locale.US) ?: return null
                if (scheme != "https" && scheme != "http") return null
                val host = uri.host?.lowercase(java.util.Locale.US)?.trim()?.removePrefix("www.") ?: return null
                if (host.isEmpty() || host.contains(" ") || host.any { it.isISOControl() }) return null
                val port = uri.port
                val isDefaultPort = port == -1 ||
                    (scheme == "https" && port == 443) ||
                    (scheme == "http" && port == 80)
                if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Enforces W3C Secure Contexts requirement for sensitive site permissions:
         * Camera, Microphone, Geolocation, Protected Media, MIDI, and Notifications
         * require `https://` or loopback (`http://localhost`, `http://127.0.0.1`, `http://[::1]`).
         */
        fun isOriginEligibleForPermission(url: String?, type: SitePermissionType): Boolean {
            val normalized = normalizePermissionOrigin(url) ?: return false
            if (normalized.startsWith("https://")) return true
            if (normalized.startsWith("http://")) {
                val host = try {
                    Uri.parse(normalized).host?.lowercase(java.util.Locale.US)?.removeSurrounding("[", "]") ?: ""
                } catch (_: Exception) {
                    ""
                }
                return host == "localhost" || host == "127.0.0.1" || host == "::1"
            }
            return false
        }
    }

    private fun getPrefs() = EncryptedPreferencesManager.getPreferences(context)

    fun getWebsitePermission(url: String, type: SitePermissionType): PermissionState {
        if (!isOriginEligibleForPermission(url, type)) {
            return PermissionState.BLOCK
        }
        val origin = normalizePermissionOrigin(url) ?: return PermissionState.BLOCK
        val prefKey = KEY_PREFIX + origin
        var siteDataStr = EncryptedPreferencesManager.getString(context, prefKey, null)

        // One-time safe migration from legacy bare-host key (only for HTTPS origins)
        if (siteDataStr == null && origin.startsWith("https://")) {
            val legacyHost = try { Uri.parse(origin).host?.lowercase(java.util.Locale.US) } catch (_: Exception) { null }
            if (!legacyHost.isNullOrBlank()) {
                val legacyVal = EncryptedPreferencesManager.getString(context, legacyHost, null)
                if (legacyVal != null && isPermissionJsonPayload(legacyVal)) {
                    EncryptedPreferencesManager.putString(context, prefKey, legacyVal)
                    EncryptedPreferencesManager.remove(context, legacyHost)
                    siteDataStr = legacyVal
                }
            }
        }

        if (siteDataStr == null) return PermissionState.ASK

        return try {
            val json = JSONObject(siteDataStr)
            if (json.has(type.key)) {
                val stateStr = json.getString(type.key)
                PermissionState.valueOf(stateStr)
            } else {
                PermissionState.ASK
            }
        } catch (e: Exception) {
            SecurityLogger.logValidationError("PermissionManager", "Failed to parse permission state for $origin")
            PermissionState.ASK
        }
    }

    fun setWebsitePermission(url: String, type: SitePermissionType, state: PermissionState) {
        val origin = normalizePermissionOrigin(url) ?: return
        if (state == PermissionState.ALLOW && !isOriginEligibleForPermission(url, type)) {
            SecurityLogger.logValidationError(
                "PermissionManager",
                "Blocked granting ${type.name} to non-secure origin $origin"
            )
            return
        }
        val prefKey = KEY_PREFIX + origin
        val siteDataStr = EncryptedPreferencesManager.getString(context, prefKey, "{}") ?: "{}"

        try {
            val json = JSONObject(siteDataStr)
            json.put(type.key, state.name)
            EncryptedPreferencesManager.putString(context, prefKey, json.toString())
            Log.d(TAG, "Permission set for $origin: ${type.key} = ${state.name}")
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to set website permission for $origin",
                exception = e
            )
        }
    }

    fun resetWebsitePermission(url: String) {
        val origin = normalizePermissionOrigin(url) ?: return
        try {
            EncryptedPreferencesManager.remove(context, KEY_PREFIX + origin)
            val legacyHost = try { Uri.parse(origin).host?.lowercase(java.util.Locale.US) } catch (_: Exception) { null }
            if (!legacyHost.isNullOrBlank()) {
                val legacyVal = EncryptedPreferencesManager.getString(context, legacyHost, null)
                if (legacyVal != null && isPermissionJsonPayload(legacyVal)) {
                    EncryptedPreferencesManager.remove(context, legacyHost)
                }
            }
            Log.d(TAG, "Permissions reset for $origin")
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to reset website permission for $origin",
                exception = e
            )
        }
    }

    fun clearAllPermissions() {
        try {
            val prefs = getPrefs()
            val keysToRemove = mutableListOf<String>()
            for ((key, value) in prefs.all) {
                if (key.startsWith(KEY_PREFIX)) {
                    keysToRemove.add(key)
                } else if (value is String && isPermissionJsonPayload(value)) {
                    keysToRemove.add(key)
                }
            }
            if (keysToRemove.isNotEmpty()) {
                val editor = prefs.edit()
                keysToRemove.forEach { editor.remove(it) }
                editor.apply()
            }
            Log.i(TAG, "All site permissions cleared (${keysToRemove.size} origins)")
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to clear all permissions",
                exception = e
            )
        }
    }

    fun getAllPermissions(): Map<String, Map<SitePermissionType, PermissionState>> {
        val result = mutableMapOf<String, Map<SitePermissionType, PermissionState>>()
        return try {
            val prefs = getPrefs()
            for ((key, valueStr) in prefs.all) {
                if (!key.startsWith(KEY_PREFIX) && !(valueStr is String && isPermissionJsonPayload(valueStr))) {
                    continue
                }
                if (valueStr is String) {
                    try {
                        val json = JSONObject(valueStr)
                        val originMap = mutableMapOf<SitePermissionType, PermissionState>()
                        for (type in SitePermissionType.values()) {
                            if (json.has(type.key)) {
                                originMap[type] = PermissionState.valueOf(json.getString(type.key))
                            }
                        }
                        if (originMap.isNotEmpty()) {
                            val displayOrigin = key.removePrefix(KEY_PREFIX)
                            result[displayOrigin] = originMap
                        }
                    } catch (e: Exception) {
                        SecurityLogger.logValidationError("PermissionManager", "Failed to parse permission entry for $key")
                    }
                }
            }
            result
        } catch (e: Exception) {
            SecurityLogger.logSecurityEvent(
                type = SecurityLogger.EventType.UNKNOWN_ERROR,
                component = "PermissionManager",
                message = "Failed to get all permissions",
                exception = e
            )
            emptyMap()
        }
    }

    private fun isPermissionJsonPayload(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return try {
            val json = JSONObject(trimmed)
            SitePermissionType.values().any { json.has(it.key) }
        } catch (_: Exception) {
            false
        }
    }
}

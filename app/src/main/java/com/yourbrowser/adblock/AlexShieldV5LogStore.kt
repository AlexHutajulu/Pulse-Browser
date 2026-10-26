package com.yourbrowser.adblock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class AlexShieldBlockedLog(
    val tabId: String,
    val pageHost: String,
    val blockedUrl: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

object AlexShieldV5LogStore {
    private const val MAX_LOGS = 500

    private val _logs = MutableStateFlow<List<AlexShieldBlockedLog>>(emptyList())
    val logs: StateFlow<List<AlexShieldBlockedLog>> = _logs

    fun normalizeHost(urlOrHost: String?): String {
        val raw = urlOrHost?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val candidate = if (raw.contains("://")) {
            try {
                android.net.Uri.parse(raw).host.orEmpty()
            } catch (_: Exception) {
                ""
            }
        } else {
            raw.substringBefore("/").substringBefore(":")
        }
        return candidate.lowercase(java.util.Locale.US).removePrefix("www.")
    }

    fun classifyReason(blockedUrl: String, reason: String): String {
        val lowerUrl = blockedUrl.lowercase(java.util.Locale.US)
        val isPopup = reason.contains("popup", ignoreCase = true) ||
            reason.contains("redirect", ignoreCase = true)
        val isTracker = reason.contains("tracker", ignoreCase = true) ||
            reason.contains("beacon", ignoreCase = true) ||
            lowerUrl.contains("analytics") ||
            lowerUrl.contains("tracker") ||
            lowerUrl.contains("pixel") ||
            lowerUrl.contains("beacon") ||
            lowerUrl.contains("telemetry") ||
            lowerUrl.contains("collect")
        return when {
            isPopup -> "Popup"
            isTracker -> "Tracker"
            else -> "Ad"
        }
    }

    fun add(tabId: String, pageUrl: String?, blockedUrl: String, reason: String) {
        val host = normalizeHost(pageUrl)
        _logs.update { old ->
            (old + AlexShieldBlockedLog(tabId, host, blockedUrl, reason)).takeLast(MAX_LOGS)
        }
    }

    fun forTab(tabId: String): List<AlexShieldBlockedLog> {
        return _logs.value.filter { it.tabId == tabId }
    }

    fun forPageHost(pageUrlOrHost: String?): List<AlexShieldBlockedLog> {
        val targetHost = normalizeHost(pageUrlOrHost)
        if (targetHost.isEmpty()) return emptyList()
        return _logs.value.filter { log ->
            log.pageHost.isNotEmpty() && (
                log.pageHost == targetHost ||
                    log.pageHost.endsWith(".$targetHost") ||
                    targetHost.endsWith(".${log.pageHost}")
                )
        }
    }

    fun clearTab(tabId: String) {
        _logs.update { old -> old.filterNot { it.tabId == tabId } }
    }

    fun clearAll() {
        _logs.value = emptyList()
    }
}

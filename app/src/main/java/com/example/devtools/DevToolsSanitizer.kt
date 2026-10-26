package com.example.devtools

import java.util.Locale

object DevToolsSanitizer {

    private val SENSITIVE_HEADERS = setOf(
        "authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization", "token", "apikey"
    )

    private val SENSITIVE_KEYS = setOf(
        "token", "auth", "password", "session", "jwt", "bearer", "secret", "refresh", "credentials", "private"
    )

    fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        return headers.entries.associate { (key, value) ->
            val lowerKey = key.lowercase(Locale.US)
            val sanitizedValue = if (SENSITIVE_HEADERS.contains(lowerKey)) {
                "[CONFIDENTIAL - MASKED]"
            } else {
                value
            }
            key to sanitizedValue
        }
    }

    fun sanitizeStorageMap(map: Map<String, String>): Map<String, String> {
        return map.entries.associate { (key, value) ->
            val lowerKey = key.lowercase(Locale.US)
            val isSensitive = SENSITIVE_KEYS.any { lowerKey.contains(it) }
            val sanitizedValue = if (isSensitive) {
                "[MASKED SECURITY TOKEN]"
            } else {
                value
            }
            key to sanitizedValue
        }
    }

    fun sanitizeCookieString(cookies: String): String {
        if (cookies.isBlank()) return ""
        return cookies.split(";")
            .map { part ->
                val eqIndex = part.indexOf('=')
                if (eqIndex != -1) {
                    val key = part.substring(0, eqIndex).trim()
                    val lowerKey = key.lowercase(Locale.US)
                    val isSensitive = SENSITIVE_KEYS.any { lowerKey.contains(it) }
                    if (isSensitive) {
                        "$key=[MASKED]"
                    } else {
                        part.trim()
                    }
                } else {
                    part.trim()
                }
            }
            .joinToString("; ")
    }
}

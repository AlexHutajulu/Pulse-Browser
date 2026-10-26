package com.example.manager

import android.net.Uri

enum class SearchEngine(
    val engineName: String,
    val queryUrl: String,
    val homeUrl: String,
    val description: String = ""
) {
    GOOGLE(
        "Google",
        "https://www.google.com/search?q=",
        "https://www.google.com",
        "Mesin pencari utama (Default)"
    ),
    BING(
        "Bing",
        "https://www.bing.com/search?q=",
        "https://www.bing.com",
        "Microsoft Bing"
    ),
    DUCKDUCKGO(
        "DuckDuckGo",
        "https://duckduckgo.com/?q=",
        "https://duckduckgo.com",
        "Privasi tanpa pelacakan"
    ),
    YAHOO(
        "Yahoo",
        "https://search.yahoo.com/search?p=",
        "https://search.yahoo.com",
        "Yahoo Search"
    ),
    BRAVE(
        "Brave Search",
        "https://search.brave.com/search?q=",
        "https://search.brave.com",
        "Independen & berfokus privasi"
    ),
    ECOSIA(
        "Ecosia",
        "https://www.ecosia.org/search?q=",
        "https://www.ecosia.org",
        "Menanam pohon saat mencari"
    ),
    YANDEX(
        "Yandex",
        "https://yandex.com/search/?text=",
        "https://yandex.com",
        "Yandex Search"
    ),
    STARTPAGE(
        "Startpage",
        "https://www.startpage.com/sp/search?query=",
        "https://www.startpage.com",
        "Hasil Google dengan privasi penuh"
    );

    companion object {
        fun fromName(name: String?): SearchEngine {
            if (name.isNullOrBlank()) return GOOGLE
            return values().find { it.engineName.equals(name.trim(), ignoreCase = true) } ?: GOOGLE
        }

        fun getAllEngines(): List<SearchEngine> = values().toList()
    }
}

object SearchEngineManager {
    fun parseUrlOrQuery(input: String, selectedEngine: SearchEngine): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return selectedEngine.homeUrl

        // Only allow safe web schemes (http://, https://) and about:blank.
        // Dangerous schemes (javascript:, file://, content://, data:, intent://) are treated as search queries.
        if (trimmed.equals("about:blank", ignoreCase = true)) return "about:blank"
        val hasSafeWebScheme = trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        if (hasSafeWebScheme) return trimmed

        // Localhost and IPv4 address check (e.g. 192.168.1.1, 10.0.0.1, 127.0.0.1:8080)
        val isLocalhost = trimmed.startsWith("localhost", ignoreCase = true)
        val ipV4Pattern = "^(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(?::\\d+)?(?:/.*)?$".toRegex()
        val isIp = ipV4Pattern.matches(trimmed)
        if (isLocalhost || isIp) {
            return "http://$trimmed"
        }

        // Standard domain matching (e.g. google.com, wikipedia.org/wiki, github.com)
        val urlPattern = "^(?i)([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(:\\d+)?(/.*)?$".toRegex()
        if (urlPattern.matches(trimmed)) {
            return "https://$trimmed"
        }

        // Treat as web search query with the user's selected search engine
        val encodedQuery = Uri.encode(trimDoubleSpaces(trimmed))
        return "${selectedEngine.queryUrl}$encodedQuery"
    }

    private fun trimDoubleSpaces(str: String): String {
        return str.replace("\\s+".toRegex(), " ")
    }
}

package com.example.devtools

import java.util.Locale

object DevToolsResourceClassifier {

    fun classify(url: String): ResourceType {
        val lowerUrl = url.lowercase(Locale.US).trim()
        
        if (lowerUrl.contains("/xhr/") || lowerUrl.contains("/api/") || lowerUrl.contains("ajax") || lowerUrl.contains("/graphql")) {
            return ResourceType.FETCH
        }

        // Check path extensions
        val path = try {
            val uri = android.net.Uri.parse(url)
            uri.path?.lowercase(Locale.US) ?: ""
        } catch (e: Exception) {
            lowerUrl
        }

        return when {
            path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php") || path.endsWith(".jsp") -> ResourceType.DOCUMENT
            path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".ts") || lowerUrl.contains("/js/") || lowerUrl.contains("/script") -> ResourceType.SCRIPT
            path.endsWith(".css") || lowerUrl.contains("/css/") || lowerUrl.contains("stylesheet") -> ResourceType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".webp") || path.endsWith(".ico") -> ResourceType.IMAGE
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") || path.endsWith(".eot") -> ResourceType.FONT
            path.endsWith(".mp4") || path.endsWith(".mp3") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".ogg") || path.endsWith(".wav") || path.endsWith(".m4s") -> ResourceType.MEDIA
            lowerUrl.startsWith("ws://") || lowerUrl.startsWith("wss://") -> ResourceType.WEBSOCKET
            else -> ResourceType.OTHER
        }
    }
}

package com.example.manager

enum class RequestKind {
    SCRIPT, IMAGE, STYLESHEET, SUBDOCUMENT, XMLHTTPREQUEST,
    FONT, MEDIA, WEBSOCKET, PING, DOCUMENT, OTHER
}

data class NetworkRequest(
    val url: String,
    val requestHost: String,
    val pageHost: String?,
    val kind: RequestKind = RequestKind.OTHER,
    val isThirdParty: Boolean = false,
)

data class UnifiedBlockResult(
    
    val shouldBlock: Boolean,
    
    val important: Boolean = false,
    
    val exceptionMatched: Boolean = false,
    
    val redirectBody: String? = null,
    
    val rewrittenUrl: String? = null,
    
    val cspDirectives: List<String> = emptyList(),
    
    val reason: String = "",
) {
    companion object {
        fun allow(reason: String = "") = UnifiedBlockResult(false, reason = reason)
        fun block(reason: String = "", important: Boolean = false) =
            UnifiedBlockResult(true, important = important, reason = reason)
    }
}

fun interface NetworkMatcher {
    fun check(request: NetworkRequest): UnifiedBlockResult
}

data class PageCosmeticResources(
    val hideSelectors: Set<String> = emptySet(),
    val proceduralActions: Set<String> = emptySet(),
    val injectedScript: String = "",
    val generichide: Boolean = false,
    val exceptions: Set<String> = emptySet(),
)

fun interface CosmeticProvider {
    fun resourcesFor(pageHost: String): PageCosmeticResources
}

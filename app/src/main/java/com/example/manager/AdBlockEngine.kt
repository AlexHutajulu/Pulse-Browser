package com.example.manager

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

enum class ResourceType {
    MAIN_DOCUMENT, SCRIPT, STYLESHEET, IMAGE, MEDIA, FONT, XHR, IFRAME, OTHER
}

/**
 * Engine utama AdBlocker.
 */
object AdBlockEngine {

    private const val TAG = "AdBlockEngine"

    private val CAPTCHA_DOMAINS = listOf(
        "recaptcha.net", "gstatic.com", "hcaptcha.com", "js.hcaptcha.com",
        "challenges.cloudflare.com", "turnstile.cloudflare.com",
        "arkoselabs.com", "funcaptcha.com", "geetest.com",
        "friendlycaptcha.com", "botdetect.com", "auth0.com",
        "accounts.google.com"
    )
    private val CAPTCHA_KEYWORDS = listOf(
        "recaptcha", "hcaptcha", "turnstile", "captcha", "challenge",
        "cf-chl", "cdn-cgi/challenge", "arkoselabs", "funcaptcha",
        "geetest", "antibot", "verify", "verification", "auth", "sso"
    )

    private val CRITICAL_KEYWORDS = listOf(
        "login", "signin", "signup", "register", "payment", "checkout",
        "verify", "verification", "oauth", "pembayaran", "transaksi",
        "banking", "transfer", "topup", "subscribe", "account", "checkout"
    )

    private val VIDEO_EXTENSIONS = listOf(".m3u8", ".mp4", ".webm", ".ts", ".m4s", ".dash")
    private val VIDEO_DOMAINS = listOf(
        "googlevideo.com", "youtube.com", "vimeo.com", "dailymotion.com",
        "netflix.com", "tiktok.com", "twitch.tv", "hbo.com", "disneyplus.com",
        "cdn.discordapp.com"
    )
    
    private val SAFE_CDNS = listOf(
        "cloudflare.com", "cdnjs.cloudflare.com", "jsdelivr.net", "unpkg.com",
        "googleapis.com", "jquery.com", "bootstrapcdn.com"
    )

    fun determineResourceType(request: WebResourceRequest): ResourceType {
        val headers = request.requestHeaders ?: return ResourceType.OTHER
        val accept = headers["Accept"]?.lowercase() ?: ""
        val url = request.url.toString().lowercase()

        return when {
            request.isForMainFrame -> ResourceType.MAIN_DOCUMENT
            accept.contains("text/html") -> ResourceType.IFRAME
            accept.contains("text/css") || url.endsWith(".css") -> ResourceType.STYLESHEET
            accept.contains("image/") || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".svg") -> ResourceType.IMAGE
            accept.contains("video/") || accept.contains("audio/") || isStreamingOrVideo(url) -> ResourceType.MEDIA
            accept.contains("application/json") || headers["X-Requested-With"] == "XMLHttpRequest" -> ResourceType.XHR
            accept.contains("font/") || url.endsWith(".woff2") || url.endsWith(".ttf") -> ResourceType.FONT
            accept.contains("application/javascript") || accept.contains("text/javascript") || url.endsWith(".js") -> ResourceType.SCRIPT
            else -> ResourceType.OTHER
        }
    }

    fun shouldBlock(
        request: WebResourceRequest?,
        url: String?,
        mainPageUrl: String?,
        adBlockMode: AdBlockMode,
        trackerBlockEnabled: Boolean,
        disabledDomains: Set<String>,
        streamingBoostEnabled: Boolean = false
    ): Boolean {
        if (url == null || url.isBlank() || (adBlockMode == AdBlockMode.OFF && !trackerBlockEnabled)) return false

        // 1. Never block Captcha/Verification
        if (isCaptchaOrVerification(url)) return false

        val requestHost = getHost(url)
        val mainHost = getHost(mainPageUrl)

        // 2. Global Whitelist
        if (isInDomainSet(mainHost, disabledDomains) || isInDomainSet(requestHost, disabledDomains)) {
            return false
        }

        // 3. Streaming Boost
        if (streamingBoostEnabled && isStreamingOrVideo(url)) return false

        // 4. Critical Contexts (Login, Payment)
        val isCritical = isCriticalContext(mainPageUrl) || isCriticalContext(url)
        if (isCritical) return false

        // 5. First-Party Protection & Safe Critical Resources
        val isFirstParty = (mainHost.isNotEmpty() && isDomainMatch(requestHost, mainHost)) || mainHost == requestHost
        if (request != null) {
            val resourceType = determineResourceType(request)
            
            // NEVER block main document or first-party essential files
            if (resourceType == ResourceType.MAIN_DOCUMENT) return false
            if (isFirstParty && (resourceType == ResourceType.STYLESHEET || resourceType == ResourceType.SCRIPT || resourceType == ResourceType.FONT)) {
                return false // Don't block our own scripts/css
            }
            if (resourceType == ResourceType.MEDIA) return false // Protect media
            
            // Allow common CDNs unless specifically ad-related URL
            if (SAFE_CDNS.any { isDomainMatch(requestHost, it) }) {
                if (!url.lowercase().contains("/ads/") && !url.lowercase().contains("adsystem")) {
                    return false
                }
            }
        } else {
            // If request is null, fallback protection for First-Party resources
            if (isFirstParty) return false
        }

        val lowerUrl = url.lowercase()
        val isTrackerCandidate = FALLBACK_TRACKER_DOMAINS.any { isDomainMatch(requestHost, it) } ||
            lowerUrl.contains("analytics") || lowerUrl.contains("tracker") ||
            lowerUrl.contains("pixel") || lowerUrl.contains("telemetry")

        // When AdBlock is OFF and only Tracker Blocking is enabled, only block tracker domains/urls
        if (adBlockMode == AdBlockMode.OFF && trackerBlockEnabled) {
            if (isTrackerCandidate) {
                logBlockedRequest(url, requestHost, "Tracker", mainPageUrl ?: "")
                return true
            }
            return false
        }

        // When Tracker Blocking is disabled, skip pure tracker matches
        if (!trackerBlockEnabled && isTrackerCandidate && !FALLBACK_AD_DOMAINS.any { isDomainMatch(requestHost, it) }) {
            return false
        }

        // 6. Rules Evaluation
        val rules = FilterListManager.getCachedRules()
        if (rules != null) {
            if (isInDomainSet(requestHost, rules.whitelistDomains)) return false

            if (isDomainBlocked(requestHost, rules.blockDomains)) {
                Log.v(TAG, "Blocked by filter list domain: $requestHost")
                val logType = if (isTrackerCandidate) "Tracker" else "Ad"
                logBlockedRequest(url, requestHost, logType, mainPageUrl ?: "")
                return true
            }

            if (adBlockMode == AdBlockMode.AGGRESSIVE) {
                // To avoid greedy regex matching that breaks sites, limit what patterns block
                // Only run patterns if it's NOT a first-party resource
                if (!isFirstParty) {
                    for (pattern in rules.blockPatterns) {
                        if (pattern.containsMatchIn(url)) {
                            Log.v(TAG, "Blocked by pattern: ${pattern.pattern}")
                            val logType = if (isTrackerCandidate) "Tracker" else "Ad"
                            logBlockedRequest(url, requestHost, logType, mainPageUrl ?: "")
                            return true
                        }
                    }
                }
            }
        } else {
            if (FALLBACK_BLOCK_DOMAINS.any { isDomainMatch(requestHost, it) }) {
                val logType = if (isTrackerCandidate) "Tracker" else "Ad"
                logBlockedRequest(url, requestHost, logType, mainPageUrl ?: "")
                return true
            }
        }

        return false
    }

    private fun isCaptchaOrVerification(url: String): Boolean {
        val lower = url.lowercase()
        return CAPTCHA_DOMAINS.any { lower.contains(it) } ||
               CAPTCHA_KEYWORDS.any { lower.contains(it) } ||
               lower.contains("cloudflare.com/cdn-cgi/")
    }

    private fun isCriticalContext(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        return CRITICAL_KEYWORDS.any { lower.contains(it) }
    }

    private fun isStreamingOrVideo(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_EXTENSIONS.any { lower.contains(it) } ||
               VIDEO_DOMAINS.any { getHost(lower).contains(it) }
    }

    fun getHost(url: String?): String {
        if (url == null || url.isBlank()) return ""
        return try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (e: Exception) { "" }
    }

    fun isInDomainSet(host: String, set: Set<String>): Boolean {
        if (host.isEmpty()) return false
        return set.contains(host) || set.any { host == it || host.endsWith(".$it") }
    }

    private fun isDomainBlocked(host: String, blockSet: HashSet<String>): Boolean {
        if (host.isEmpty()) return false
        if (blockSet.contains(host)) return true
        var idx = host.indexOf('.')
        while (idx != -1) {
            val parent = host.substring(idx + 1)
            if (blockSet.contains(parent)) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    private fun isDomainMatch(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }

    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream("".toByteArray()))
    }

    private val FALLBACK_AD_DOMAINS = hashSetOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "adnxs.com", "criteo.com", "outbrain.com",
        "taboola.com", "pubmatic.com", "rubiconproject.com", "adroll.com",
        "propellerads.com", "exoclick.com", "adsterra.com", "popads.net",
        "popcash.net", "mgid.com", "revcontent.com", "zeropark.com",
        "1xbet.com", "bet365.com", "onclickads.net", "adcash.com",
        "chaturbate.com", "livejasmin.com", "trafficjunky.com",
        "go.xlviirdr.com", "woclin.com", "shorte.st", "adf.ly", "ouo.io",
        "pckgupdate.com", "malware-domain.com", "fake-virus-alert.com"
    )

    private val FALLBACK_TRACKER_DOMAINS = hashSetOf(
        "google-analytics.com", "analytics.google.com", "hotjar.com", "mixpanel.com",
        "amplitude.com", "segment.com", "segment.io", "facebook.net",
        "connect.facebook.net", "pixel.facebook.com", "analytics.tiktok.com"
    )

    private val FALLBACK_BLOCK_DOMAINS = HashSet<String>().apply {
        addAll(FALLBACK_AD_DOMAINS)
        addAll(FALLBACK_TRACKER_DOMAINS)
    }

    // Blocked requests log for false positive inspection (Max 50 items)
    data class BlockedRequest(
        val url: String,
        val host: String,
        val type: String, // "Ad" / "Tracker" / "Popup"
        val mainPageUrl: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val blockedLogs = java.util.Collections.synchronizedList(ArrayList<BlockedRequest>())

    fun getBlockedLogsForPage(mainPageUrl: String?): List<BlockedRequest> {
        if (mainPageUrl == null) return emptyList()
        val mainHost = com.yourbrowser.adblock.AlexShieldV5LogStore.normalizeHost(mainPageUrl)
        val legacyMatches = synchronized(blockedLogs) {
            blockedLogs.filter {
                val h = com.yourbrowser.adblock.AlexShieldV5LogStore.normalizeHost(it.mainPageUrl)
                h.isNotEmpty() && (h == mainHost || h.endsWith(".$mainHost") || mainHost.endsWith(".$h"))
            }
        }
        val shieldMatches = com.yourbrowser.adblock.AlexShieldV5LogStore.forPageHost(mainPageUrl).map { log ->
            val blockedHost = getHost(log.blockedUrl)
            val category = com.yourbrowser.adblock.AlexShieldV5LogStore.classifyReason(log.blockedUrl, log.reason)
            BlockedRequest(
                url = log.blockedUrl,
                host = blockedHost,
                type = category,
                mainPageUrl = mainPageUrl,
                timestamp = log.timestamp
            )
        }
        return (legacyMatches + shieldMatches)
            .distinctBy { "${it.url}|${it.timestamp}" }
            .takeLast(100)
    }

    fun clearBlockedLogs() {
        synchronized(blockedLogs) {
            blockedLogs.clear()
        }
        com.yourbrowser.adblock.AlexShieldV5LogStore.clearAll()
    }
    
    fun logBlockedRequestPublic(url: String, host: String, type: String, mainPageUrl: String) {
        logBlockedRequest(url, host, type, mainPageUrl)
    }

    private fun logBlockedRequest(url: String, host: String, type: String, mainPageUrl: String) {
        synchronized(blockedLogs) {
            if (blockedLogs.size >= 50) {
                blockedLogs.removeAt(0)
            }
            blockedLogs.add(BlockedRequest(url, host, type, mainPageUrl))
        }
    }
}


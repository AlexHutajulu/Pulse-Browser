package com.yourbrowser.adblock

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ─── Enums & Models ───────────────────────────────────────────────────────────

enum class PrivacyMode { OFF, BALANCED, AGGRESSIVE }

data class AggressiveMetrics(
    val blockedRequests: Int = 0,
    val hiddenElements: Int = 0,
    val ytAdsSkipped: Int = 0,
    val surrogatesSpoofed: Int = 0,
    val antiAdblockDefused: Int = 0
)

// ─── AdBlockWebViewClient ─────────────────────────────────────────────────────

class AdBlockWebViewClient(
    mode: PrivacyMode = PrivacyMode.BALANCED,
    private val onBlocked: ((blockedUrl: String, reason: String) -> Unit)? = null
) {
    private val currentMode            = AtomicReference(mode)
    private val trackerBlockingEnabled = AtomicBoolean(true)
    private val trackerActiveWhenOff   = AtomicBoolean(false)
    private val streamingBoostEnabled  = AtomicBoolean(false)
    private val pageUrl                = AtomicReference("")
    private val whitelisted            = CopyOnWriteArraySet<String>()
    private val customZapperRules      = ConcurrentHashMap<String, CopyOnWriteArraySet<String>>()
    private val blockedCount           = AtomicInteger(0)
    private val hiddenCount            = AtomicInteger(0)
    private val ytCount                = AtomicInteger(0)
    private val surrogateCount         = AtomicInteger(0)
    private val defusedTrapsCount      = AtomicInteger(0)

    // Decision cache — key = "mode|tracker|stream|pageHost|requestHost|url", value = "" (allowed) or non-empty block reason
    private val cache = android.util.LruCache<String, String>(6_000)

    // ── Public API ────────────────────────────────────────────────────────────

    fun setMode(m: PrivacyMode) {
        if (currentMode.getAndSet(m) != m) {
            cache.evictAll()
        }
    }

    fun setTrackerBlockingEnabled(enabled: Boolean, activeWhenAdBlockOff: Boolean = true) {
        val prevEnabled = trackerBlockingEnabled.getAndSet(enabled)
        val prevActiveOff = trackerActiveWhenOff.getAndSet(activeWhenAdBlockOff)
        if (prevEnabled != enabled || prevActiveOff != activeWhenAdBlockOff) {
            cache.evictAll()
        }
    }

    fun isTrackerBlockingEnabled(): Boolean = trackerBlockingEnabled.get()

    fun setStreamingBoostEnabled(enabled: Boolean) {
        if (streamingBoostEnabled.getAndSet(enabled) != enabled) {
            cache.evictAll()
        }
    }

    fun isStreamingBoostEnabled(): Boolean = streamingBoostEnabled.get()

    fun updateCurrentPageUrl(url: String?) { if (!url.isNullOrBlank()) pageUrl.set(url) }
    fun whitelistHost(host: String) { whitelisted.add(normalizeHost(host)); cache.evictAll() }
    fun clearWhitelist() { whitelisted.clear(); cache.evictAll() }
    fun setWhitelist(hosts: Set<String>) {
        val normalized = hosts.mapNotNull { h -> normalizeHost(h).takeIf { it.isNotEmpty() } }.toSet()
        whitelisted.clear()
        whitelisted.addAll(normalized)
        cache.evictAll()
    }

    fun setCustomZapperSelectors(host: String, selectors: Set<String>) {
        val norm = normalizeHost(host)
        if (norm.isEmpty()) return
        if (selectors.isEmpty()) {
            customZapperRules.remove(norm)
        } else {
            customZapperRules[norm] = CopyOnWriteArraySet(selectors)
        }
    }

    fun getCustomZapperSelectors(host: String): Set<String> {
        val norm = normalizeHost(host)
        return customZapperRules[norm]?.toSet() ?: emptySet()
    }

    fun recordStealthTrapDefused(count: Int = 1) {
        defusedTrapsCount.addAndGet(count)
    }

    fun getAggressiveMetrics() = AggressiveMetrics(
        blockedRequests = blockedCount.get(),
        hiddenElements = hiddenCount.get(),
        ytAdsSkipped = ytCount.get(),
        surrogatesSpoofed = surrogateCount.get(),
        antiAdblockDefused = defusedTrapsCount.get()
    )

    // ── Lifecycle passthroughs ─────────────────────────────────────────────────

    fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        updateCurrentPageUrl(url)
        if (url.isNullOrBlank() || currentMode.get() == PrivacyMode.OFF) return
        val host = safeHost(url)
        if (isWhitelistedHost(host) || isCriticalBypassDomain(host)) return
        view.evaluateJavascript(buildEarlyScript(host), null)
        // Lapisan : pustaka scriptlet + pemanggilan per-aturan
        // dari daftar filter (AbpRuleEngine), dan processor data YouTube.
        // SAFE dicek ulang di sini karena onPageStarted tidak melewati
        // shouldInterceptRequest.
        if (!isSafeDomain(host)) {
            if (scriptletLibraryJs == null) {
                scriptletLibraryJs = loadAssetJs(view, "adblock/scriptlet_library.js")
            }
            val library = scriptletLibraryJs.orEmpty()
            if (library.isNotBlank()) {
                view.evaluateJavascript(library, null)
                val ruleSet = com.example.manager.FilterListManager.getCachedRules()?.ruleSet?.scriptlets
                if (ruleSet != null) {
                    val invocation = com.example.manager.ScriptletEngine.buildInvocationJs(host, ruleSet)
                    if (invocation.isNotBlank()) view.evaluateJavascript(invocation, null)
                }
            }
            if (host.contains("youtube.com") || host.contains("music.youtube.com")) {
                if (youtubeProcessorJs == null) {
                    youtubeProcessorJs = loadAssetJs(view, "adblock/youtube_processor.js")
                }
                val processor = youtubeProcessorJs.orEmpty()
                if (processor.isNotBlank()) view.evaluateJavascript(processor, null)
            }
        }
    }

    // Cache aset JS lapisan (dimuat sekali dari assets, lalu dipakai ulang).
    @Volatile private var scriptletLibraryJs: String? = null
    @Volatile private var youtubeProcessorJs: String? = null

    private fun loadAssetJs(view: WebView, assetPath: String): String {
        return try {
            view.context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    fun onPageFinished(view: WebView, url: String?) {
        updateCurrentPageUrl(url)
        if (url.isNullOrBlank() || currentMode.get() == PrivacyMode.OFF) return
        val host = safeHost(url)
        if (isWhitelistedHost(host) || isCriticalBypassDomain(host)) return
        val css = buildCosmeticCSS(host)
        if (css.isNotBlank()) injectCSS(view, css)
        val zapperCss = buildCustomZapperCSS(host)
        if (zapperCss.isNotBlank()) injectZapperCSS(view, zapperCss)
        view.evaluateJavascript(buildPageScript(host), null)
    }

    fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) {
        // passthrough — tidak ada action khusus
    }

    // ── Main intercept ────────────────────────────────────────────────────────

    fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        val mode = currentMode.get()
        val trackerEnabled = trackerBlockingEnabled.get()
        val trackerOnlyActive = (mode == PrivacyMode.OFF && trackerEnabled && trackerActiveWhenOff.get())
        if (mode == PrivacyMode.OFF && !trackerOnlyActive) return null
        if (request.isForMainFrame) return null

        val url     = request.url?.toString() ?: return null
        val reqHost = request.url?.host?.lowercase(Locale.US) ?: return null
        val pgHost  = safeHost(pageUrl.get())
        val accept  = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value?.lowercase(Locale.US)

        // 1. Whitelisted page or host
        if (isWhitelistedHost(pgHost) || isWhitelistedHost(reqHost)) return null

        // 2. SAFE DOMAINS — harus PERTAMA, tidak boleh dipindah
        if (isSafeDomain(reqHost)) return null

        // 3. Cache check (Thread-safe LRU Cache)
        val streamBoost = streamingBoostEnabled.get()
        val cacheKey = "${mode.ordinal}|$trackerEnabled|$streamBoost|$pgHost|$reqHost|$url"
        cache.get(cacheKey)?.let { cachedReason ->
            if (cachedReason.isNotEmpty()) {
                blockedCount.incrementAndGet()
                onBlocked?.invoke(url, cachedReason)
                return emptyResponse(request.url, accept)
            }
            return null
        }

        val lower = url.lowercase(Locale.US)
        val resType = com.example.manager.AbpRuleEngine.inferType(url, accept)
        val blockReason = checkRules(reqHost, pgHost, lower, mode, trackerEnabled, streamBoost, resType)

        val isFiltersLoaded = com.example.manager.FilterListManager.isLoaded()
        // Keputusan "diizinkan" TIDAK di-cache sebelum daftar filter siap (isLoaded())
        if (blockReason != null || isFiltersLoaded) {
            cache.put(cacheKey, blockReason ?: "")
        }

        return if (blockReason != null) {
            blockedCount.incrementAndGet()
            onBlocked?.invoke(url, blockReason)
            emptyResponse(request.url, accept)
        } else null
    }

    // ── Navigation redirect check ─────────────────────────────────────────────

    fun shouldBlockNavigationRedirect(url: String, mainPageUrl: String, hasGesture: Boolean): Boolean {
        if (currentMode.get() == PrivacyMode.OFF) return false
        val host = safeHost(url)
        val mainHost = safeHost(mainPageUrl)
        if (isSafeDomain(host)) return false
        if (isWhitelistedHost(host)) return false
        if (isCriticalBypassDomain(host)) return false

        // Navigasi dalam situs yang sama dengan gestur user diizinkan
        val isSameSite = (host.isNotEmpty() && mainHost.isNotEmpty()) &&
            (host == mainHost || host.endsWith(".$mainHost") || mainHost.endsWith(".$host"))
        if (hasGesture && isSameSite) return false

        // Blok domain iklan yang dikenal walau ada gestur (scam/slot redirect)
        val isKnownAd = REDIRECT_BLOCK_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            AD_DOMAINS.any { host == it || host.endsWith(".$it") } ||
            (com.example.manager.FilterListManager.getCachedRules()?.ruleSet?.isDomainBlocked(host) == true)

        if (isKnownAd) {
            blockedCount.incrementAndGet()
            onBlocked?.invoke(url, "popup-redirect")
            return true
        }

        if (hasGesture) return false // legitimate cross-site navigation with user gesture
        return false
    }

    // ─── Core rule check ──────────────────────────────────────────────────────

    private fun checkRules(
        reqHost: String,
        pgHost: String,
        lowerUrl: String,
        mode: PrivacyMode,
        trackerEnabled: Boolean,
        streamBoost: Boolean,
        resType: com.example.manager.AbpRuleEngine.ResourceType = com.example.manager.AbpRuleEngine.ResourceType.OTHER
    ): String? {
        val isFirstParty = pgHost.isNotEmpty() &&
            (reqHost == pgHost || reqHost.endsWith(".$pgHost") || pgHost.endsWith(".$reqHost"))

        // Protect video/streaming segments (.m3u8, .ts, .mp4, .m4s, .mpd, .dash) from false-positive blocking
        val isStreamingSegment = lowerUrl.contains(".m3u8") || lowerUrl.contains(".m4s") ||
            lowerUrl.contains(".mpd") || lowerUrl.contains(".dash") ||
            lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".ts") || lowerUrl.endsWith(".webm")
        if (isStreamingSegment) {
            if (streamBoost || !AD_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return null
            }
        }

        val adBlockActive = (mode != PrivacyMode.OFF)

        // 1. Hardcoded explicit ad & tracker domains (always third-party networks)
        if (!isFirstParty) {
            if (adBlockActive && AD_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return "ad-domain"
            }
            if (trackerEnabled && TRACKER_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return "tracker-domain"
            }
        }

        // Protect first-party authentication / checkout / verification flows from path keyword false positives
        val isFirstPartyCriticalFlow = isFirstParty && isCriticalAuthOrPaymentContext(lowerUrl)

        // 2. Explicit ad/tracker path keywords
        if (!isFirstPartyCriticalFlow) {
            if (trackerEnabled && TRACKER_PATH_KEYWORDS.any { lowerUrl.contains(it) }) {
                return "tracker-keyword"
            }
            if (adBlockActive && AD_ONLY_PATH_KEYWORDS.any { lowerUrl.contains(it) }) {
                return "ad-keyword"
            }
        }

        if (!adBlockActive) {
            return null
        }

        // 3. Aggressive mode extras (only on third-party or non-critical explicit ad paths)
        if (mode == PrivacyMode.AGGRESSIVE) {
            if (!isFirstParty && AGGRESSIVE_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") }) {
                return "aggressive-ad"
            }
            if (!isFirstPartyCriticalFlow && AGGRESSIVE_PATH_KEYWORDS.any { lowerUrl.contains(it) }) {
                return "aggressive-keyword"
            }
        }

        // 4. AbpRuleEngine evaluation — berlaku juga untuk first-party (gaya Brave)
        val cachedRules = com.example.manager.FilterListManager.getCachedRules()
        if (cachedRules != null) {
            val match = cachedRules.ruleSet.shouldBlock(lowerUrl, pgHost, resType)
            if (match != null) {
                val isTrackerLike = TRACKER_DOMAINS.any { reqHost == it || reqHost.endsWith(".$it") } ||
                    lowerUrl.contains("analytics") || lowerUrl.contains("tracker") ||
                    lowerUrl.contains("pixel") || lowerUrl.contains("telemetry") ||
                    lowerUrl.contains("beacon") ||
                    TRACKER_PATH_KEYWORDS.any { lowerUrl.contains(it) }
                if (isTrackerLike) {
                    if (trackerEnabled) return "filter-tracker"
                } else {
                    if (adBlockActive) return "filter-rule"
                }
            }
        }

        // 5. Lapisan heuristik  (lapisan) — SETELAH ABP, dan hanya
        //    kalau adBlock aktif. SAFE/whitelist/critical sudah disaring di
        //    shouldInterceptRequest sebelum checkRules dipanggil, dan segmen
        //    streaming sudah dikembalikan di awal fungsi ini. Kata kunci
        //    gambling & pola path di HeuristicsEngine hanya berlaku
        //    third-party, jadi first-party yang sah tidak ikut terblok.
        if (adBlockActive) {
            // Exception ABP menang atas heuristik: kalau URL ini eksplisit
            // diizinkan @@, heuristik tidak boleh memblokirnya.
            val explicitlyAllowed = cachedRules?.ruleSet?.matchesAllowRule(lowerUrl, pgHost, resType) == true
            if (!explicitlyAllowed) {
                val heuristic = HeuristicsEngine.heuristicReason(reqHost, lowerUrl, isFirstParty)
                if (heuristic != null) return heuristic
            }
        }

        return null
    }

    private fun isCriticalAuthOrPaymentContext(lowerUrl: String): Boolean {
        return CRITICAL_FLOW_KEYWORDS.any { lowerUrl.contains(it) }
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

    // ─── Safe domains (NEVER blocked) ────────────────────────────────────────

    private fun isSafeDomain(host: String): Boolean {
        if (SAFE_EXCLUSIONS.any { host == it || host.endsWith(".$it") }) return false
        return SAFE_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    internal fun isCriticalBypassDomain(host: String): Boolean {
        if (host.isEmpty()) return false
        return CRITICAL_BYPASS_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    private fun isWhitelistedHost(host: String): Boolean {
        val n = normalizeHost(host)
        return whitelisted.any { n == it || n.endsWith(".$it") }
    }

    // ─── Stealth CSS injection (Zero detectable ID/attribute footprint) ──────

    private fun injectCSS(view: WebView, css: String) {
        val escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
        view.evaluateJavascript("""
(function(){
  var s=window.__asStyleRef;
  if(!s||!s.isConnected){
    s=document.createElement('style');
    (document.head||document.documentElement).appendChild(s);
    try{Object.defineProperty(window,'__asStyleRef',{value:s,enumerable:false,configurable:true});}catch(e){}
  }
  s.textContent=`$escaped`;
})();""", null)
    }

    fun injectZapperCSS(view: WebView, css: String) {
        val escaped = css.replace("\\", "\\\\").replace("`", "\\`").replace("\${", "\\\${")
        view.evaluateJavascript("""
(function(){
  var s=window.__asZapperRef;
  if(!s||!s.isConnected){
    s=document.createElement('style');
    (document.head||document.documentElement).appendChild(s);
    try{Object.defineProperty(window,'__asZapperRef',{value:s,enumerable:false,configurable:true});}catch(e){}
  }
  s.textContent=`$escaped`;
})();""", null)
    }

    // ─── Early script (sebelum halaman JS jalan) ──────────────────────────────

    private fun buildEarlyScript(hostname: String): String = buildString {
        append(EARLY_CORE_SCRIPT)
        if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
            append("\n").append(YT_EARLY_SCRIPT)
        }
    }

    // ─── Page script (setelah DOM siap) ───────────────────────────────────────

    private fun buildPageScript(hostname: String): String {
        val mode = currentMode.get()
        return buildString {
            append(BASE_CLEANUP_SCRIPT)
            append("\n").append(ANTI_ADBLOCK_NEUTRALIZER_SCRIPT)
            if (mode == PrivacyMode.AGGRESSIVE) {
                append("\n").append(buildVisualAdKiller(aggressive = true))
            } else {
                append("\n").append(buildVisualAdKiller(aggressive = false))
            }
            if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
                append("\n").append(YT_PAGE_SCRIPT)
            }
        }
    }

    // ─── Cosmetic CSS (Stealth offscreen & zero-opacity rather than detectable display:none) ───

    private fun buildCosmeticCSS(hostname: String): String {
        val sel = LinkedHashSet<String>(GLOBAL_COSMETIC)
        if (hostname.contains("youtube.com") || hostname.contains("music.youtube.com")) {
            sel.addAll(YT_COSMETIC)
        }
        DOMAIN_COSMETIC.forEach { (domain, rules) ->
            if (hostname == domain || hostname.endsWith(".$domain")) sel.addAll(rules)
        }
        if (sel.isEmpty()) return ""
        // Gunakan offscreen + clip-path + opacity:0 agar offsetHeight tidak 0 jika belum ter-proxy,
        // namun tetap benar-benar hilang secara visual dan tidak memakan ruang tata letak
        return sel.joinToString(",\n") +
            " { opacity:0 !important; pointer-events:none !important; max-height:1px !important; max-width:1px !important; overflow:hidden !important; position:absolute !important; left:-9999px !important; top:-9999px !important; z-index:-9999 !important; }"
    }

    fun buildCustomZapperCSS(hostname: String): String {
        val norm = normalizeHost(hostname)
        val rules = customZapperRules[norm] ?: return ""
        if (rules.isEmpty()) return ""
        return rules.joinToString(",\n") +
            " { display:none !important; visibility:hidden !important; pointer-events:none !important; }"
    }

    // ─── Stealth Surrogate & Empty Response Builder ───────────────────────────

    fun emptyResponse(uri: Uri?, acceptHeader: String?): WebResourceResponse {
        val fullUrl = uri?.toString().orEmpty().lowercase(Locale.US)
        val path = uri?.path.orEmpty().lowercase(Locale.US)
        val accept = acceptHeader.orEmpty().lowercase(Locale.US)

        val isJsRequest = path.endsWith(".js") ||
            fullUrl.contains("adsbygoogle") ||
            fullUrl.contains("gpt.js") ||
            fullUrl.contains("prebid") ||
            fullUrl.contains("fuckadblock") ||
            fullUrl.contains("blockadblock") ||
            fullUrl.contains("admiral") ||
            fullUrl.contains("pagefair") ||
            fullUrl.contains("fundingchoices") ||
            fullUrl.contains("/pagead/js/") ||
            fullUrl.contains("analytics.js") ||
            fullUrl.contains("fbevents.js") ||
            accept.contains("javascript") ||
            accept.contains("ecmascript")

        val isImageRequest = path.endsWith(".gif") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".svg") ||
            path.endsWith(".ico") ||
            fullUrl.contains("/pixel") ||
            fullUrl.contains("/beacon") ||
            accept.startsWith("image/")

        val isJsonRequest = path.endsWith(".json") ||
            fullUrl.contains("/api/stats/ads") ||
            fullUrl.contains("/youtubei/v1/log_event") ||
            accept.contains("application/json")

        val isCssRequest = path.endsWith(".css") || accept.contains("text/css")

        val mime: String
        val body: ByteArray

        when {
            isJsRequest -> {
                mime = "application/javascript"
                body = getSurrogateJsPayload(fullUrl)
                surrogateCount.incrementAndGet()
            }
            isImageRequest -> {
                mime = "image/gif"
                body = TRANSPARENT_1X1_GIF
                surrogateCount.incrementAndGet()
            }
            isJsonRequest -> {
                mime = "application/json"
                body = SURROGATE_JSON_OK
                surrogateCount.incrementAndGet()
            }
            isCssRequest -> {
                mime = "text/css"
                body = SURROGATE_CSS_EMPTY
            }
            else -> {
                mime = "text/plain"
                body = ByteArray(0)
            }
        }

        // Return 200 OK for all surrogates (JS, GIF, JSON, CSS) so <script onerror> and <img onerror> NEVER fire
        val statusCode = if (body.isEmpty()) 204 else 200
        val reasonPhrase = if (body.isEmpty()) "No Content" else "OK"
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Cache-Control" to "no-store",
            "Timing-Allow-Origin" to "*"
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WebResourceResponse(mime, "utf-8", statusCode, reasonPhrase, headers, ByteArrayInputStream(body))
        } else {
            @Suppress("DEPRECATION")
            WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
        }
    }

    private fun getSurrogateJsPayload(lowerUrl: String): ByteArray {
        return when {
            lowerUrl.contains("adsbygoogle") || lowerUrl.contains("/pagead/") || lowerUrl.contains("googlesyndication") ->
                SURROGATE_ADSBYGOOGLE_JS
            lowerUrl.contains("gpt.js") || lowerUrl.contains("doubleclick") || lowerUrl.contains("pubads") ->
                SURROGATE_GPT_JS
            lowerUrl.contains("fuckadblock") || lowerUrl.contains("blockadblock") || lowerUrl.contains("sniffadblock") ->
                SURROGATE_FUCKADBLOCK_JS
            lowerUrl.contains("prebid") ->
                SURROGATE_PREBID_JS
            lowerUrl.contains("analytics") || lowerUrl.contains("gtag") || lowerUrl.contains("fbevents") ->
                SURROGATE_ANALYTICS_JS
            lowerUrl.contains("inplayer-adx") || lowerUrl.contains("player-adx") ->
                SURROGATE_INPLAYER_ADX_JS
            else ->
                SURROGATE_GENERIC_AD_JS
        }
    }

    private fun normalizeHost(h: String) = h.trim().lowercase(Locale.US).removePrefix("www.")
    private fun safeHost(url: String?) = runCatching { Uri.parse(url ?: "").host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")

    // ─── Rule sets & Stealth Surrogates ────────────────────────────────────────

    companion object {

        // 43-byte valid 1x1 transparent GIF (prevents <img onerror="adblockDetected()">)
        val TRANSPARENT_1X1_GIF: ByteArray = Base64.decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
            Base64.DEFAULT
        )

        private val SURROGATE_JSON_OK = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
        private val SURROGATE_CSS_EMPTY = "/* */".toByteArray(Charsets.UTF_8)

        private val SURROGATE_ADSBYGOOGLE_JS = """
(function(){
  window.google_ad_status=1;
  window.adsbygoogle=window.adsbygoogle||[];
  window.adsbygoogle.loaded=true;
  window.adsbygoogle.push=function(o){return 1;};
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_GPT_JS = """
(function(){
  var noop=function(){};
  var rThis=function(){return this;};
  var slot={addService:rThis,clearCategoryExclusions:rThis,clearTargeting:rThis,defineSizeMapping:rThis,get:function(){return null;},getAdUnitPath:function(){return '';},getAttributeKeys:function(){return[];},getCategoryExclusions:function(){return[];},getDomId:function(){return '';},getResponseInformation:function(){return null;},getSlotElementId:function(){return '';},getSlotId:function(){return{};},getTargeting:function(){return[];},getTargetingKeys:function(){return[];},set:rThis,setCategoryExclusion:rThis,setClickUrl:rThis,setCollapseEmptyDiv:rThis,setForceSafeFrame:rThis,setSafeFrameConfig:rThis,setTargeting:rThis,updateTargetingFromMap:rThis};
  var pubads={addEventListener:rThis,clear:noop,clearCategoryExclusions:rThis,clearTagForChildDirectedTreatment:rThis,clearTargeting:rThis,collapseEmptyDivs:noop,defineOutOfPagePassback:function(){return slot;},definePassback:function(){return slot;},disableInitialLoad:noop,display:noop,enableAsyncRendering:noop,enableSingleRequest:noop,enableSyncRendering:noop,enableVideoAds:noop,get:function(){return null;},getAttributeKeys:function(){return[];},getSlots:function(){return[];},getTargeting:function(){return[];},getTargetingKeys:function(){return[];},isInitialLoadDisabled:function(){return false;},refresh:noop,removeEventListener:function(){return false;},set:rThis,setCategoryExclusion:rThis,setCentering:noop,setCookieOptions:rThis,setForceSafeFrame:rThis,setLocation:rThis,setPublisherProvidedId:rThis,setRequestNonPersonalizedAds:rThis,setSafeFrameConfig:rThis,setTagForChildDirectedTreatment:rThis,setTargeting:rThis,setVideoContent:rThis,updateCorrelator:rThis};
  var gt=window.googletag||{};
  gt.apiReady=true;
  gt.pubadsReady=true;
  gt.cmd=gt.cmd||[];
  gt.cmd.push=function(fn){if(typeof fn==='function'){try{fn();}catch(e){}}return 1;};
  gt.companionAds=function(){return{addEventListener:rThis,enableSyncLoading:noop,setRefreshUnfilledSlots:noop};};
  gt.content=function(){return{addEventListener:rThis,setContent:noop};};
  gt.defineOutOfPageSlot=function(){return slot;};
  gt.defineSlot=function(){return slot;};
  gt.defineUnit=function(){return slot;};
  gt.destroySlots=function(){return true;};
  gt.disablePublisherConsole=noop;
  gt.display=noop;
  gt.enableServices=noop;
  gt.getVersion=function(){return '2025010101';};
  gt.pubads=function(){return pubads;};
  gt.setAdIframeTitle=noop;
  gt.sizeMapping=function(){return{addSize:rThis,build:function(){return[];}};};
  window.googletag=gt;
  while(gt.cmd.length>0){var f=gt.cmd.shift();if(typeof f==='function'){try{f();}catch(e){}}}
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_FUCKADBLOCK_JS = """
(function(){
  function Fab(){}
  Fab.prototype.check=function(l){return true;};
  Fab.prototype.clearEvent=function(){};
  Fab.prototype.emitEvent=function(detected){return this;};
  Fab.prototype.on=function(detected,fn){if(!detected&&typeof fn==='function'){try{fn();}catch(e){}}return this;};
  Fab.prototype.onDetected=function(fn){return this;};
  Fab.prototype.onNotDetected=function(fn){if(typeof fn==='function'){try{fn();}catch(e){}}return this;};
  Fab.prototype.setOption=function(){return this;};
  var inst=new Fab();
  window.FuckAdBlock=window.BlockAdBlock=Fab;
  window.fuckAdBlock=window.blockAdBlock=window.sniffAdBlock=inst;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_PREBID_JS = """
(function(){
  var pb=window.pbjs||{};
  pb.que=pb.que||[];
  pb.cmd=pb.cmd||[];
  pb.libLoaded=true;
  pb.addAdUnits=function(){};
  pb.requestBids=function(o){if(o&&typeof o.bidsBackHandler==='function'){try{o.bidsBackHandler();}catch(e){}}};
  pb.setConfig=function(){};
  pb.getAdserverTargeting=function(){return{};};
  pb.setTargetingForGPTAsync=function(){};
  pb.que.push=function(fn){if(typeof fn==='function'){try{fn();}catch(e){}}return 1;};
  pb.cmd.push=pb.que.push;
  window.pbjs=pb;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_INPLAYER_ADX_JS = """
(function(){
  window.show_adx = 0;
  window.COUNT_VAST = 0;
  window.vastAdx = [];
  window.bannerAdx = [];
  window.funcGetvastAdx = function() { return []; };
  window.funcJWonReadyVAST = function() {};
  window.bannerAdxAllowed = function() { return false; };
  window.hideInplayerBanner = function() {};
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_ANALYTICS_JS = """
(function(){
  var noop=function(){};
  window.ga=window.ga||function(){(window.ga.q=window.ga.q||[]).push(arguments);};
  window.ga.loaded=true;
  window.ga.create=noop;
  window.ga.getByName=function(){return null;};
  window.ga.getAll=function(){return[];};
  window.datalayer=window.dataLayer=window.dataLayer||[];
  window.fbq=window.fbq||function(){};
  window.fbq.loaded=true;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SURROGATE_GENERIC_AD_JS = """
(function(){
  window.google_ad_status=1;
  window.canRunAds=true;
  window.isAdBlockActive=false;
  window.adblock=false;
  window.adBlockEnabled=false;
})();
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private val SAFE_EXCLUSIONS = setOf(
            "adservice.google.com",
            "fundingchoicesmessages.google.com",
            "analytics.google.com",
            "pagead2.googlesyndication.com",
            "googleads.g.doubleclick.net"
        )

        /**
         * SAFE_DOMAINS — tidak pernah diblok dalam kondisi apapun.
         */
        private val SAFE_DOMAINS = setOf(
            "google.com", "google.co.id", "google.co.uk", "google.de", "google.fr",
            "google.co.jp", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "googletagmanager.com",
            "youtube.com", "youtu.be", "ytimg.com", "yt3.ggpht.com",
            "googlevideo.com",
            "accounts.google.com",
            "music.youtube.com",
            // Auth / Captcha / Security
            "recaptcha.net", "hcaptcha.com", "hcaptcha.net",
            "challenges.cloudflare.com", "turnstile.cloudflare.com",
            "arkoselabs.com", "funcaptcha.com", "geetest.com",
            "auth0.com", "okta.com", "onelogin.com",
            // CDN infra
            "cloudflare.com",
            "cdn.jsdelivr.net", "jsdelivr.net",
            "cdnjs.cloudflare.com",
            "unpkg.com",
            "jquery.com",
            "bootstrapcdn.com",
            "fontawesome.com",
            // Social login
            "appleid.apple.com",
            "login.microsoftonline.com", "microsoftonline.com",
            "login.live.com",
            // Payment gateway Indonesia & global
            "midtrans.com", "xendit.co", "doku.com",
            "snap.midtrans.com",
            "paypal.com", "stripe.com", "braintreegateway.com",
            // Media & streaming
            "spotify.com",
            // Common API / infra
            "web.whatsapp.com"
        )

        /** Domain iklan — diblok semua mode */
        private val AD_DOMAINS = setOf(
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com",
            "adnxs.com", "criteo.com", "outbrain.com", "taboola.com",
            "pubmatic.com", "rubiconproject.com", "openx.net", "adroll.com",
            "media.net", "propellerads.com", "exoclick.com", "adsterra.com",
            "popads.net", "popcash.net", "mgid.com", "revcontent.com",
            "zeropark.com", "trafficjunky.com", "go.xlviirdr.com",
            "onclickads.net", "adcash.com",
            "adsymptotic.com", "adcolony.com", "applovin.com",
            "mopub.com", "chartboost.com", "inneractive.com",
            "smartadserver.com", "teads.tv", "adform.net",
            "sharethrough.com", "indexexchange.com", "casalemedia.com",
            "33across.com", "bidswitch.net", "emxdgt.com",
            "contextweb.com", "yldbt.com", "adtelligent.com",
            "adsafeprotected.com", "moatads.com",
            // Anti-adblock recovery / wall providers
            "fundingchoicesmessages.google.com",
            "getadmiral.com", "pagefair.com", "pagefair.net", "blockadblock.com", "fuckadblock.com",
            // Gambling/scam ad networks
            "1xbet.com", "bet365.com",
            "woclin.com", "shorte.st", "adf.ly", "ouo.io"
        )

        /** Tracker — diblok semua mode */
        private val TRACKER_DOMAINS = setOf(
            "hotjar.com", "clarity.ms", "mixpanel.com",
            "amplitude.com", "segment.com", "segment.io",
            "fullstory.com", "logrocket.com", "inspectlet.com",
            "mouseflow.com", "crazyegg.com",
            "scorecardresearch.com", "quantserve.com",
            "pixel.facebook.com",
            "analytics.tiktok.com",
            "ads.linkedin.com", "snap.licdn.com",
            "static.ads-twitter.com"
        )

        /** Domain iklan ekstra untuk mode Aggressive */
        private val AGGRESSIVE_DOMAINS = setOf(
            "amazon-adsystem.com", "adsystem.amazon.com",
            "advertising.com", "everesttech.net",
            "rlcdn.com", "w55c.net",
            "ads-twitter.com"
        )

        private val TRACKER_PATH_KEYWORDS = listOf(
            "/ptracking",
            "/youtubei/v1/log_event",
            "google-analytics.com/collect",
            "google-analytics.com/j/collect",
            "google-analytics.com/g/collect"
        )

        private val AD_ONLY_PATH_KEYWORDS = listOf(
            "/api/stats/ads",
            "/pagead/",
            "/doubleclick/",
            "/adsystem/",
            "/prebid.js",
            "/vast.xml",
            "/vpaid.js",
            "/adserver/ads",
            "/fuckadblock",
            "/blockadblock",
            "/adblock-detector"
        )

        private val CRITICAL_FLOW_KEYWORDS = listOf(
            "/login", "/signin", "/signup", "/register", "/oauth", "/auth/",
            "/checkout", "/payment", "/pembayaran", "/transaksi", "/verify", "/captcha"
        )

        internal val CRITICAL_BYPASS_DOMAINS = setOf(
            "accounts.google.com",
            "recaptcha.net", "hcaptcha.com", "hcaptcha.net",
            "challenges.cloudflare.com", "turnstile.cloudflare.com",
            "arkoselabs.com", "funcaptcha.com", "geetest.com",
            "auth0.com", "okta.com", "onelogin.com",
            "appleid.apple.com", "login.microsoftonline.com", "login.live.com",
            "midtrans.com", "snap.midtrans.com", "xendit.co", "doku.com",
            "paypal.com", "stripe.com", "braintreegateway.com"
        )

        private val AGGRESSIVE_PATH_KEYWORDS = listOf(
            "/advertising/banner",
            "/ads/banner",
            "/adserver/",
            "/adbanner/",
            "/popunder/"
        )

        private val REDIRECT_BLOCK_DOMAINS = setOf(
            "doubleclick.net", "googlesyndication.com", "adnxs.com",
            "taboola.com", "outbrain.com", "exoclick.com",
            "propellerads.com", "adsterra.com", "popads.net"
        )

        // ─── Cosmetic selectors ──────────────────────────────────────────────

        private val GLOBAL_COSMETIC = setOf(
            "ins.adsbygoogle",
            "[data-ad-unit]", "[data-google-query-id]", "[data-adsbygoogle-status]",
            "iframe[src*='doubleclick']", "iframe[src*='googlesyndication']",
            "iframe[src*='adnxs']", "iframe[src*='amazon-adsystem']",
            "iframe[src*='taboola']", "iframe[src*='outbrain']",
            "div[id^='google_ads']", "[class*='adsbygoogle']",
            ".ad-banner", ".ads-banner-container", ".banner-ad",
            ".advertisement-box", ".advertise-here",
            "div[id^='taboola-']", "div[class*='taboola-']",
            ".OUTBRAIN", "div[id^='ob_holder']",
            "div[class*='jackpot-banner']", "div[class*='casino-banner']",
            "div[class*='slot-banner']", "div[class*='judi-banner']",
            "a[class*='floating-ad-banner']"
        )

        private val YT_COSMETIC = setOf(
            ".ytp-ad-overlay-container", ".ytp-ad-text-overlay", ".ytp-ad-image-overlay",
            ".ytp-ad-progress", ".ytp-ad-progress-list",
            "ytd-promoted-sparkles-web-renderer", "ytd-display-ad-renderer",
            "ytd-companion-slot-renderer", "ytd-action-companion-ad-renderer",
            "ytd-promoted-video-renderer", "ytd-search-pyv-renderer",
            "ytd-in-feed-ad-layout-renderer", "ytd-ad-slot-renderer",
            "ytd-statement-banner-renderer", "ytd-promoted-sparkles-text-search-renderer",
            "ytd-banner-promo-renderer", "ytm-promoted-sparkles-web-renderer",
            "#masthead-ad", "#player-ads"
        )

        private val DOMAIN_COSMETIC = mapOf(
            "reddit.com"    to listOf(".promotedlink", "[data-testid='promoted-post']", "shreddit-ad-post"),
            "x.com"         to listOf("[data-testid='placementTracking']"),
            "twitter.com"   to listOf("[data-testid='placementTracking']"),
            "facebook.com"  to listOf("[aria-label='Sponsored']", "[data-pagelet*='sponsored']"),
            "instagram.com" to listOf("[aria-label='Sponsored']")
        )

        // ─── Stealth Early Script (Native Cloaking + Bait Dimension Spoof + SDK Stubs) ───

        val EARLY_CORE_SCRIPT = """
(function(){
  if(window.__asStealthInit) return;
  try{Object.defineProperty(window,'__asStealthInit',{value:true,enumerable:false,configurable:false});}catch(e){window.__asStealthInit=true;}

  // 1. Native Function.prototype.toString Cloaking (Defeats Anti-AdBlock Monkey-Patch Fingerprinting)
  var cloakedFns=typeof WeakMap!=='undefined'?new WeakMap():null;
  var origToString=Function.prototype.toString;
  function cloak(fn,nativeName){
    if(cloakedFns&&typeof fn==='function'){
      cloakedFns.set(fn,'function '+nativeName+'() { [native code] }');
    }
    return fn;
  }
  var wrappedToString=function toString(){
    if(cloakedFns&&cloakedFns.has(this)) return cloakedFns.get(this);
    return origToString.apply(this,arguments);
  };
  cloak(wrappedToString,'toString');
  try{Function.prototype.toString=wrappedToString;}catch(e){}

  // 2. Spoof Global Ad & Anti-AdBlock Objects (Prevents SDK presence checks)
  try{
    window.canRunAds=true;
    window.isAdBlockActive=false;
    window.adblock=false;
    window.adBlockEnabled=false;
    window.google_ad_status=1;
    if(!window.adsbygoogle){
      window.adsbygoogle={loaded:true,push:cloak(function(){return 1;},'push')};
    }else{
      window.adsbygoogle.loaded=true;
    }
    if(!window.fuckAdBlock){
      var Fab=function(){};
      Fab.prototype.check=function(){return true;};
      Fab.prototype.clearEvent=function(){};
      Fab.prototype.on=function(d,f){if(!d&&typeof f==='function'){try{f();}catch(e){}}return this;};
      Fab.prototype.onDetected=function(){return this;};
      Fab.prototype.onNotDetected=function(f){if(typeof f==='function'){try{f();}catch(e){}}return this;};
      Fab.prototype.setOption=function(){return this;};
      var fabInst=new Fab();
      window.FuckAdBlock=window.BlockAdBlock=Fab;
      window.fuckAdBlock=window.blockAdBlock=window.sniffAdBlock=fabInst;
    }
  }catch(e){}

  // 3. DOM Bait / Honeypot Element Dimension & Style Spoofing
  // Anti-AdBlock scripts create hidden bait elements (.adsbox, .ad-banner, .textads, ins.adsbygoogle)
  // and check if offsetHeight === 0 or getComputedStyle(el).display === 'none'.
  var BAIT_RX=/\b(adsbox|ad-banner|banner-ad|textads|banner-ads|ad-unit|ad-zone|ad-space|adsbygoogle|pub_300x250|ad-placeholder|ad-tester|adblock-bait)\b/i;
  function isBaitNode(el){
    if(!el||el.nodeType!==1) return false;
    var cls=typeof el.className==='string'?el.className:'';
    var id=el.id||'';
    return BAIT_RX.test(cls)||BAIT_RX.test(id)||el.tagName==='INS'&&cls.indexOf('adsbygoogle')>-1;
  }
  try{
    var proto=HTMLElement.prototype;
    var dOH=Object.getOwnPropertyDescriptor(proto,'offsetHeight');
    var dOW=Object.getOwnPropertyDescriptor(proto,'offsetWidth');
    var dCH=Object.getOwnPropertyDescriptor(proto,'clientHeight');
    var dCW=Object.getOwnPropertyDescriptor(proto,'clientWidth');
    if(dOH&&dOH.get){
      Object.defineProperty(proto,'offsetHeight',{get:cloak(function(){
        var v=dOH.get.call(this);
        return (v<=1&&isBaitNode(this))?90:v;
      },'get offsetHeight'),configurable:true});
    }
    if(dOW&&dOW.get){
      Object.defineProperty(proto,'offsetWidth',{get:cloak(function(){
        var v=dOW.get.call(this);
        return (v<=1&&isBaitNode(this))?300:v;
      },'get offsetWidth'),configurable:true});
    }
    if(dCH&&dCH.get){
      Object.defineProperty(proto,'clientHeight',{get:cloak(function(){
        var v=dCH.get.call(this);
        return (v<=1&&isBaitNode(this))?90:v;
      },'get clientHeight'),configurable:true});
    }
    if(dCW&&dCW.get){
      Object.defineProperty(proto,'clientWidth',{get:cloak(function(){
        var v=dCW.get.call(this);
        return (v<=1&&isBaitNode(this))?300:v;
      },'get clientWidth'),configurable:true});
    }
    var origGBR=Element.prototype.getBoundingClientRect;
    if(origGBR){
      Element.prototype.getBoundingClientRect=cloak(function(){
        var r=origGBR.apply(this,arguments);
        if(r&&r.height<=1&&isBaitNode(this)){
          return {width:300,height:90,top:10,left:10,right:310,bottom:100,x:10,y:10,toJSON:function(){return this;}};
        }
        return r;
      },'getBoundingClientRect');
    }
    var origGCS=window.getComputedStyle;
    if(origGCS){
      window.getComputedStyle=cloak(function(el,pseudo){
        var st=origGCS.apply(this,arguments);
        if(!pseudo&&isBaitNode(el)&&typeof Proxy!=='undefined'){
          return new Proxy(st,{
            get:function(target,prop){
              if(prop==='display') return 'block';
              if(prop==='visibility') return 'visible';
              if(prop==='opacity') return '1';
              if(prop==='height') return '90px';
              if(prop==='width') return '300px';
              if(prop==='getPropertyValue'){
                return cloak(function(p){
                  if(p==='display') return 'block';
                  if(p==='visibility') return 'visible';
                  if(p==='opacity') return '1';
                  return target.getPropertyValue(p);
                },'getPropertyValue');
              }
              var val=target[prop];
              return typeof val==='function'?val.bind(target):val;
            }
          });
        }
        return st;
      },'getComputedStyle');
    }
  }catch(e){}

  // 4. Cloaked Fetch / XHR / Beacon Interception (Returns 200 OK Surrogates)
  var B=/doubleclick|googlesyndication|adnxs|amazon-adsystem|criteo|taboola|outbrain|hotjar|clarity\.ms|mixpanel|segment\.(io|com)|amplitude|fullstory|scorecardresearch|google-analytics\.com\/(collect|j\/collect|g\/collect)|\/pagead\/|\/ptracking|fundingchoicesmessages|getadmiral\.com/i;
  function bad(u){try{return B.test(String(u||''));}catch(e){return false;}}
  var sb=navigator.sendBeacon&&navigator.sendBeacon.bind(navigator);
  if(sb){
    navigator.sendBeacon=cloak(function(u,d){return bad(u)?true:sb(u,d);},'sendBeacon');
  }
  var of=window.fetch;
  if(of){
    window.fetch=cloak(function(r,i){
      var u=typeof r==='string'?r:(r&&r.url)||'';
      if(bad(u)){
        try{if(window.AlexBrowserNative&&window.AlexBrowserNative.onStealthTrapDefused)window.AlexBrowserNative.onStealthTrapDefused('fetch-surrogate');}catch(e){}
        return Promise.resolve(new Response('{}',{status:200,statusText:'OK',headers:{'Content-Type':'application/json'}}));
      }
      return of.apply(this,arguments);
    },'fetch');
  }
  var xo=XMLHttpRequest.prototype.open;
  if(xo){
    XMLHttpRequest.prototype.open=cloak(function(m,u){
      if(bad(u)){
        u='data:application/json,%7B%22status%22%3A%22ok%22%7D';
      }
      return xo.apply(this,arguments);
    },'open');
  }
  var ow=window.open;
  if(ow){
    window.open=cloak(function(u,t,f){return bad(u)?null:ow.apply(this,arguments);},'open');
  }
})();
        """.trimIndent()

        private val YT_EARLY_SCRIPT = """
(function(){
  if(window.__asYTEarly) return;
  try{Object.defineProperty(window,'__asYTEarly',{value:true,enumerable:false,configurable:false});}catch(e){window.__asYTEarly=true;}
  function wipe(v){if(v){try{v.adPlacements=[];v.playerAds=[];v.adSlots=[];}catch(e){}}return v;}
  function stripFeed(d){
    if(!d) return d;
    try{
      var tabs=d&&d.contents&&d.contents.twoColumnBrowseResultsRenderer&&d.contents.twoColumnBrowseResultsRenderer.tabs;
      if(tabs) tabs.forEach(function(t){
        var c=t&&t.tabRenderer&&t.tabRenderer.content;
        var items=(c&&c.richGridRenderer&&c.richGridRenderer.contents)||(c&&c.sectionListRenderer&&c.sectionListRenderer.contents);
        if(!items) return;
        for(var i=items.length-1;i>=0;i--){
          var x=items[i];
          if(x.promotedSparklesWebRenderer||x.adSlotRenderer||x.searchPyvRenderer
          ||(x.richItemRenderer&&x.richItemRenderer.content&&(x.richItemRenderer.content.adSlotRenderer||x.richItemRenderer.content.promotedSparklesWebRenderer)))
            items.splice(i,1);
        }
      });
    }catch(e){}
    return d;
  }
  try{Object.defineProperty(window,'ytInitialPlayerResponse',{get:function(){return this.__ay;},set:function(v){this.__ay=wipe(v);},configurable:true});}catch(e){}
  try{Object.defineProperty(window,'ytInitialData',{get:function(){return this.__ad;},set:function(v){this.__ad=stripFeed(v);},configurable:true});}catch(e){}
  var pf=window.fetch;
  var YB=['/api/stats/ads','/pagead/','/ptracking','/youtubei/v1/log_event','doubleclick.net/pagead'];
  window.fetch=function(r,i){
    var u=typeof r==='string'?r:(r&&r.url)||'';
    if(YB.some(function(e){return u.indexOf(e)>-1;})) return Promise.resolve(new Response('{}',{status:200}));
    return pf.apply(this,arguments);
  };
})();
        """.trimIndent()

        // ─── Anti-AdBlock Modal / Wall Neutralizer ────────────────────────────

        val ANTI_ADBLOCK_NEUTRALIZER_SCRIPT = """
(function(){
  if(window.__asNeutralizer) return;
  try{Object.defineProperty(window,'__asNeutralizer',{value:true,enumerable:false,configurable:false});}catch(e){window.__asNeutralizer=true;}

  var MODAL_SELS=[
    '.fc-ab-root','#fc-ab-root','.fc-whitelist-root',
    '#babasbmsgx','.adblock-modal','#adblock-modal','.adblocker-modal',
    '[class*="adblock-notice"]','[id*="adblock-notice"]',
    '[class*="adblock-overlay"]','[id*="adblock-overlay"]',
    '.tp-modal','.tp-backdrop'
  ];

  function unlockScroll(){
    try{
      [document.documentElement,document.body].forEach(function(el){
        if(!el) return;
        var st=window.getComputedStyle(el);
        if(st.overflow==='hidden'||el.classList.contains('fc-ab-root')||el.style.overflow==='hidden'){
          el.style.setProperty('overflow','auto','important');
          el.style.setProperty('position','static','important');
        }
      });
    }catch(e){}
  }

  function sweepAntiAdblockModals(){
    var defused=0;
    try{
      MODAL_SELS.forEach(function(sel){
        document.querySelectorAll(sel).forEach(function(el){
          el.remove();
          defused++;
        });
      });
      if(defused>0){
        unlockScroll();
        try{if(window.AlexBrowserNative&&window.AlexBrowserNative.onStealthTrapDefused)window.AlexBrowserNative.onStealthTrapDefused('anti-adblock-modal');}catch(e){}
      }
    }catch(e){}
  }

  sweepAntiAdblockModals();
  var timer=null;
  new MutationObserver(function(){
    if(timer) return;
    timer=setTimeout(function(){timer=null;sweepAntiAdblockModals();},350);
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── Base cleanup script ──────────────────────────────────────────────

        private val BASE_CLEANUP_SCRIPT = """
(function(){
  if(window.__asBasePage) return;
  try{Object.defineProperty(window,'__asBasePage',{value:true,enumerable:false,configurable:false});}catch(e){window.__asBasePage=true;}
  var BADSRC=['doubleclick','googlesyndication','adnxs','amazon-adsystem','rubiconproject','pubmatic','openx','taboola','outbrain','criteo'];
  function badSrc(s){return BADSRC.some(function(x){return(s||'').indexOf(x)>-1;});}
  function clean(root){
    try{
      (root||document).querySelectorAll('iframe[src]').forEach(function(n){if(badSrc(n.src||''))n.remove();});
    }catch(e){}
  }
  clean(document);
  new MutationObserver(function(ms){ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.nodeType===1)clean(n);});});})
    .observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── YouTube skip script ───────────────────────────────────────────────

        private val YT_PAGE_SCRIPT = """
(function(){
  if(window.__AlexYTSkip) return;
  window.__AlexYTSkip=true;
  var busy=false;
  function skip(){
    if(busy) return;
    busy=true;
    try{
      var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
      if(b){b.click();busy=false;return;}
      var c=document.querySelector('.ytp-ad-overlay-close-button,.ytp-ad-feedback-dialog-close-button');
      if(c) c.click();
      var ad=document.querySelector('.ad-showing,.ytp-ad-player-overlay,.ytp-ad-simple-ad-badge,.ytp-ad-text');
      var v=ad?document.querySelector('video'):null;
      if(v&&isFinite(v.duration)&&v.duration>0){v.muted=true;v.currentTime=Math.max(v.duration-0.1,0);}
      document.querySelectorAll('ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,#player-ads,#masthead-ad').forEach(function(n){n.remove();});
    }catch(e){}
    busy=false;
  }
  skip();
  new MutationObserver(function(){skip();})
    .observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class']});
})();
        """.trimIndent()

        // ─── Visual Ad Killer (Stealth Offscreen + Idle-Debounced) ─────────────

        fun buildVisualAdKiller(aggressive: Boolean): String = """
(function(){
  if(window.__asVAK) return;
  try{Object.defineProperty(window,'__asVAK',{value:true,enumerable:false,configurable:false});}catch(e){window.__asVAK=true;}
  var AGG=${aggressive};

  var GAMBLING=['arab777','slot gacor','slot online','casino online',
    'judi online','togel online','betting online','daftar slot',
    'agen slot','main slot','menang slot','bonus slot','spin slot',
    'rtp slot','jackpot slot','scatter hitam','mahjong ways',
    'pragmatic play','pg soft','deposit pulsa slot','maxwin slot',
    'link alternatif slot'];

  var SAFE=['login','signin','sign in','sign up','signup','register',
    'auth','oauth','account','checkout','payment','billing',
    'captcha','recaptcha','hcaptcha','verify','password','username',
    'email','phone','otp','invoice','subscribe','newsletter'];

  var IAB=[[728,90],[320,50],[320,100],[300,250],[336,280],[300,600],
           [160,600],[970,90],[468,60],[250,250],[200,200],[970,250]];

  function safeText(el){
    try{return[el.id||'',el.className||'',el.getAttribute('alt')||'',
      el.getAttribute('title')||'',el.getAttribute('aria-label')||'',
      el.getAttribute('src')||'',el.getAttribute('href')||'',
      el.textContent?el.textContent.substring(0,300):''].join(' ').toLowerCase();}
    catch(e){return'';}
  }

  function isProtected(el){
    try{var n=el;for(var i=0;n&&i<6;i++,n=n.parentElement){
      var t=safeText(n);
      if(SAFE.some(function(w){return t.includes(w);})) return true;
      if(n.tagName==='FORM'||n.tagName==='INPUT'||n.tagName==='SELECT') return true;
    }return false;}catch(e){return true;}
  }

  function hasGambling(el){
    var t=safeText(el);
    return GAMBLING.some(function(p){return t.includes(p);});
  }

  function isIAB(el){
    try{var r=el.getBoundingClientRect();var w=Math.round(r.width);var h=Math.round(r.height);
    if(w<50||h<20) return false;
    return IAB.some(function(s){return Math.abs(w-s[0])<=25&&Math.abs(h-s[1])<=25;});}
    catch(e){return false;}
  }

  function isFixedOverlay(el){
    try{var s=window.getComputedStyle(el);
    if(s.position!=='fixed'&&s.position!=='sticky') return false;
    var r=el.getBoundingClientRect();
    return r.width>=(window.innerWidth||320)*0.5&&r.height>=50;}
    catch(e){return false;}
  }

  // Stealth hide without leaving detectable data-alexshield attributes
  function hide(el){
    try{
      if(!el||isProtected(el)) return;
      el.style.setProperty('opacity','0','important');
      el.style.setProperty('pointer-events','none','important');
      el.style.setProperty('max-height','0px','important');
      el.style.setProperty('overflow','hidden','important');
      el.style.setProperty('position','absolute','important');
      el.style.setProperty('left','-9999px','important');
    }catch(e){}
  }

  function step1(){
    var SELS=['[data-ad-unit]','[data-google-query-id]',
      'iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]',
      'iframe[src*="adnxs"]','iframe[src*="amazon-adsystem"]',
      'div[id^="google_ads"]'];
    SELS.forEach(function(s){
      try{document.querySelectorAll(s).forEach(function(el){if(!isProtected(el))hide(el);});}
      catch(e){}
    });
  }

  function step2(){
    try{['iframe','img'].forEach(function(t){
      document.querySelectorAll(t+'[src]').forEach(function(el){
        if(isIAB(el)&&hasGambling(el)&&!isProtected(el)){
          var c=el.closest('div,section,aside')||el;
          if(!isProtected(c)) hide(c);
        }
      });
    });}catch(e){}
  }

  function step3(){
    try{document.querySelectorAll('div,section,aside').forEach(function(el){
      if(el.children.length>30) return;
      if(isIAB(el)&&hasGambling(el)&&!isProtected(el)) hide(el);
    });}catch(e){}
  }

  function step4(){
    if(!AGG) return;
    try{document.querySelectorAll('div,section').forEach(function(el){
      if(isFixedOverlay(el)&&hasGambling(el)&&!isProtected(el)) hide(el);
    });}catch(e){}
  }

  function runAll(){
    if(document.hidden) return;
    step1();step2();step3();step4();
  }

  if(document.readyState==='loading'){
    document.addEventListener('DOMContentLoaded',runAll,{once:true});
  }else{runAll();}

  var pending=false;
  new MutationObserver(function(){
    if(pending||document.hidden) return;
    pending=true;
    var sched=window.requestIdleCallback||function(cb){setTimeout(cb,450);};
    sched(function(){pending=false;runAll();});
  }).observe(document.documentElement,{childList:true,subtree:true});
})();
        """.trimIndent()

        // ─── Interactive Custom Element Zapper Script ─────────────────────────

        fun buildElementZapperToggleScript(enable: Boolean, sessionToken: String = ""): String {
            val quotedToken = org.json.JSONObject.quote(sessionToken)
            return """
(function(){
  if(!${enable}){
    if(window.__asZapperCleanup) window.__asZapperCleanup();
    return;
  }
  if(window.__asZapperCleanup) window.__asZapperCleanup();
  window.__asZapperActive=true;
  var zapToken=$quotedToken;
  var selectedEl=null;
  var origOutline='';

  function cssPath(el){
    if(!el||el.nodeType!==1) return '';
    if(el.id&&!/^[0-9]/.test(el.id)&&el.id.indexOf(' ')===-1){
      return '#'+CSS.escape(el.id);
    }
    var parts=[];
    var cur=el;
    for(var depth=0;cur&&cur.nodeType===1&&cur.tagName!=='HTML'&&cur.tagName!=='BODY'&&depth<4;depth++){
      var tag=cur.tagName.toLowerCase();
      var cls=typeof cur.className==='string'?cur.className.trim().split(/\s+/).filter(function(c){return c&&c.length<36&&!/^[0-9]/.test(c);}).slice(0,2):[];
      if(cls.length>0){
        parts.unshift(tag+'.'+cls.map(function(c){return CSS.escape(c);}).join('.'));
        break;
      }else{
        var sibs=cur.parentElement?Array.from(cur.parentElement.children).filter(function(x){return x.tagName===cur.tagName;}):[];
        if(sibs.length>1){
          var idx=sibs.indexOf(cur)+1;
          parts.unshift(tag+':nth-of-type('+idx+')');
        }else{
          parts.unshift(tag);
        }
      }
      cur=cur.parentElement;
    }
    return parts.join(' ');
  }

  function clearHighlight(){
    if(selectedEl){
      try{selectedEl.style.outline=origOutline;}catch(e){}
      selectedEl=null;
    }
  }

  function onClickCapture(e){
    if(!window.__asZapperActive||!e||!e.isTrusted) return;
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
    var target=e.target;
    if(!target||target===document.body||target===document.documentElement) return;
    clearHighlight();
    selectedEl=target;
    origOutline=target.style.outline||'';
    target.style.setProperty('outline','3px solid #EA4335','important');
    var sel=cssPath(target);
    try{
      if(window.AlexBrowserNative&&window.AlexBrowserNative.onElementZapperSelected){
        window.AlexBrowserNative.onElementZapperSelected(sel, target.tagName.toLowerCase(), zapToken);
      }
    }catch(err){}
    return false;
  }

  window.__asConfirmZap=function(){
    if(!selectedEl) return '';
    var sel=cssPath(selectedEl);
    var toHide=selectedEl;
    clearHighlight();
    try{toHide.style.setProperty('display','none','important');}catch(e){}
    return sel;
  };

  window.__asZapperCleanup=function(){
    window.__asZapperActive=false;
    zapToken='';
    clearHighlight();
    document.removeEventListener('click',onClickCapture,true);
  };

  document.addEventListener('click',onClickCapture,true);
})();
            """.trimIndent()
        }
    }
}
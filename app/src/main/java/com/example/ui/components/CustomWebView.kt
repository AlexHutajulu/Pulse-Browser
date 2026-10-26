package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.manager.DisplayMode
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.BrowserViewModel

private const val TAG = "CustomWebView"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CustomWebView(
    tab: BrowserTab,
    viewModel: BrowserViewModel,
    adBlockEnabled: Boolean,
    trackerBlockEnabled: Boolean,
    webViewPool: MutableMap<String, WebView>,
    onFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Unit,
    onFullscreenView: ((android.view.View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
    onHideFullscreenView: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!com.example.manager.FilterListManager.isLoaded()) {
            try {
                com.example.manager.FilterListManager.loadRules(context)
            } catch (e: Exception) {
                Log.w("AdBlock", "Failed to load rules on WebView creation: ${e.message}")
            }
        }
    }

    val displayMode by viewModel.displayMode.collectAsState()
    val desktopZoomScale by viewModel.desktopZoomScale.collectAsState()
    val streamingBoostEnabled by viewModel.streamingBoostEnabled.collectAsState()
    val dataSaverEnabled by viewModel.dataSaverEnabled.collectAsState()
    val navigationActions by viewModel.navigationActions.collectAsState()
    val isAppPaused by viewModel.isAppPaused.collectAsState()

    val privacyShieldMode by viewModel.privacyShieldMode.collectAsState()
    val preventWebRtcLeak by viewModel.preventWebRtcLeak.collectAsState()
    val strictWebRtcMode by viewModel.strictWebRtcMode.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val forceWebPageDarkMode by viewModel.forceWebPageDarkMode.collectAsState()
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDarkTheme = when (appTheme) {
        com.example.manager.AppTheme.SYSTEM -> isSystemDark
        com.example.manager.AppTheme.LIGHT -> false
        com.example.manager.AppTheme.DARK -> true
    }

    val devToolsStates by viewModel.devToolsStates.collectAsState()
    val devState = devToolsStates[tab.id]
    val inspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()

    val backgroundPlaybackEnabled by viewModel.backgroundPlaybackEnabled.collectAsState()

    LaunchedEffect(tab.id, inspectToolsEnabled) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        try {
            if (inspectToolsEnabled) {
                webView.addJavascriptInterface(
                    createDevToolsJsBridge(tab.id, viewModel),
                    "AdBlockDevTools"
                )
            } else {
                webView.removeJavascriptInterface("AdBlockDevTools")
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(devState?.isElementPickerActive) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        if (devState?.isElementPickerActive == true) {
            webView.evaluateJavascript(com.example.devtools.DevToolsScriptFactory.installElementPickerScript(), null)
            webView.evaluateJavascript("window.__ADB_DEVTOOLS_PICKER_ACTIVE__ = true;", null)
        } else {
            webView.evaluateJavascript("window.__ADB_DEVTOOLS_PICKER_ACTIVE__ = false;", null)
        }
    }

    val isElementZapperActive by viewModel.isElementZapperActive.collectAsState()
    val zapperSessionToken by viewModel.zapperSessionToken.collectAsState()
    val zapperRulesVersion by viewModel.zapperRulesVersion.collectAsState()

    LaunchedEffect(tab.id, isElementZapperActive, zapperSessionToken) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        webView.evaluateJavascript(
            com.yourbrowser.adblock.AdBlockWebViewClient.buildElementZapperToggleScript(
                enable = isElementZapperActive,
                sessionToken = zapperSessionToken
            ),
            null
        )
    }

    LaunchedEffect(tab.id, tab.url, zapperRulesVersion) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        val host = safeHost(tab.url)
        if (host.isNotBlank()) {
            val rules = viewModel.getCustomZapperRulesForHost(host)
                .map { sanitizeZapperSelector(it) }
                .filter { it.isNotBlank() }
            val css = if (rules.isEmpty()) "" else {
                rules.joinToString(",\n") + " { display:none !important; visibility:hidden !important; pointer-events:none !important; }"
            }
            val quotedCss = org.json.JSONObject.quote(css)
            webView.evaluateJavascript("""
(function(){
  var s=window.__asZapperRef;
  if(!s||!s.isConnected){
    s=document.createElement('style');
    (document.head||document.documentElement).appendChild(s);
    try{Object.defineProperty(window,'__asZapperRef',{value:s,enumerable:false,configurable:true});}catch(e){}
  }
  s.textContent=$quotedCss;
})();
            """.trimIndent(), null)
        }
    }

    LaunchedEffect(tab.id, isAppPaused, backgroundPlaybackEnabled, tab.isPlayingAudio) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        if (isAppPaused && !backgroundPlaybackEnabled && !tab.isPlayingAudio) {
            webView.onPause()
        } else {
            webView.onResume()
        }
    }

    LaunchedEffect(tab.id, tab.isPrivate, privacyShieldMode) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        val shouldAllowThirdPartyCookies = !tab.isPrivate &&
            privacyShieldMode != com.example.manager.PrivacyShieldMode.STRICT
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, shouldAllowThirdPartyCookies)
        webView.settings.domStorageEnabled = !tab.isPrivate
    }

    LaunchedEffect(tab.id, tab.isMuted) {
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        val muteScript = """
            document.querySelectorAll('video, audio').forEach(function(media) {
                media.muted = ${tab.isMuted};
            });
        """.trimIndent()
        webView.evaluateJavascript(muteScript, null)
    }

    AndroidView(
        factory = { context ->
            com.example.manager.FrameMetricsTelemetrySync.validateRenderToken(context)
            webViewPool.getOrPut(tab.id) {
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    com.example.manager.DocumentStartCosmetics.install(this)
                    isFocusable = true
                    isFocusableInTouchMode = true

                    // Set standard DNT: Both Standard and Strict send DNT

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = !tab.isPrivate
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            offscreenPreRaster = false
                        }
                        
                        // Safety: Strict file sandbox access
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        saveFormData = false
                        
                        // Block Mixed Content (HTTP on HTTPS)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        
                        // Phishing and Malware Shield Safe Browsing (Android O+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            try {
                                safeBrowsingEnabled = true
                            } catch (e: Exception) {
                                Log.w(TAG, "Safe Browsing is not supported on this device")
                            }
                        }

                        if (viewModel.displayMode.value != DisplayMode.MOBILE) {
                            userAgentString = desktopUserAgentFromDefault(context)
                            textZoom = viewModel.desktopZoomScale.value.coerceIn(50, 200)
                        } else {
                            textZoom = 100
                        }
                    }

                    // For private/incognito tabs, disable third-party cookies for security isolation
                    // Also disable if in STRICT privacy mode
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(
                        this,
                        !tab.isPrivate && privacyShieldMode != com.example.manager.PrivacyShieldMode.STRICT
                    )

                    // Update properties that can change dynamically using update block
                    // Private tabs always enforce LOAD_NO_CACHE regardless of Data Saver setting
                    settings.loadsImagesAutomatically = !dataSaverEnabled
                    settings.cacheMode = when {
                        tab.isPrivate -> WebSettings.LOAD_NO_CACHE
                        dataSaverEnabled -> WebSettings.LOAD_CACHE_ELSE_NETWORK
                        else -> WebSettings.LOAD_DEFAULT
                    }
                    
                    val currentPageUrlRef = java.util.concurrent.atomic.AtomicReference<String>(tab.url)
                    val alexShieldV5Client = com.yourbrowser.adblock.AdBlockWebViewClient(
                        mode = com.yourbrowser.adblock.PrivacyMode.BALANCED,
                        onBlocked = { blockedUrl, reason ->
                            Log.d(TAG, "AlexShield v5 blocked: $reason | $blockedUrl")
                            if (reason.contains("tracker", ignoreCase = true) ||
                                reason.contains("beacon", ignoreCase = true) ||
                                blockedUrl.contains("analytics", ignoreCase = true) ||
                                blockedUrl.contains("pixel", ignoreCase = true)
                            ) {
                                viewModel.incrementTrackerCookiesBlocked()
                            }
                            if (tab.isPrivate || viewModel.privacyShieldMode.value == com.example.manager.PrivacyShieldMode.STRICT) {
                                viewModel.incrementThirdPartyCookiesBlocked()
                            }
                            val safePageUrl = currentPageUrlRef.get()
                            com.yourbrowser.adblock.AlexShieldV5LogStore.add(
                                tabId = tab.id,
                                pageUrl = safePageUrl,
                                blockedUrl = blockedUrl,
                                reason = reason
                            )
                        }
                    )

                    addJavascriptInterface(object {
                        @Volatile private var lastNewTabTimestamp = 0L
                        @Volatile private var lastTrapReportTimestamp = 0L
                        @Volatile private var lastAudioStateTimestamp = 0L
                        @Volatile private var lastWebRtcToastTimestamp = 0L
                        @Volatile private var lastBlobDownloadTimestamp = 0L

                        @android.webkit.JavascriptInterface
                        fun openUrlInNewTab(url: String) {
                            val trimmed = url.trim()
                            if (trimmed.isBlank() || trimmed.length > 2048) return
                            // Strict scheme allowlist: prevent javascript:, file:, content:, or intent: injection
                            if (!com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(trimmed)) {
                                com.example.manager.SecurityLogger.logValidationError(
                                    "AlexBrowserNative",
                                    "Blocked unsafe scheme in openUrlInNewTab: $trimmed"
                                )
                                return
                            }

                            // Block navigation to detected phishing or malware destinations
                            val threat = com.example.manager.SecurityGuardEngine.inspectUrlThreat(
                                url = trimmed,
                                httpsOnlyEnabled = viewModel.httpsOnlyMode.value,
                                phishingProtectionEnabled = viewModel.phishingProtectionEnabled.value,
                                isUserTrustedHost = false
                            )
                            if (threat.severity == com.example.manager.ThreatSeverity.SUSPICIOUS_PHISHING ||
                                threat.severity == com.example.manager.ThreatSeverity.MALWARE_OR_SCAM
                            ) {
                                com.example.manager.SecurityLogger.logSecurityEvent(
                                    type = com.example.manager.SecurityLogger.EventType.VALIDATION_ERROR,
                                    component = "AlexBrowserNative",
                                    message = "Blocked openUrlInNewTab to threat URL: $trimmed",
                                    severity = "WARNING"
                                )
                                return
                            }

                            // Anti-ad-popup: block automated popunder ad redirects
                            val mainPageUrl = viewModel.tabs.value.find { it.id == tab.id }?.url ?: currentPageUrlRef.get()
                            val blockedByAdGuard = viewModel.adBlockMode.value != com.example.manager.AdBlockMode.OFF &&
                                (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value) &&
                                alexShieldV5Client.shouldBlockNavigationRedirect(trimmed, mainPageUrl, false)
                            if (blockedByAdGuard) {
                                Log.w(TAG, "Blocked automated ad redirect via openUrlInNewTab: $trimmed")
                                return
                            }

                            val now = System.currentTimeMillis()
                            if (now - lastNewTabTimestamp < 1200L) return
                            if (viewModel.tabs.value.size >= 40) return
                            lastNewTabTimestamp = now

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.addNewTab(trimmed, tab.isPrivate)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun onAudioStateChanged(isPlaying: Boolean) {
                            val now = System.currentTimeMillis()
                            if (now - lastAudioStateTimestamp < 150L) return
                            lastAudioStateTimestamp = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.updateTabPlayingAudio(tab.id, isPlaying)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun onStealthTrapDefused(kind: String) {
                            val now = System.currentTimeMillis()
                            if (now - lastTrapReportTimestamp < 300L) return
                            lastTrapReportTimestamp = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                alexShieldV5Client.recordStealthTrapDefused(1)
                                viewModel.recordStealthTrapDefused()
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun onElementZapperSelected(selector: String, tag: String, token: String) {
                            if (!viewModel.isElementZapperActive.value) return
                            val cleanSelector = sanitizeZapperSelector(selector)
                            val cleanTag = tag.trim().lowercase().take(32)
                            val cleanToken = token.trim()
                            if (cleanSelector.isBlank() || cleanToken.isBlank()) return
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.verifyAndSelectZapperElement(cleanToken, cleanSelector, cleanTag)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun setImmersiveFullscreen(enabled: Boolean) {
                            // Exiting fullscreen is always permitted; entering immersive fullscreen
                            // requires an active fullscreen session or recent user Full Screen tool invocation
                            val now = System.currentTimeMillis()
                            val recentToolTrigger = (now - viewModel.fullScreenToolSignal.value) < 15_000L
                            if (enabled && !recentToolTrigger && !viewModel.isImmersivePageFullscreen.value) {
                                Log.w(TAG, "Blocked unprompted setImmersiveFullscreen(true) from web content")
                                return
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                viewModel.setImmersivePageFullscreen(enabled)
                            }
                        }
                        
                        @android.webkit.JavascriptInterface
                        fun onWebRtcBlocked(url: String) {
                            val now = System.currentTimeMillis()
                            if (now - lastWebRtcToastTimestamp < 3000L) return
                            lastWebRtcToastTimestamp = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(
                                    context,
                                    "Koneksi WebRTC diblokir oleh Mode Privasi Ketat. Ubah WebRTC ke 'Standar' di Pengaturan jika panggilan video gagal.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun downloadVerifiedBlob(blobUrl: String, dataUri: String, fileName: String, mimeType: String) {
                            val now = System.currentTimeMillis()
                            if (now - lastBlobDownloadTimestamp < 1000L) return
                            lastBlobDownloadTimestamp = now
                            val activePageUrl = currentPageUrlRef.get()
                            if (!com.example.manager.SecurityGuardEngine.isSafeBlobUrlForContext(blobUrl, activePageUrl)) {
                                Log.w(TAG, "Blocked unverified blob download in JS bridge: $blobUrl (page=$activePageUrl)")
                                return
                            }
                            if (!dataUri.startsWith("data:", ignoreCase = true) ||
                                dataUri.length > com.example.manager.AppDownloadManager.MAX_DATA_URI_LENGTH
                            ) {
                                Log.w(TAG, "Blocked invalid or oversized blob data URI payload")
                                return
                            }
                            // SECURITY FIX (audit 2026-10-26): a web page must not
                            // be able to trigger an executable download through
                            // the JS bridge. Normal user-initiated downloads in
                            // AppDownloadManager are unaffected by this check.
                            val lowerMime = mimeType.trim().lowercase()
                            val lowerName = fileName.trim().lowercase()
                            val blockedExecutable = lowerMime in setOf(
                                "application/vnd.android.package-archive",
                                "application/x-msdownload",
                                "application/x-executable",
                                "application/x-sh",
                                "application/x-msdos-program"
                            ) || lowerName.endsWith(".apk") || lowerName.endsWith(".exe") ||
                                lowerName.endsWith(".dll") || lowerName.endsWith(".bat") ||
                                lowerName.endsWith(".sh") || lowerName.endsWith(".dex")
                            if (blockedExecutable) {
                                Log.w(TAG, "Blocked executable blob download via JS bridge: $lowerName ($lowerMime)")
                                return
                            }
                            val originHost = com.example.manager.SecurityGuardEngine.extractHost(activePageUrl)
                            val cleanName = fileName.ifBlank {
                                android.webkit.URLUtil.guessFileName(blobUrl, null, mimeType)
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                val started = viewModel.downloadManager.startDownload(
                                    url = dataUri,
                                    userAgent = "AlexBrowser/BlobDownload",
                                    fileName = cleanName,
                                    mimeType = mimeType.ifBlank { "application/octet-stream" },
                                    domain = originHost.ifBlank { "blob" },
                                    isIncognito = tab.isPrivate
                                )
                                val msg = if (started) "Downloading..." else "Download blocked: Unsafe URL"
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun getCosmeticCss(host: String): String {
                            if (viewModel.adBlockMode.value == com.example.manager.AdBlockMode.OFF) return ""
                            val cleanHost = host.trim().lowercase().removePrefix("www.")
                            if (cleanHost.isBlank()) return ""
                            val isWhitelisted = viewModel.adBlockDisabledDomains.value.any { d ->
                                val clean = d.trim().lowercase().removePrefix("www.")
                                clean.isNotEmpty() && (cleanHost == clean || cleanHost.endsWith(".$clean"))
                            }
                            if (isWhitelisted) return ""
                            return com.example.manager.DocumentStartCosmetics.buildCosmeticCssForHost(cleanHost)
                        }
                    }, "AlexBrowserNative")

                    // Only attach DevTools JS Bridge when Inspect Tools is explicitly enabled by user
                    if (viewModel.inspectToolsEnabled.value) {
                        addJavascriptInterface(
                            createDevToolsJsBridge(tab.id, viewModel),
                            "AdBlockDevTools"
                        )
                    }
                    
                    webViewClient = object : WebViewClient() {
                        @Volatile private var lastAlexShieldMode: com.yourbrowser.adblock.PrivacyMode? = null
                        @Volatile private var lastAlexShieldWhitelist: Set<String> = emptySet()

                        @Synchronized
                        private fun syncAlexShieldV5State(syncZapperRules: Boolean = false) {
                            val nextMode = when (viewModel.adBlockMode.value) {
                                com.example.manager.AdBlockMode.OFF -> com.yourbrowser.adblock.PrivacyMode.OFF
                                com.example.manager.AdBlockMode.BALANCED -> com.yourbrowser.adblock.PrivacyMode.BALANCED
                                com.example.manager.AdBlockMode.AGGRESSIVE -> com.yourbrowser.adblock.PrivacyMode.AGGRESSIVE
                            }

                            if (nextMode != lastAlexShieldMode) {
                                alexShieldV5Client.setMode(nextMode)
                                com.example.manager.AdBlockServiceWorker.setMode(nextMode)
                                lastAlexShieldMode = nextMode
                            }

                            alexShieldV5Client.setTrackerBlockingEnabled(
                                enabled = viewModel.trackerBlockEnabled.value,
                                activeWhenAdBlockOff = true
                            )
                            alexShieldV5Client.setStreamingBoostEnabled(viewModel.streamingBoostEnabled.value)

                            val nextWhitelist = viewModel.adBlockDisabledDomains.value
                                .mapNotNull { domain ->
                                    domain.trim().lowercase().removePrefix("www.").takeIf { it.isNotBlank() }
                                }
                                .toSet()

                            if (nextWhitelist != lastAlexShieldWhitelist) {
                                alexShieldV5Client.setWhitelist(nextWhitelist)
                                lastAlexShieldWhitelist = nextWhitelist
                            }

                            if (syncZapperRules) {
                                val currentHost = safeHost(currentPageUrlRef.get())
                                if (currentHost.isNotBlank()) {
                                    val zapperRules = viewModel.getCustomZapperRulesForHost(currentHost).toSet()
                                    alexShieldV5Client.setCustomZapperSelectors(currentHost, zapperRules)
                                }
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            currentPageUrlRef.set(url.orEmpty())
                            syncAlexShieldV5State(syncZapperRules = true)
                            if (view != null && !url.isNullOrBlank()) {
                                if (tab.isPrivate) {
                                    view.evaluateJavascript(
                                        com.example.manager.SecurityGuardEngine.buildIncognitoStorageIsolationScript(),
                                        null
                                    )
                                }
                                alexShieldV5Client.onPageStarted(view, url, favicon)
                                com.example.manager.SecurityGuardEngine.injectClientSecurityScript(
                                    webView = view,
                                    blockClipboardHijack = viewModel.blockClipboardHijack.value,
                                    blockHiddenPopups = viewModel.blockHiddenPopups.value
                                )
                                com.example.manager.BrowserExtensionManager.injectMatchingExtensions(
                                    webView = view,
                                    url = url,
                                    extensions = viewModel.extensions.value,
                                    stage = com.example.manager.ExtensionRunAt.DOCUMENT_START,
                                    isPrivate = tab.isPrivate
                                )
                            }
                            viewModel.clearTabActivePermissions(tab.id)
                            viewModel.updateTabPlayingAudio(tab.id, false)
                            viewModel.updateTabLoading(tab.id, true)
                            viewModel.updateTabError(tab.id, null)
                            viewModel.updateTabNavigation(
                                tabId = tab.id,
                                canBack = view?.canGoBack() ?: false,
                                canForward = view?.canGoForward() ?: false
                            )
                            if (!url.isNullOrBlank() && url != "about:blank") {
                                viewModel.updateTabUrl(tab.id, url)
                                if (viewModel.inspectToolsEnabled.value) {
                                    val navLog = com.example.devtools.ConsoleEntry(
                                        id = java.util.UUID.randomUUID().toString(),
                                        timestamp = System.currentTimeMillis(),
                                        level = com.example.devtools.ConsoleLevel.INFO,
                                        message = "--- Navigated to $url ---"
                                    )
                                    viewModel.addConsoleEntry(tab.id, navLog)
                                    viewModel.refreshSecuritySnapshot(tab.id, url, tab.isPrivate)
                                }
                            }
                            
                            // WebRTC Leak Protection & Anti-Fingerprinting with strict domain boundary validation
                            val host = try { android.net.Uri.parse(url ?: "").host?.lowercase() ?: "" } catch (e: Exception) { "" }
                            val isAfpBypass = com.example.manager.SecurityGuardEngine.isAfpBypassDomain(host)
                            val isWhitelisted = viewModel.adBlockDisabledDomains.value.contains(host) || viewModel.adBlockDisabledDomains.value.any { host.endsWith(".$it") }

                            if (preventWebRtcLeak && !isWhitelisted) {
                                val webRtcScript = com.example.manager.SecurityGuardEngine.buildWebRtcProtectionScript(
                                    preventWebRtcLeak = preventWebRtcLeak,
                                    strictWebRtcMode = strictWebRtcMode
                                )
                                if (webRtcScript.isNotEmpty()) {
                                    view?.evaluateJavascript(webRtcScript, null)
                                }
                            }
                            
                            // Anti-Fingerprinting (Compatible / Selective Spoofing aligned with DisplayMode)
                            if (!isWhitelisted && !isAfpBypass && !alexShieldV5Client.isCriticalBypassDomain(host)) {
                                val afpScript = com.example.manager.SecurityGuardEngine.buildAntiFingerprintingScript(
                                    isDesktopMode = viewModel.displayMode.value != DisplayMode.MOBILE,
                                    isStrictMode = privacyShieldMode == com.example.manager.PrivacyShieldMode.STRICT
                                )
                                view?.evaluateJavascript(afpScript, null)
                            } else if (isWhitelisted || isAfpBypass) {
                                // SECURITY FIX (audit 2026-10-26): a protection
                                // bypass must never be silent. Log host only,
                                // never the full URL, so the trade-off is visible
                                // in diagnostics and in the security snapshot.
                                Log.w(TAG, "Protection reduced for host=$host: whitelisted=$isWhitelisted afpBypass=$isAfpBypass")
                            }
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            viewModel.updateTabNavigation(
                                tabId = tab.id,
                                canBack = view?.canGoBack() ?: false,
                                canForward = view?.canGoForward() ?: false
                            )
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            currentPageUrlRef.set(url.orEmpty())
                            viewModel.updateTabLoading(tab.id, false)
                            viewModel.updateTabProgress(tab.id, 100)
                            viewModel.updateTabNavigation(
                                tabId = tab.id,
                                canBack = view?.canGoBack() ?: false,
                                canForward = view?.canGoForward() ?: false
                            )
                            if (url.isNullOrBlank() || url == "about:blank") return

                            viewModel.updateTabUrl(tab.id, url)
                            val title = view?.title ?: ""
                            viewModel.updateTabTitle(tab.id, title)
                            viewModel.handlePageLoadComplete(title, url, tab.isPrivate)
                            
                            // Inject middle-click handler, media audio state tracker, and sync mute state if muted
                            view?.evaluateJavascript("""
                                (function() {
                                    if (!window.__middleClickInjected) {
                                        window.__middleClickInjected = true;
                                        document.addEventListener('auxclick', function(e) {
                                            if (e.button === 1) {
                                                let target = e.target.closest('a');
                                                if (target && target.href) {
                                                    e.preventDefault();
                                                    window.AlexBrowserNative.openUrlInNewTab(target.href);
                                                }
                                            }
                                        });
                                        function syncMediaState() {
                                            try {
                                                var playing = Array.from(document.querySelectorAll('video, audio')).some(function(m) {
                                                    return !m.paused && !m.ended && m.readyState > 2;
                                                });
                                                if (window.AlexBrowserNative && window.AlexBrowserNative.onAudioStateChanged) {
                                                    window.AlexBrowserNative.onAudioStateChanged(playing);
                                                }
                                            } catch(e) {}
                                        }
                                        document.addEventListener('play', syncMediaState, true);
                                        document.addEventListener('pause', syncMediaState, true);
                                        document.addEventListener('ended', syncMediaState, true);
                                    }
                                    if (${tab.isMuted}) {
                                        document.querySelectorAll('video, audio').forEach(function(media) {
                                            media.muted = true;
                                        });
                                    }
                                    var wantsDesktop = ${viewModel.displayMode.value != DisplayMode.MOBILE};
                                    var metaVp = document.querySelector('meta[name="viewport"]');
                                    if (wantsDesktop) {
                                        if (!metaVp) {
                                            metaVp = document.createElement('meta');
                                            metaVp.name = 'viewport';
                                            metaVp.setAttribute('data-created-desktop-vp', '1');
                                            (document.head || document.documentElement).appendChild(metaVp);
                                        } else if (!metaVp.hasAttribute('data-orig-vp')) {
                                            metaVp.setAttribute('data-orig-vp', metaVp.getAttribute('content') || '');
                                        }
                                        metaVp.setAttribute('content', 'width=1280, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes');
                                    } else if (metaVp) {
                                        if (metaVp.hasAttribute('data-created-desktop-vp')) {
                                            metaVp.remove();
                                        } else if (metaVp.hasAttribute('data-orig-vp')) {
                                            metaVp.setAttribute('content', metaVp.getAttribute('data-orig-vp') || 'width=device-width, initial-scale=1.0');
                                            metaVp.removeAttribute('data-orig-vp');
                                        }
                                    }
                                })();
                            """.trimIndent(), null)
                            
                            val disabledDomains = viewModel.adBlockDisabledDomains.value
                            val requestHost = safeHost(url)
                            val isAdBlockDisabledForSite = isHostInSet(requestHost, disabledDomains)
                            
                            val adBlockMode = viewModel.adBlockMode.value
                            if (adBlockMode != com.example.manager.AdBlockMode.OFF && !isAdBlockDisabledForSite && view != null) {
                                // v5 page cleanup: aggressive CSS, anti-popup, anti-beacon, safe YouTube cleanup.
                                alexShieldV5Client.onPageFinished(view, url)
                                com.example.manager.CosmeticFilterInjector.inject(view, pageUrl = url)
                                Log.d("AlexShield", alexShieldV5Client.getAggressiveMetrics().toString())
                            }

                            // Re-ensure client security shield, Auto-Run Custom JS Toolbar scripts & Browser Extensions
                            if (view != null) {
                                com.example.manager.SecurityGuardEngine.injectClientSecurityScript(
                                    webView = view,
                                    blockClipboardHijack = viewModel.blockClipboardHijack.value,
                                    blockHiddenPopups = viewModel.blockHiddenPopups.value
                                )
                                com.example.manager.CustomJsToolbarManager.runAutoScriptsForUrl(
                                    webView = view,
                                    url = url,
                                    scripts = viewModel.customJsScripts.value,
                                    isPrivate = tab.isPrivate
                                )
                                com.example.manager.BrowserExtensionManager.injectMatchingExtensions(
                                    webView = view,
                                    url = url,
                                    extensions = viewModel.extensions.value,
                                    stage = com.example.manager.ExtensionRunAt.DOCUMENT_END,
                                    isPrivate = tab.isPrivate
                                )
                            }

                            if (adBlockMode != com.example.manager.AdBlockMode.OFF && !isAdBlockDisabledForSite && view != null) {
                                // Anti-Blank Page Check for Aggressive modes
                                if (adBlockMode == com.example.manager.AdBlockMode.AGGRESSIVE) {
                                    val antiBlankJs = """
                                        (function() {
                                            try {
                                                let h = document.body ? document.body.offsetHeight : 0;
                                                let nodes = document.body ? document.body.querySelectorAll('*').length : 0;
                                                let txt = document.body ? document.body.innerText.trim().length : 0;
                                                // Identify common blank page characteristics
                                                if (h < 10 && nodes < 5 && txt < 5) {
                                                    // Give it a tiny bit of time in case it's a dynamic SPA
                                                    return "BLANK";
                                                }
                                                return "OK";
                                            } catch(e) { return "ERROR"; }
                                        })();
                                    """.trimIndent()
                                    
                                    view.postDelayed({
                                        view.evaluateJavascript(antiBlankJs) { result ->
                                            if (result == "\"BLANK\"") {
                                                viewModel.handleBlankPageDetected(url, view.context)
                                            }
                                        }
                                    }, 1500)
                                }
                            }
                        }

                        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            if (detail != null && detail.didCrash()) {
                                Log.e(TAG, "WebView crash detected! Recovering...")
                            } else {
                                Log.w(TAG, "WebView killed by OS (OOM)")
                            }
                            viewModel.updateTabError(tab.id, "Tab crashed. Refresh to restore.")
                            return true // Indicates we handled it, prevent app crash
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (view != null && request != null && error != null) {
                                alexShieldV5Client.onReceivedError(view, request, error)
                            }

                            super.onReceivedError(view, request, error)

                            if (request?.isForMainFrame == true) {
                                val description = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    error?.description?.toString()
                                } else {
                                    "Connection validation failed"
                                }
                                Log.e(TAG, "WebView error: $description for url [redacted]")
                                viewModel.updateTabError(tab.id, description ?: "Connection validation failed")
                                viewModel.updateTabLoading(tab.id, false)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            // High security: Do not bypass SSL errors.
                            val errorDesc = error?.toString() ?: "Security Error: Blocked Invalid SSL Certificate"
                            Log.e(TAG, "SSL Error blocked: $errorDesc")
                            handler?.cancel() // Cancel the request.
                            viewModel.updateTabError(tab.id, "Security Error: Blocked Invalid SSL Certificate")
                            viewModel.updateLastSslError(tab.id, errorDesc)
                        }

                        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O_MR1)
                        override fun onSafeBrowsingHit(
                            view: WebView?,
                            request: WebResourceRequest?,
                            threatType: Int,
                            callback: android.webkit.SafeBrowsingResponse?
                        ) {
                            // Malware/Phishing detected by Safe Browsing API
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                callback?.showInterstitial(true) // Show Safe Browsing warning
                            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                callback?.backToSafety(true)
                            }
                            val threatLabel = when(threatType) {
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_MALWARE -> "Malware"
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_PHISHING -> "Phishing / Social Engineering"
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "Unwanted Software"
                                android.webkit.WebViewClient.SAFE_BROWSING_THREAT_BILLING -> "Billing Abuse"
                                else -> "Unknown General Threat"
                            }
                            Log.w(TAG, "Safe Browsing Blocked: $threatLabel")
                            viewModel.updateTabError(tab.id, "Security Warning: $threatLabel Detected by Safe Browsing!")
                            viewModel.updateLastSafeBrowsingThreat(tab.id, "$threatLabel (type ID: $threatType)")
                        }

                        // Adblock and tracker interceptors
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url?.toString()
                            
                            var entryId: String? = null
                            if (viewModel.inspectToolsEnabled.value && requestUrl != null) {
                                val sanitizedHeaders = com.example.devtools.DevToolsSanitizer.sanitizeHeaders(request.requestHeaders ?: emptyMap())
                                val resType = com.example.devtools.DevToolsResourceClassifier.classify(requestUrl)
                                entryId = viewModel.recordNetworkRequest(
                                    tabId = tab.id,
                                    url = requestUrl,
                                    method = request.method ?: "GET",
                                    isMainFrame = request.isForMainFrame,
                                    hasGesture = request.hasGesture(),
                                    headers = sanitizedHeaders,
                                    resourceType = resType
                                )
                            }

                            val latestTab = viewModel.tabs.value.find { it.id == tab.id }
                            val mainPageUrl = latestTab?.url ?: currentPageUrlRef.get()
                            alexShieldV5Client.updateCurrentPageUrl(mainPageUrl)

                            val currentMode = viewModel.adBlockMode.value
                            val trackerActive = viewModel.trackerBlockEnabled.value
                            // Primary AlexShield Ultra v5/v6 request blocking & Stealth Surrogate engine.
                            syncAlexShieldV5State(syncZapperRules = false)
                            if (view != null && request != null) {
                                if (currentMode != com.example.manager.AdBlockMode.OFF || trackerActive) {
                                    val v5Blocked = alexShieldV5Client.shouldInterceptRequest(view, request)
                                    if (v5Blocked != null) {
                                        if (entryId != null) {
                                            viewModel.updateNetworkBlocked(tab.id, entryId, "Blocked by AlexShield")
                                        }
                                        return v5Blocked
                                    }
                                }

                                // Strict Fingerprinting Script Protection (returns 200 OK JS surrogate to prevent onerror detection)
                                val pageHost = safeHost(mainPageUrl)
                                val isPageWhitelisted = isHostInSet(pageHost, lastAlexShieldWhitelist)
                                if (!isPageWhitelisted &&
                                    viewModel.privacyShieldMode.value == com.example.manager.PrivacyShieldMode.STRICT &&
                                    requestUrl != null &&
                                    (requestUrl.contains("fingerprint2") || requestUrl.contains("/fp.js") || requestUrl.contains("clientjs"))
                                ) {
                                    viewModel.incrementFingerprintingBlocked()
                                    if (entryId != null) {
                                        viewModel.updateNetworkBlocked(tab.id, entryId, "Blocked by Strict Privacy Shield (Fingerprinting protection)")
                                    }
                                    return alexShieldV5Client.emptyResponse(request.url, "application/javascript")
                                }
                            }

                            if (entryId != null) {
                                viewModel.updateNetworkAllowed(tab.id, entryId, statusCode = 200, mimeType = null)
                            }

                            return super.shouldInterceptRequest(view, request)
                        }

                        // Prevent arbitrary app-deep-linking exploits to hijack native flows, and block ad redirects
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val hasGesture = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) request.hasGesture() else true

                            // 1. Allow blob: preview ONLY when verified to originate from the active HTTPS page origin
                            if (url.startsWith("blob:", ignoreCase = true)) {
                                val activePageUrl = view?.url ?: currentPageUrlRef.get()
                                if (com.example.manager.SecurityGuardEngine.isSafeBlobUrlForContext(url, activePageUrl)) {
                                    return false
                                }
                                Log.w(TAG, "Blocked unverified blob: navigation: $url (activePage=$activePageUrl)")
                                return true
                            }

                            // 2. Block dangerous internal/executable schemes (file://, content://, javascript:, data:, etc.)
                            if (com.example.manager.SecurityGuardEngine.isDangerousInternalScheme(url)) {
                                Log.w(TAG, "Blocked dangerous internal scheme navigation: $url")
                                return true
                            }

                            // 2. Validate external app schemes & Intent redirects
                            if (com.example.manager.SecurityGuardEngine.isAllowedExternalScheme(url)) {
                                if (!hasGesture && (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value)) {
                                    Log.w(TAG, "Blocked unprompted external intent without user gesture: $url")
                                    return true
                                }
                                viewModel.requestOpenIntent(url)
                                return true
                            }

                            // Block any other unknown non-HTTP(S) scheme
                            if (!url.startsWith("https://", ignoreCase = true) &&
                                !url.startsWith("http://", ignoreCase = true)
                            ) {
                                Log.w(TAG, "Blocked unsupported navigation scheme: $url")
                                return true
                            }
                            
                            // 3. Main-frame Security Guard (HTTPS-Only upgrade, Phishing detector, Ad Redirect blocker)
                            if (request.isForMainFrame) {
                                val targetHost = com.example.manager.SecurityGuardEngine.extractHost(url)
                                val isTrustedHost = com.example.manager.SecurityGuardEngine.isHostUserTrusted(view?.context ?: context, targetHost)

                                // Auto-upgrade HTTP to HTTPS when HTTPS-Only is enabled
                                if (viewModel.httpsOnlyMode.value && !isTrustedHost && url.startsWith("http://", ignoreCase = true)) {
                                    val upgraded = com.example.manager.SecurityGuardEngine.upgradeUrlIfHttpsOnly(url, true)
                                    if (upgraded != url) {
                                        view?.loadUrl(upgraded, mapOf("DNT" to "1"))
                                        return true
                                    }
                                }

                                // Real-time Phishing, Homograph (xn--), Malware & Insecure HTTP check
                                val threat = com.example.manager.SecurityGuardEngine.inspectUrlThreat(
                                    url = url,
                                    httpsOnlyEnabled = viewModel.httpsOnlyMode.value,
                                    phishingProtectionEnabled = viewModel.phishingProtectionEnabled.value,
                                    isUserTrustedHost = isTrustedHost
                                )
                                if (threat.severity != com.example.manager.ThreatSeverity.SAFE) {
                                    view?.stopLoading()
                                    viewModel.reportTabSecurityThreat(tab.id, threat)
                                    return true
                                }

                                val latestTab = viewModel.tabs.value.find { it.id == tab.id }
                                val mainPageUrl = latestTab?.url ?: ""
                                val currentMode = viewModel.adBlockMode.value
                                
                                if (currentMode != com.example.manager.AdBlockMode.OFF && (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value)) {
                                    syncAlexShieldV5State()
                                    if (alexShieldV5Client.shouldBlockNavigationRedirect(url, mainPageUrl, hasGesture)) {
                                        Log.w(TAG, "Blocked Ad Redirect: $url")
                                        return true // Drop navigation entirely instead of showing empty page
                                    }
                                }
                            }
                            
                            return false
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            if (view == null) return false

                            // Anti-scam: block popups without user gesture
                            if (!isUserGesture && (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value)) {
                                Log.w(TAG, "Blocked popup without user gesture")
                                return false
                            }

                            val handled = java.util.concurrent.atomic.AtomicBoolean(false)
                            val newWebView = WebView(view.context).apply {
                                settings.apply {
                                    javaScriptEnabled = false
                                    domStorageEnabled = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }
                            }
                            newWebView.webViewClient = object : WebViewClient() {
                                private fun openAndDestroy(targetView: WebView?, url: String?): Boolean {
                                    if (!url.isNullOrBlank() && url != "about:blank" && handled.compareAndSet(false, true)) {
                                        val trimmedUrl = url.trim()
                                        if (com.example.manager.SecurityGuardEngine.isDangerousInternalScheme(trimmedUrl)) {
                                            Log.w(TAG, "Blocked dangerous popup internal scheme: $trimmedUrl")
                                        } else if (com.example.manager.SecurityGuardEngine.isAllowedExternalScheme(trimmedUrl)) {
                                            if (isUserGesture) {
                                                viewModel.requestOpenIntent(trimmedUrl)
                                            }
                                        } else if (com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(trimmedUrl)) {
                                            val targetHost = com.example.manager.SecurityGuardEngine.extractHost(trimmedUrl)
                                            val isTrustedHost = com.example.manager.SecurityGuardEngine.isHostUserTrusted(view.context, targetHost)
                                            val finalPopupUrl = if (isTrustedHost) {
                                                trimmedUrl
                                            } else {
                                                com.example.manager.SecurityGuardEngine.upgradeUrlIfHttpsOnly(
                                                    trimmedUrl,
                                                    viewModel.httpsOnlyMode.value
                                                )
                                            }
                                            val mainPageUrl = viewModel.tabs.value.find { it.id == tab.id }?.url ?: currentPageUrlRef.get()
                                            val blockedByAdGuard = viewModel.adBlockMode.value != com.example.manager.AdBlockMode.OFF &&
                                                (viewModel.blockSuspiciousRedirects.value || viewModel.blockHiddenPopups.value) &&
                                                alexShieldV5Client.shouldBlockNavigationRedirect(finalPopupUrl, mainPageUrl, isUserGesture)

                                            if (blockedByAdGuard) {
                                                Log.w(TAG, "Blocked ad popup redirect: $finalPopupUrl")
                                            } else if (viewModel.openPopupsInNewTab.value) {
                                                viewModel.addNewTab(finalPopupUrl, isPrivate = tab.isPrivate)
                                            } else {
                                                val threat = com.example.manager.SecurityGuardEngine.inspectUrlThreat(
                                                    url = finalPopupUrl,
                                                    httpsOnlyEnabled = viewModel.httpsOnlyMode.value,
                                                    phishingProtectionEnabled = viewModel.phishingProtectionEnabled.value,
                                                    isUserTrustedHost = isTrustedHost
                                                )
                                                if (threat.severity != com.example.manager.ThreatSeverity.SAFE) {
                                                    viewModel.reportTabSecurityThreat(tab.id, threat)
                                                } else {
                                                    view.loadUrl(finalPopupUrl, mapOf("DNT" to "1"))
                                                }
                                            }
                                        } else {
                                            Log.w(TAG, "Blocked unsafe popup scheme: $trimmedUrl")
                                        }
                                        targetView?.stopLoading()
                                        targetView?.destroy()
                                        return true
                                    }
                                    return false
                                }

                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return openAndDestroy(view, request?.url?.toString())
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    openAndDestroy(view, url)
                                }
                            }

                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = newWebView
                                resultMsg.sendToTarget()
                                // Prevent orphaned WebView leak if popup stays on about:blank
                                newWebView.postDelayed({
                                    if (handled.compareAndSet(false, true)) {
                                        try {
                                            newWebView.stopLoading()
                                            newWebView.destroy()
                                        } catch (_: Exception) {}
                                    }
                                }, 8000L)
                                return true
                            }
                            newWebView.destroy()
                            return false
                        }

                        override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                            if (request == null) return
                            val origin = request.origin?.toString().orEmpty()

                            // Strict Camera & Microphone Blocker
                            if (viewModel.blockCameraMicStrict.value &&
                                (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) ||
                                    request.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            ) {
                                request.deny()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Akses Kamera/Mikrofon dari $origin diblokir oleh Perisai Keamanan",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return
                            }
                            
                            val rawTypes = mutableListOf<com.example.manager.SitePermissionType>()
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) rawTypes.add(com.example.manager.SitePermissionType.CAMERA)
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) rawTypes.add(com.example.manager.SitePermissionType.MICROPHONE)
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) rawTypes.add(com.example.manager.SitePermissionType.PROTECTED_MEDIA)
                            if (request.resources.contains(android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX)) rawTypes.add(com.example.manager.SitePermissionType.MIDI)

                            val eligibleTypes = rawTypes.filter {
                                com.example.manager.PermissionManager.isOriginEligibleForPermission(origin, it)
                            }

                            if (eligibleTypes.isEmpty()) {
                                request.deny()
                                return
                            }

                            viewModel.requestSitePermission(origin, eligibleTypes, tab.isPrivate) { granted ->
                                val resToGrant = mutableListOf<String>()
                                if (granted.contains(com.example.manager.SitePermissionType.CAMERA)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                                if (granted.contains(com.example.manager.SitePermissionType.MICROPHONE)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                                if (granted.contains(com.example.manager.SitePermissionType.PROTECTED_MEDIA)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                                if (granted.contains(com.example.manager.SitePermissionType.MIDI)) resToGrant.add(android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX)

                                if (resToGrant.isNotEmpty()) {
                                    request.grant(resToGrant.toTypedArray())
                                    resToGrant.forEach { res ->
                                        if (res == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) viewModel.addActivePermission(tab.id, com.example.manager.SitePermissionType.CAMERA)
                                        if (res == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE) viewModel.addActivePermission(tab.id, com.example.manager.SitePermissionType.MICROPHONE)
                                    }
                                } else {
                                    request.deny()
                                }
                            }
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: android.webkit.GeolocationPermissions.Callback?
                        ) {
                            if (origin == null || callback == null) return
                            if (!com.example.manager.PermissionManager.isOriginEligibleForPermission(origin, com.example.manager.SitePermissionType.LOCATION)) {
                                callback.invoke(origin, false, false)
                                return
                            }
                            
                            viewModel.requestSitePermission(origin, listOf(com.example.manager.SitePermissionType.LOCATION), tab.isPrivate) { granted ->
                                if (granted.contains(com.example.manager.SitePermissionType.LOCATION)) {
                                    callback.invoke(origin, true, false)
                                    viewModel.addActivePermission(tab.id, com.example.manager.SitePermissionType.LOCATION)
                                } else {
                                    callback.invoke(origin, false, false)
                                }
                            }
                        }

                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            if (viewModel.inspectToolsEnabled.value) {
                                consoleMessage?.let {
                                    val level = when (it.messageLevel()) {
                                        android.webkit.ConsoleMessage.MessageLevel.LOG -> com.example.devtools.ConsoleLevel.LOG
                                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> com.example.devtools.ConsoleLevel.ERROR
                                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> com.example.devtools.ConsoleLevel.WARNING
                                        android.webkit.ConsoleMessage.MessageLevel.TIP -> com.example.devtools.ConsoleLevel.INFO
                                        else -> com.example.devtools.ConsoleLevel.INFO
                                    }
                                    val boundedMsg = (it.message() ?: "").take(BrowserViewModel.MAX_CONSOLE_MESSAGE_LENGTH)
                                    val boundedSrc = it.sourceId()?.take(BrowserViewModel.MAX_CONSOLE_SOURCE_LENGTH)
                                    viewModel.addConsoleEntry(tab.id, com.example.devtools.ConsoleEntry(
                                        id = java.util.UUID.randomUUID().toString(),
                                        timestamp = System.currentTimeMillis(),
                                        level = level,
                                        message = boundedMsg,
                                        lineNumber = it.lineNumber(),
                                        sourceId = boundedSrc
                                    ))
                                }
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }

                        private var lastReportedProgress = 0

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (newProgress == 100 || newProgress == 0 || kotlin.math.abs(newProgress - lastReportedProgress) >= 5) {
                                lastReportedProgress = newProgress
                                viewModel.updateTabProgress(tab.id, newProgress)
                            }
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            if (!title.isNullOrBlank() && title != "about:blank") {
                                viewModel.updateTabTitle(tab.id, title)
                            }
                        }

                        override fun onShowFileChooser(
                            customWebView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            onFileChooser(filePathCallback, fileChooserParams)
                            return true
                        }

                        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                            if (view != null && callback != null) {
                                viewModel.setFullscreen(true)
                                onFullscreenView?.invoke(view, callback)
                            } else {
                                super.onShowCustomView(view, callback)
                            }
                        }

                        override fun onHideCustomView() {
                            viewModel.setFullscreen(false)
                            onHideFullscreenView?.invoke()
                            super.onHideCustomView()
                        }
                    }

                    // Setup fast internet download listener
                    setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                        if (url.startsWith("blob:", ignoreCase = true)) {
                            val activePageUrl = this.url ?: currentPageUrlRef.get()
                            if (com.example.manager.SecurityGuardEngine.isSafeBlobUrlForContext(url, activePageUrl)) {
                                val quotedBlobUrl = org.json.JSONObject.quote(url)
                                val quotedFileName = org.json.JSONObject.quote(fileName)
                                val quotedMime = org.json.JSONObject.quote(mimetype ?: "application/octet-stream")
                                val blobExtractorJs = """
                                    (function() {
                                        fetch($quotedBlobUrl).then(function(r) { return r.blob(); }).then(function(b) {
                                            if (b.size > 15 * 1024 * 1024) return;
                                            var reader = new FileReader();
                                            reader.onloadend = function() {
                                                if (typeof reader.result === 'string' && window.AlexBrowserNative && window.AlexBrowserNative.downloadVerifiedBlob) {
                                                    window.AlexBrowserNative.downloadVerifiedBlob($quotedBlobUrl, reader.result, $quotedFileName, b.type || $quotedMime);
                                                }
                                            };
                                            reader.readAsDataURL(b);
                                        }).catch(function(e) {});
                                    })();
                                """.trimIndent()
                                this.evaluateJavascript(blobExtractorJs, null)
                            } else {
                                android.widget.Toast.makeText(context, "Download blocked: Unsafe URL", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@setDownloadListener
                        }
                        val started = viewModel.downloadManager.startDownload(
                            url = url,
                            userAgent = userAgent,
                            fileName = fileName,
                            mimeType = mimetype,
                            domain = safeHost(url),
                            isIncognito = tab.isPrivate
                        )
                        val msg = if (started) "Downloading..." else "Download blocked: Unsafe URL"
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }

                    setOnCreateContextMenuListener { menu, _, _ ->
                        val hitResult = this.hitTestResult
                        val hitUrl = hitResult.extra
                        
                        menu.setHeaderTitle(if (!hitUrl.isNullOrEmpty()) hitUrl else "Actions")
                        
                        // Standard Navigation
                        menu.add("Back").setOnMenuItemClickListener {
                            if (this.canGoBack()) this.goBack()
                            true
                        }
                        menu.add("Forward").setOnMenuItemClickListener {
                            if (this.canGoForward()) this.goForward()
                            true
                        }
                        menu.add("Reload").setOnMenuItemClickListener {
                            this.reload()
                            true
                        }
                        
                        // Link specific actions
                        if (hitResult.type == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE || hitResult.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                            if (!hitUrl.isNullOrEmpty() && com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(hitUrl)) {
                                menu.add("Copy link").setOnMenuItemClickListener {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", hitUrl))
                                    android.widget.Toast.makeText(context, "Link Copied", android.widget.Toast.LENGTH_SHORT).show()
                                    true
                                }
                                menu.add("Open link in new tab").setOnMenuItemClickListener {
                                    viewModel.addNewTab(hitUrl, isPrivate = tab.isPrivate)
                                    true
                                }
                            }
                        }
                        
                        // Image specific actions
                        if (hitResult.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE || hitResult.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                            if (!hitUrl.isNullOrEmpty() && com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(hitUrl)) {
                                menu.add("Save image").setOnMenuItemClickListener {
                                    val fileName = android.webkit.URLUtil.guessFileName(hitUrl, null, "image/*")
                                    viewModel.downloadManager.startDownload(
                                        url = hitUrl,
                                        userAgent = this.settings.userAgentString,
                                        fileName = fileName,
                                        mimeType = "image/*",
                                        domain = safeHost(hitUrl),
                                        isIncognito = tab.isPrivate
                                    )
                                    android.widget.Toast.makeText(context, "Downloading image...", android.widget.Toast.LENGTH_SHORT).show()
                                    true
                                }
                            }
                        }
                        
                        if (viewModel.inspectToolsEnabled.value) {
                            menu.add("Inspect element").setOnMenuItemClickListener {
                                viewModel.openDevTools(tab.id, com.example.devtools.DevToolsTab.ELEMENTS)
                                viewModel.setElementPickerActive(tab.id, true)
                                true
                            }
                        }
                    }

                    if (tab.url.isNotBlank() && tab.url != "about:blank" &&
                        navigationActions[tab.id] == null &&
                        com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(tab.url)
                    ) {
                        val headers = mapOf("DNT" to "1")
                        loadUrl(tab.url, headers)
                    }
                }
            }.apply {
                // Detach from prior parent if reused
                (parent as? ViewGroup)?.removeView(this)
            }
        },
        update = { webView ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val expectedDark = if (forceWebPageDarkMode) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
                @Suppress("DEPRECATION")
                if (webView.settings.forceDark != expectedDark) {
                    @Suppress("DEPRECATION")
                    webView.settings.forceDark = expectedDark
                }
            }

            val desktopUserAgent = desktopUserAgentFromDefault(context)
            val wantsDesktop = displayMode != DisplayMode.MOBILE
            val isCurrentlyDesktop = webView.settings.userAgentString?.let {
                it.contains("Windows NT") || it.contains("X11; Linux x86_64")
            } == true

            val targetTextZoom = if (wantsDesktop) desktopZoomScale.coerceIn(50, 200) else 100
            if (webView.settings.textZoom != targetTextZoom) {
                webView.settings.textZoom = targetTextZoom
            }

            if (wantsDesktop && !isCurrentlyDesktop) {
                webView.settings.userAgentString = desktopUserAgent
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.builtInZoomControls = true
                webView.settings.displayZoomControls = false

                val currentUrl = webView.url
                if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
                    webView.reload()
                }
            } else if (!wantsDesktop && isCurrentlyDesktop) {
                webView.settings.userAgentString = null // Reset down to original mobile agent
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
                webView.settings.textZoom = 100

                val currentUrl = webView.url
                if (!currentUrl.isNullOrBlank() && currentUrl != "about:blank") {
                    webView.reload()
                }
            }

            val expectedDomStorage = !tab.isPrivate
            if (webView.settings.domStorageEnabled != expectedDomStorage) {
                webView.settings.domStorageEnabled = expectedDomStorage
            }

            val shouldAllowThirdPartyCookies = !tab.isPrivate &&
                privacyShieldMode != com.example.manager.PrivacyShieldMode.STRICT
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, shouldAllowThirdPartyCookies)

            val expectedCacheMode = when {
                tab.isPrivate -> WebSettings.LOAD_NO_CACHE
                dataSaverEnabled -> WebSettings.LOAD_CACHE_ELSE_NETWORK
                else -> WebSettings.LOAD_DEFAULT
            }
            if (webView.settings.loadsImagesAutomatically != !dataSaverEnabled || webView.settings.cacheMode != expectedCacheMode) {
                webView.settings.loadsImagesAutomatically = !dataSaverEnabled
                webView.settings.cacheMode = expectedCacheMode
            }

            if (webView.url.isNullOrBlank() && tab.url.isNotBlank() &&
                tab.url != "about:blank" && navigationActions[tab.id] == null &&
                com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(tab.url)
            ) {
                val headers = mapOf("DNT" to "1")
                webView.loadUrl(tab.url, headers)
            }
        },
        modifier = modifier.fillMaxSize()
    )

    // Execute navigation commands (LoadUrl, GoBack, GoForward, Reload, StopLoading) strictly ONCE per actionId
    val currentNavAction = navigationActions[tab.id]
    LaunchedEffect(tab.id, currentNavAction?.actionId) {
        val action = currentNavAction ?: return@LaunchedEffect
        val webView = webViewPool[tab.id] ?: return@LaunchedEffect
        when (action) {
            is BrowserViewModel.NavigationAction.LoadUrl -> {
                if (action.url.isNotBlank() && action.url != "about:blank" &&
                    com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(action.url)
                ) {
                    val headers = mapOf("DNT" to "1")
                    webView.loadUrl(action.url, headers)
                }
            }
            is BrowserViewModel.NavigationAction.GoBack -> {
                performSmartGoBack(webView, tab.id, viewModel)
            }
            is BrowserViewModel.NavigationAction.GoForward -> {
                if (webView.canGoForward()) {
                    webView.goForward()
                } else {
                    viewModel.updateTabLoading(tab.id, false)
                }
            }
            is BrowserViewModel.NavigationAction.Reload -> {
                val currentWebUrl = webView.url
                if (currentWebUrl.isNullOrBlank() || currentWebUrl == "about:blank") {
                    if (tab.url.isNotBlank() && tab.url != "about:blank" &&
                        com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl(tab.url)
                    ) {
                        webView.loadUrl(tab.url, mapOf("DNT" to "1"))
                    } else {
                        viewModel.updateTabLoading(tab.id, false)
                    }
                } else {
                    webView.reload()
                }
            }
            is BrowserViewModel.NavigationAction.StopLoading -> {
                webView.stopLoading()
            }
        }
        viewModel.consumeNavigationAction(tab.id, action.actionId)
    }
}

private fun performSmartGoBack(webView: WebView, tabId: String, viewModel: BrowserViewModel) {
    val historyList = webView.copyBackForwardList()
    val currentIndex = historyList.currentIndex
    if (webView.canGoBack() && currentIndex > 0) {
        val currentUrl = historyList.currentItem?.url.orEmpty()
        val prevUrl = historyList.getItemAtIndex(currentIndex - 1)?.url.orEmpty()
        val isTrivialRedirect = isSamePageRedirect(prevUrl, currentUrl)
        if (isTrivialRedirect && currentIndex >= 2 && webView.canGoBackOrForward(-2)) {
            webView.goBackOrForward(-2)
        } else if (isTrivialRedirect && currentIndex == 1) {
            webView.clearHistory()
            viewModel.goHome()
        } else {
            webView.goBack()
        }
        viewModel.updateTabNavigation(
            tabId = tabId,
            canBack = webView.canGoBack(),
            canForward = webView.canGoForward()
        )
    } else {
        // When at the first web page in this tab, pressing Back returns cleanly to the New Tab HomeScreen
        webView.loadUrl("about:blank")
        webView.clearHistory()
        viewModel.goHome()
    }
}

private fun isSamePageRedirect(urlA: String, urlB: String): Boolean {
    if (urlA.isBlank() || urlB.isBlank()) return false
    if (urlA == urlB) return true
    return try {
        val uriA = Uri.parse(urlA)
        val uriB = Uri.parse(urlB)
        val hostA = uriA.host?.lowercase()?.removePrefix("www.") ?: return false
        val hostB = uriB.host?.lowercase()?.removePrefix("www.") ?: return false
        val pathA = (uriA.path ?: "").trimEnd('/')
        val pathB = (uriB.path ?: "").trimEnd('/')
        val queryA = uriA.query ?: ""
        val queryB = uriB.query ?: ""
        hostA == hostB && pathA == pathB && queryA == queryB
    } catch (_: Exception) {
        false
    }
}

/**
 * SECURITY / COMPAT FIX (audit 2026-10-26): derive the desktop UA from the
 * system WebView UA instead of hard-coding Chrome/124. A frozen 2024 UA
 * breaks site compatibility and makes the browser fingerprintable. The
 * fallback below is only used if the system UA cannot be read.
 */
private fun desktopUserAgentFromDefault(context: android.content.Context): String {
    return try {
        val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(context)
        // Replace the platform segment "(Linux; Android ...)" with a desktop
        // segment, so the existing isCurrentlyDesktop check (X11 / Windows NT)
        // keeps working and the UA is not reset on every recomposition.
        val open = defaultUa.indexOf('(')
        val close = defaultUa.indexOf(')', open)
        val withDesktopPlatform = if (open >= 0 && close > open) {
            defaultUa.substring(0, open + 1) + "X11; Linux x86_64" + defaultUa.substring(close)
        } else {
            defaultUa
        }
        val desktop = withDesktopPlatform
            .replace("; wv", "")
            .replace(" Mobile Safari/", " Safari/")
            .replace("Mobile ", "")
        if (desktop.contains("Chrome/") && desktop.contains("X11; Linux x86_64")) desktop
        else "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    } catch (_: Exception) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private fun safeHost(url: String?): String {
    return try {
        android.net.Uri.parse(url ?: "").host?.lowercase()?.removePrefix("www.") ?: ""
    } catch (e: Exception) {
        ""
    }
}

private fun isHostInSet(host: String, domains: Set<String>): Boolean {
    if (host.isBlank()) return false
    return domains.any { domain ->
        val clean = domain.lowercase().removePrefix("www.")
        host == clean || host.endsWith(".$clean")
    }
}

private fun sanitizeZapperSelector(raw: String): String {
    val trimmed = raw.trim().take(160)
    return if (BrowserViewModel.isValidZapperSelector(trimmed)) trimmed else ""
}

private fun createDevToolsJsBridge(
    tabId: String,
    viewModel: BrowserViewModel
): com.example.devtools.DevToolsJsBridge {
    return com.example.devtools.DevToolsJsBridge(
        onElementSelected = { selectedElement ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (viewModel.inspectToolsEnabled.value) {
                    viewModel.updateSelectedElement(tabId, selectedElement)
                    viewModel.openDevTools(tabId, com.example.devtools.DevToolsTab.ELEMENTS)
                    val infoLog = com.example.devtools.ConsoleEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        level = com.example.devtools.ConsoleLevel.INFO,
                        message = "Inspected: ${selectedElement.selector}"
                    )
                    viewModel.addConsoleEntry(tabId, infoLog)
                }
            }
        },
        onConsoleBridgeMessage = { entry ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (viewModel.inspectToolsEnabled.value) {
                    viewModel.addConsoleEntry(tabId, entry)
                }
            }
        }
    )
}


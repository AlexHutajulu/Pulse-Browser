package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.devtools.DevToolsState
import com.example.manager.DisplayMode
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.BrowserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val activeTabId by viewModel.activeTabId.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val adBlockEnabled by viewModel.adBlockEnabled.collectAsState()
    val trackerBlockEnabled by viewModel.trackerBlockEnabled.collectAsState()
    val bookmarks by viewModel.bookmarksState.collectAsState()
    val history by viewModel.historyState.collectAsState()

    val showHistory by viewModel.showHistory.collectAsState()
    val showBookmarks by viewModel.showBookmarks.collectAsState()
    val showTabsDialog by viewModel.showTabsDialog.collectAsState()
    var showShieldDialog by remember { mutableStateOf(false) }
    var showSecurityDashboard by remember { mutableStateOf(false) }

    // Chrome Find in Page state
    var showFindInPage by remember { mutableStateOf(false) }
    var findInPageQuery by remember { mutableStateOf("") }

    var nativeFullscreenView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val isPipMode by viewModel.isPipMode.collectAsState()
    var lastBackPressTime by remember { mutableLongStateOf(0L) }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull() ?: BrowserTab()
    val isIncognito = activeTab.isPrivate

    val webViewPool = remember { java.util.LinkedHashMap<String, android.webkit.WebView>(16, 0.75f, true) }

    val inspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()
    val devToolsStates by viewModel.devToolsStates.collectAsState()
    val activeDevState = devToolsStates[activeTab.id] ?: DevToolsState()

    val screenOrientationMode by viewModel.screenOrientationMode.collectAsState()
    val shortcutBarVisible by viewModel.shortcutBarVisible.collectAsState()
    val shortcutBarPosition by viewModel.shortcutBarPosition.collectAsState()
    val showCustomJsStudio by viewModel.showCustomJsStudio.collectAsState()
    val showExtensionsHub by viewModel.showExtensionsHub.collectAsState()
    val tabSecurityThreats by viewModel.tabSecurityThreats.collectAsState()
    val activeTabThreat = tabSecurityThreats[activeTab.id]

    // Hardware Accelerometer / Gravity Tilt Sensor Monitor (Real-Time Portrait/Landscape Tilt Detection)
    val deviceTiltState by viewModel.deviceTiltState.collectAsState()
    val smartTiltDismissedQuadrant by viewModel.smartTiltDismissedQuadrant.collectAsState()
    DisposableEffect(context) {
        val orientationListener = object : android.view.OrientationEventListener(
            context,
            android.hardware.SensorManager.SENSOR_DELAY_UI
        ) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    viewModel.updateDeviceTilt(-1, canDetectOrientation())
                } else {
                    viewModel.updateDeviceTilt(orientation, true)
                }
            }
        }
        val canDetect = try {
            orientationListener.canDetectOrientation()
        } catch (_: Exception) {
            false
        }
        if (canDetect) {
            try {
                orientationListener.enable()
            } catch (_: Exception) {}
        } else {
            viewModel.updateDeviceTilt(-1, false)
        }
        onDispose {
            try {
                orientationListener.disable()
            } catch (_: Exception) {}
        }
    }

    // Screen Orientation follows the user's system device rotation setting directly.
    // If system auto-rotate is OFF, device stays locked; if ON, it rotates naturally.
    val hostActivity = context as? android.app.Activity
    LaunchedEffect(Unit) {
        if (hostActivity != null && hostActivity.requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            hostActivity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Live blocked count for current page badge
    val liveShieldLogs by com.yourbrowser.adblock.AlexShieldV5LogStore.logs.collectAsState()
    val pageBlockedCount = remember(activeTab.url, liveShieldLogs) {
        if (activeTab.url.isBlank() || activeTab.url == "about:blank") 0
        else viewModel.getBlockedLogsForPage(activeTab.url).size
    }

    val isElementZapperActive by viewModel.isElementZapperActive.collectAsState()
    val zapperPreviewSelector by viewModel.zapperPreviewSelector.collectAsState()
    val zapperPreviewTag by viewModel.zapperPreviewTag.collectAsState()
    val hibernateSignal by viewModel.hibernateInactiveTabsSignal.collectAsState()
    val isImmersivePageFullscreen by viewModel.isImmersivePageFullscreen.collectAsState()
    val fullScreenToolSignal by viewModel.fullScreenToolSignal.collectAsState()

    LaunchedEffect(fullScreenToolSignal) {
        if (fullScreenToolSignal <= 0L) return@LaunchedEffect
        val wv = webViewPool[activeTab.id]
        viewModel.triggerFullScreenTool(wv) { msg ->
            if (msg.isNotBlank() && msg != "null") {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Immersive System Bars Controller for Full Screen Tool & HTML5 Fullscreen
    LaunchedEffect(isImmersivePageFullscreen, nativeFullscreenView) {
        val window = hostActivity?.window ?: return@LaunchedEffect
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (isImmersivePageFullscreen || nativeFullscreenView != null) {
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    // Unified BackHandler - Steps back one page at a time in WebView history before returning to Home
    androidx.activity.compose.BackHandler(enabled = true) {
        val webView = webViewPool[activeTabId]

        if (nativeFullscreenView != null || isImmersivePageFullscreen) {
            customViewCallback?.onCustomViewHidden()
            nativeFullscreenView = null
            customViewCallback = null
            viewModel.setFullscreen(false)
            viewModel.setImmersivePageFullscreen(false)
            webView?.evaluateJavascript("if(window.__kiwiExitFs) window.__kiwiExitFs();", null)
        } else if (isElementZapperActive) {
            viewModel.setElementZapperActive(false)
        } else if (showExtensionsHub) {
            viewModel.setExtensionsHubVisible(false)
        } else if (showCustomJsStudio) {
            viewModel.setCustomJsStudioVisible(false)
        } else if (showFindInPage) {
            webView?.clearMatches()
            findInPageQuery = ""
            showFindInPage = false
        } else if (inspectToolsEnabled && activeDevState.isOpen) {
            viewModel.toggleDevTools(activeTab.id)
        } else if (showTabsDialog) {
            viewModel.setTabsDialogVisible(false)
        } else if (showBookmarks) {
            viewModel.setBookmarksVisible(false)
        } else if (showHistory) {
            viewModel.setHistoryVisible(false)
        } else if (showShieldDialog) {
            showShieldDialog = false
        } else if (showSecurityDashboard) {
            showSecurityDashboard = false
        } else if (webView?.canGoBack() == true) {
            // Step back inside WebView history (never jumps straight to Home)
            viewModel.goBack()
        } else if (activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
            // Check per-tab backup history stack first, or return to New Tab Home
            viewModel.goBack()
        } else if (tabs.size > 1) {
            viewModel.removeTab(activeTabId)
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                (context as? android.app.Activity)?.finish()
            } else {
                Toast.makeText(context, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                lastBackPressTime = currentTime
            }
        }
    }

    val isAppPaused by viewModel.isAppPaused.collectAsState()
    val backgroundPlaybackEnabled by viewModel.backgroundPlaybackEnabled.collectAsState()

    // Balanced RAM Optimization: Keep at most 3 active WebViews in the LRU pool and auto-suspend older tabs
    val tabIds = remember(tabs) { tabs.map { it.id }.toSet() }
    LaunchedEffect(tabIds, activeTabId) {
        // Touch active tab entry to update access order in LinkedHashMap LRU
        webViewPool[activeTabId]
        viewModel.markTabSuspended(activeTabId, false)

        val iterator = webViewPool.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in tabIds) {
                safeDestroyWebView(entry.value)
                iterator.remove()
            }
        }
        // Evict least-recently-used inactive non-audio WebView instances if pool exceeds 3 (Balanced Profile)
        val maxPoolSize = 3
        if (webViewPool.size > maxPoolSize) {
            val evictCandidates = webViewPool.keys.filter { id ->
                id != activeTabId && tabs.find { it.id == id }?.isPlayingAudio != true
            }
            val excessCount = webViewPool.size - maxPoolSize
            evictCandidates.take(excessCount).forEach { evictId ->
                webViewPool.remove(evictId)?.let { safeDestroyWebView(it) }
                viewModel.markTabSuspended(evictId, true)
            }
        }
    }

    // 1-Tap & System Memory Trim Hibernation Handler
    LaunchedEffect(hibernateSignal) {
        if (hibernateSignal <= 0L) return@LaunchedEffect
        val toHibernate = webViewPool.keys.filter { id ->
            id != activeTabId && tabs.find { it.id == id }?.isPlayingAudio != true
        }
        toHibernate.forEach { id ->
            webViewPool.remove(id)?.let { safeDestroyWebView(it) }
            viewModel.markTabSuspended(id, true)
        }
    }

    // Battery & CPU Hibernation: Pause inactive background tabs and resume only the active tab
    LaunchedEffect(activeTabId, isAppPaused, backgroundPlaybackEnabled, tabs) {
        // Iterate over a snapshot of entries so LinkedHashMap access-order isn't mutated during iteration
        webViewPool.entries.toList().forEach { (id, wv) ->
            val tabObj = tabs.find { it.id == id }
            val keepAudioAlive = backgroundPlaybackEnabled && (tabObj?.isPlayingAudio == true)
            if (id == activeTabId && !isAppPaused) {
                try {
                    wv.onResume()
                    wv.resumeTimers()
                } catch (_: Throwable) {}
            } else if (!keepAudioAlive) {
                try {
                    wv.onPause()
                } catch (_: Throwable) {}
            }
        }
        val activePlayingAudio = backgroundPlaybackEnabled && tabs.any { it.isPlayingAudio }
        if (isAppPaused && !activePlayingAudio) {
            try {
                webViewPool[activeTabId]?.pauseTimers()
            } catch (_: Throwable) {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewPool.values.toList().forEach { webView ->
                safeDestroyWebView(webView)
            }
            webViewPool.clear()
        }
    }

    var addressInput by remember { mutableStateOf(activeTab.url) }
    var isAddressBarFocused by remember { mutableStateOf(false) }
    LaunchedEffect(activeTab.id) {
        addressInput = activeTab.url
    }
    LaunchedEffect(activeTab.url) {
        if (!isAddressBarFocused) {
            addressInput = activeTab.url
        }
    }
    val addressBarFocusRequester = remember { FocusRequester() }

    val isCurrentUrlBookmarked = remember(bookmarks, activeTab.url) {
        bookmarks.any { it.url == activeTab.url }
    }
    val isUrlInsecure = activeTab.url.startsWith("http://")

    var processingPermission by remember { mutableStateOf<BrowserViewModel.PendingPermissionRequest?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val req = processingPermission ?: return@rememberLauncherForActivityResult

        val grantedTypes = mutableListOf<com.example.manager.SitePermissionType>()
        req.types.forEach { type ->
            val isGranted = when (type) {
                com.example.manager.SitePermissionType.CAMERA -> permissions[android.Manifest.permission.CAMERA] == true
                com.example.manager.SitePermissionType.MICROPHONE -> permissions[android.Manifest.permission.RECORD_AUDIO] == true
                com.example.manager.SitePermissionType.LOCATION -> permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
                else -> true
            }
            if (isGranted) grantedTypes.add(type)
        }

        val isIncognitoReq = req.isIncognito || activeTab.isPrivate
        if (grantedTypes.isNotEmpty()) {
            grantedTypes.forEach { type ->
                viewModel.handlePermissionChoice(req.url, type, com.example.manager.PermissionState.ALLOW, isIncognitoReq)
            }
            req.onGrant(grantedTypes)
        } else {
            req.onDeny()
        }
        processingPermission = null
    }

    val pendingPermissionRequest by viewModel.pendingPermissionRequest.collectAsState()

    // Pure Chrome M3 Site Permission Request Dialog
    if (pendingPermissionRequest != null) {
        val request = pendingPermissionRequest!!
        val isIncognitoReq = request.isIncognito || activeTab.isPrivate
        AlertDialog(
            onDismissRequest = {
                request.onDeny()
                viewModel.clearPendingPermissionRequest()
            },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Site Permission Request",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${request.url} wants to use:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    request.types.forEach { type ->
                        ChromePermissionBadge(
                            text = type.name,
                            icon = when (type) {
                                com.example.manager.SitePermissionType.CAMERA -> Icons.Default.Videocam
                                com.example.manager.SitePermissionType.MICROPHONE -> Icons.Default.Mic
                                com.example.manager.SitePermissionType.LOCATION -> Icons.Default.LocationOn
                                else -> Icons.Default.Check
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        processingPermission = request
                        val androidPermsToRequest = mutableListOf<String>()
                        if (request.types.contains(com.example.manager.SitePermissionType.CAMERA)) androidPermsToRequest.add(android.Manifest.permission.CAMERA)
                        if (request.types.contains(com.example.manager.SitePermissionType.MICROPHONE)) androidPermsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
                        if (request.types.contains(com.example.manager.SitePermissionType.LOCATION)) androidPermsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)

                        viewModel.clearPendingPermissionRequest()
                        if (androidPermsToRequest.isNotEmpty()) {
                            launcher.launch(androidPermsToRequest.toTypedArray())
                        } else {
                            request.types.forEach { type ->
                                viewModel.handlePermissionChoice(request.url, type, com.example.manager.PermissionState.ALLOW, isIncognitoReq)
                            }
                            request.onGrant(request.types)
                            processingPermission = null
                        }
                    }
                ) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        request.types.forEach { type ->
                            viewModel.handlePermissionChoice(request.url, type, com.example.manager.PermissionState.BLOCK, isIncognitoReq)
                        }
                        request.onDeny()
                        viewModel.clearPendingPermissionRequest()
                    }
                ) {
                    Text("Block")
                }
            }
        )
    }

    val intentToOpen by viewModel.intentToOpen.collectAsState()
    if (intentToOpen != null) {
        val url = intentToOpen!!
        AlertDialog(
            onDismissRequest = { viewModel.clearIntentToOpen() },
            title = { Text("Buka Aplikasi Eksternal?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Situs ini ingin membuka aplikasi di luar AlexBrowser:\n${url.take(180)}") },
            confirmButton = {
                Button(onClick = {
                    val safeIntent = com.example.manager.SecurityGuardEngine.buildSafeExternalIntent(context, url)
                    if (safeIntent != null) {
                        try {
                            context.startActivity(safeIntent)
                        } catch (_: android.content.ActivityNotFoundException) {
                            val fallbackUrl = com.example.manager.SecurityGuardEngine.extractSafeFallbackUrl(url)
                            if (fallbackUrl != null) {
                                viewModel.navigateTo(fallbackUrl)
                            } else {
                                Toast.makeText(context, "Aplikasi eksternal tidak ditemukan di perangkat", Toast.LENGTH_SHORT).show()
                            }
                        } catch (_: Exception) {
                            Toast.makeText(context, "Tidak dapat membuka tautan aplikasi eksternal", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Tautan aplikasi eksternal diblokir karena alasan keamanan", Toast.LENGTH_SHORT).show()
                    }
                    viewModel.clearIntentToOpen()
                }) {
                    Text("Buka")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearIntentToOpen() }) {
                    Text("Batal")
                }
            }
        )
    }

    if (isPipMode) {
        val webView = webViewPool[activeTab.id]
        if (webView != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    val containerBg = if (isIncognito) ChromeIncognitoBg else MaterialTheme.colorScheme.background

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(containerBg)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                    when (event.key) {
                        Key.T -> { viewModel.addNewTab(); true }
                        Key.W -> { viewModel.removeTab(activeTabId); true }
                        Key.R -> { viewModel.reload(); true }
                        Key.F -> { showFindInPage = true; true }
                        else -> false
                    }
                } else false
            }
    ) {
        val isTablet = maxWidth >= 680.dp
        val displayMode by viewModel.displayMode.collectAsState()

        if (displayMode == DisplayMode.DESKTOP) {
            DesktopBrowserLayout(
                viewModel = viewModel,
                activeTab = activeTab,
                tabs = tabs,
                bookmarks = bookmarks,
                history = history,
                webViewPool = webViewPool,
                onFileChooser = onFileChooser,
                onNavigateToSettings = onNavigateToSettings,
                isTablet = isTablet,
                addressInput = addressInput,
                onAddressInputChanged = { addressInput = it },
                isCurrentUrlBookmarked = isCurrentUrlBookmarked,
                isUrlInsecure = isUrlInsecure,
                showBookmarks = showBookmarks,
                showHistory = showHistory,
                showTabsDialog = showTabsDialog,
                adBlockEnabled = adBlockEnabled,
                trackerBlockEnabled = trackerBlockEnabled,
                pageBlockedCount = pageBlockedCount,
                onShowShield = { showShieldDialog = true },
                onShowSecurityDashboard = { showSecurityDashboard = true },
                onOpenFindInPage = { showFindInPage = true },
                showFindInPage = showFindInPage,
                findInPageQuery = findInPageQuery,
                onFindQueryChange = { query ->
                    findInPageQuery = query
                    val wv = webViewPool[activeTab.id]
                    if (query.isNotBlank()) wv?.findAllAsync(query) else wv?.clearMatches()
                },
                onFindNext = { forward -> webViewPool[activeTab.id]?.findNext(forward) },
                onCloseFindInPage = {
                    webViewPool[activeTab.id]?.clearMatches()
                    findInPageQuery = ""
                    showFindInPage = false
                },
                onFullscreenView = { view, callback ->
                    nativeFullscreenView = view
                    customViewCallback = callback
                    viewModel.setFullscreen(true)
                },
                onHideFullscreenView = {
                    nativeFullscreenView = null
                    customViewCallback = null
                    viewModel.setFullscreen(false)
                },
                addressBarFocusRequester = addressBarFocusRequester,
                onAddressFocusChanged = { isAddressBarFocused = it }
            )
        } else {
            Scaffold(
                containerColor = containerBg,
                bottomBar = {
                    if (!isTablet && !isImmersivePageFullscreen) {
                        ChromeMobileBottomBar(
                            viewModel = viewModel,
                            activeTab = activeTab,
                            activeWebView = webViewPool[activeTab.id],
                            tabsCount = tabs.size,
                            isIncognito = isIncognito,
                            pageBlockedCount = pageBlockedCount,
                            shortcutBarVisible = shortcutBarVisible,
                            shortcutBarPosition = shortcutBarPosition,
                            onBack = { viewModel.goBack() },
                            onForward = { viewModel.goForward() },
                            onNewTab = { viewModel.addNewTab(isPrivate = isIncognito) },
                            onHome = { viewModel.goHome() },
                            onOpenTabs = { viewModel.setTabsDialogVisible(true) },
                            onOpenShield = { showShieldDialog = true },
                            onOpenFindInPage = { showFindInPage = true },
                            onOpenSecurityShield = { showSecurityDashboard = true },
                            adBlockEnabled = adBlockEnabled
                        )
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isImmersivePageFullscreen) PaddingValues(0.dp) else innerPadding)
                        .imePadding()
                ) {
                    // Tablet inline side panel
                    AnimatedVisibility(
                        visible = isTablet && (showTabsDialog || showBookmarks || showHistory),
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(310.dp)
                                .fillMaxHeight(),
                            color = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    if (showTabsDialog) {
                                        SidebarTabsPanel(viewModel, tabs, activeTabId)
                                    } else if (showBookmarks) {
                                        SidebarBookmarksPanel(viewModel, bookmarks, context)
                                    } else if (showHistory) {
                                        SidebarHistoryPanel(viewModel, history, context)
                                    }
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }

                    // Main Chrome Viewport
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        if (!isImmersivePageFullscreen) {
                            if (isTablet) {
                                TabletHorizontalTabBar(viewModel, tabs, activeTabId)
                            }

                            if (showFindInPage) {
                                ChromeFindInPageBar(
                                    query = findInPageQuery,
                                    onQueryChange = { q ->
                                        findInPageQuery = q
                                        val wv = webViewPool[activeTab.id]
                                        if (q.isNotBlank()) wv?.findAllAsync(q) else wv?.clearMatches()
                                    },
                                    onFindPrev = { webViewPool[activeTab.id]?.findNext(false) },
                                    onFindNext = { webViewPool[activeTab.id]?.findNext(true) },
                                    onClose = {
                                        webViewPool[activeTab.id]?.clearMatches()
                                        findInPageQuery = ""
                                        showFindInPage = false
                                    }
                                )
                            } else {
                                TabletOrMobileToolbar(
                                    isTablet = isTablet,
                                    activeTab = activeTab,
                                    tabsCount = tabs.size,
                                    addressInput = addressInput,
                                    onAddressValueChange = { addressInput = it },
                                    onSearchSubmit = {
                                        if (addressInput.isNotBlank()) {
                                            viewModel.navigateTo(addressInput.trim())
                                            focusManager.clearFocus()
                                        }
                                    },
                                    isUrlInsecure = isUrlInsecure,
                                    isCurrentUrlBookmarked = isCurrentUrlBookmarked,
                                    showTabsDialog = showTabsDialog,
                                    showBookmarks = showBookmarks,
                                    showHistory = showHistory,
                                    pageBlockedCount = pageBlockedCount,
                                    bookmarks = bookmarks,
                                    history = history,
                                    onClearAddressInput = { addressInput = "" },
                                    onToggleBookmark = {
                                        viewModel.toggleBookmark(activeTab.title, activeTab.url)
                                        val msg = if (isCurrentUrlBookmarked) "Bookmark removed" else "Bookmarked"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    onToggleTabsSidebar = {
                                        if (showTabsDialog) {
                                            viewModel.setTabsDialogVisible(false)
                                        } else {
                                            viewModel.setBookmarksVisible(false)
                                            viewModel.setHistoryVisible(false)
                                            viewModel.setTabsDialogVisible(true)
                                        }
                                    },
                                    onToggleBookmarksSidebar = {
                                        if (showBookmarks) {
                                            viewModel.setBookmarksVisible(false)
                                        } else {
                                            viewModel.setTabsDialogVisible(false)
                                            viewModel.setHistoryVisible(false)
                                            viewModel.setBookmarksVisible(true)
                                        }
                                    },
                                    onToggleHistorySidebar = {
                                        if (showHistory) {
                                            viewModel.setHistoryVisible(false)
                                        } else {
                                            viewModel.setTabsDialogVisible(false)
                                            viewModel.setBookmarksVisible(false)
                                            viewModel.setHistoryVisible(true)
                                        }
                                    },
                                    onNavigateToSettings = onNavigateToSettings,
                                    onShowShield = { showShieldDialog = true },
                                    onShowSecurityDashboard = { showSecurityDashboard = true },
                                    onOpenFindInPage = { showFindInPage = true },
                                    context = context,
                                    viewModel = viewModel,
                                    addressBarFocusRequester = addressBarFocusRequester,
                                    onAddressFocusChanged = { isAddressBarFocused = it }
                                )
                            }

                            // Top Position: Chrome Scrollable Shortcut & Custom JS Bar
                            if (shortcutBarVisible && shortcutBarPosition == com.example.manager.ShortcutBarPosition.TOP) {
                                ChromeShortcutAndJsBar(
                                    viewModel = viewModel,
                                    activeTab = activeTab,
                                    activeWebView = webViewPool[activeTab.id],
                                    isIncognito = isIncognito,
                                    onOpenFindInPage = { showFindInPage = true },
                                    onOpenSecurityShield = { showSecurityDashboard = true }
                                )
                            }
                        }

                        // Chrome thin 2dp progress bar
                        if (activeTab.isLoading) {
                            val animatedProgress by animateFloatAsState(
                                targetValue = (activeTab.progress / 100f).coerceIn(0.05f, 1f),
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "chrome_progress"
                            )
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                                color = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary,
                                trackColor = Color.Transparent
                            )
                        } else {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = if (isIncognito) Color.White.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        }

                        // Active Web Content
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            if (activeTabThreat != null) {
                                ChromeSecurityThreatInterstitial(
                                    threat = activeTabThreat,
                                    onBackToSafety = {
                                        viewModel.clearTabSecurityThreat(activeTab.id)
                                        val wv = webViewPool[activeTab.id]
                                        if (wv?.canGoBack() == true) {
                                            viewModel.goBack()
                                        } else {
                                            viewModel.goHome()
                                        }
                                    },
                                    onProceedAnyway = {
                                        viewModel.proceedDespiteSecurityThreat(activeTab.id, activeTabThreat)
                                    }
                                )
                            } else if (activeTab.loadError != null) {
                                WebErrorPlaceholderPage(activeTab.loadError, viewModel)
                            } else if (activeTab.url.isBlank() || activeTab.url == "about:blank") {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigate = { viewModel.navigateTo(it) },
                                    onSearchSubmit = { viewModel.navigateTo(it) }
                                )
                            } else {
                                key(activeTab.id) {
                                    CustomWebView(
                                        tab = activeTab,
                                        viewModel = viewModel,
                                        adBlockEnabled = adBlockEnabled,
                                        trackerBlockEnabled = trackerBlockEnabled,
                                        webViewPool = webViewPool,
                                        onFileChooser = onFileChooser,
                                        onFullscreenView = { view, callback ->
                                            nativeFullscreenView = view
                                            customViewCallback = callback
                                            viewModel.setFullscreen(true)
                                        },
                                        onHideFullscreenView = {
                                            nativeFullscreenView = null
                                            customViewCallback = null
                                            viewModel.setFullscreen(false)
                                        }
                                    )
                                }
                            }
                        }

                        // Tablet Bottom Position: Chrome Scrollable Shortcut & Custom JS Bar (Mobile renders it inside ChromeMobileBottomBar)
                        if (isTablet && !isImmersivePageFullscreen && shortcutBarVisible && shortcutBarPosition == com.example.manager.ShortcutBarPosition.BOTTOM) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = if (isIncognito) Color.White.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                            ChromeShortcutAndJsBar(
                                viewModel = viewModel,
                                activeTab = activeTab,
                                activeWebView = webViewPool[activeTab.id],
                                isIncognito = isIncognito,
                                onOpenFindInPage = { showFindInPage = true },
                                onOpenSecurityShield = { showSecurityDashboard = true }
                            )
                        }

                        // Mobile & Tablet bottom docked Developer Tools Inspector
                        AnimatedVisibility(
                            visible = inspectToolsEnabled && activeDevState.isOpen,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.45f)
                        ) {
                            DevToolsPanel(
                                viewModel = viewModel,
                                tabId = activeTab.id,
                                devToolsState = activeDevState,
                                webView = webViewPool[activeTab.id],
                                onClose = { viewModel.toggleDevTools(activeTab.id) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        // Interactive Custom Element Zapper Floating Overlay
        if (isElementZapperActive) {
            val currentHost = remember(activeTab.url) {
                try { Uri.parse(activeTab.url).host?.lowercase()?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 72.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
            ) {
                ElementZapperFloatingBar(
                    selectedSelector = zapperPreviewSelector,
                    selectedTag = zapperPreviewTag,
                    onZapConfirm = {
                        val wv = webViewPool[activeTab.id]
                        if (wv != null && zapperPreviewSelector.isNotBlank() && currentHost.isNotBlank()) {
                            wv.evaluateJavascript("if(window.__asConfirmZap) window.__asConfirmZap();", null)
                            viewModel.addCustomZapperRule(currentHost, zapperPreviewSelector)
                            Toast.makeText(context, "Elemen dihapus & disimpan untuk $currentHost", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUndo = {
                        if (currentHost.isNotBlank() && viewModel.undoLastCustomZapperRule(currentHost)) {
                            viewModel.reload()
                            Toast.makeText(context, "Aturan elemen terakhir dibatalkan", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Belum ada elemen yang dihapus di situs ini", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClose = { viewModel.setElementZapperActive(false) }
                )
            }
        }

        // Mobile Full-Screen Chrome Overlays (Only when in Mobile DisplayMode; Desktop Mode uses its own structured Sidebar)
        if (!isTablet && displayMode != DisplayMode.DESKTOP) {
            if (showTabsDialog) {
                MobileTabsDialog(viewModel, tabs, activeTabId)
            }
            if (showBookmarks) {
                MobileBookmarksDialog(viewModel, bookmarks, context)
            }
            if (showHistory) {
                MobileHistoryDialog(viewModel, history, context)
            }
        }

        val showDownloadsDialog by viewModel.showDownloadsDialog.collectAsState()
        if (showDownloadsDialog) {
            DownloadsDialog(viewModel)
        }

        if (showShieldDialog) {
            WebGuardShieldDialog(
                viewModel = viewModel,
                activeTab = activeTab,
                onDismiss = { showShieldDialog = false }
            )
        }

        if (showSecurityDashboard) {
            SecurityDashboardDialog(
                onDismiss = { showSecurityDashboard = false },
                isUrlInsecure = isUrlInsecure,
                adBlockEnabled = adBlockEnabled,
                trackerBlockEnabled = trackerBlockEnabled,
                context = context,
                viewModel = viewModel,
                activeTabId = activeTabId
            )
        }

        if (showCustomJsStudio) {
            CustomJsStudioDialog(
                viewModel = viewModel,
                activeWebView = webViewPool[activeTab.id],
                activeUrl = activeTab.url,
                onDismiss = { viewModel.setCustomJsStudioVisible(false) }
            )
        }

        if (showExtensionsHub) {
            ChromeExtensionsHubDialog(
                viewModel = viewModel,
                activeWebView = webViewPool[activeTab.id],
                activeUrl = activeTab.url,
                onDismiss = { viewModel.setExtensionsHubVisible(false) }
            )
        }

        // Top-level HTML5 / Video Custom Fullscreen Overlay (keeps CustomWebView mounted underneath so DOM fullscreen never detaches)
        if (nativeFullscreenView != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = {
                        val v = nativeFullscreenView!!
                        (v.parent as? android.view.ViewGroup)?.removeView(v)
                        v
                    },
                    modifier = Modifier.fillMaxSize()
                )

                val contextActivity = context as? android.app.Activity
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        IconButton(
                            onClick = {
                                try {
                                    contextActivity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
                                } catch (_: Exception) {}
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPicture,
                                contentDescription = "Enter Picture in Picture",
                                tint = Color.White
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            customViewCallback?.onCustomViewHidden()
                            nativeFullscreenView = null
                            customViewCallback = null
                            viewModel.setFullscreen(false)
                            viewModel.setImmersivePageFullscreen(false)
                            webViewPool[activeTab.id]?.evaluateJavascript("if(window.__kiwiExitFs) window.__kiwiExitFs();", null)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FullscreenExit,
                            contentDescription = "Exit Full Screen",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromePermissionBadge(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun ChromeFindInPageBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFindPrev: () -> Unit,
    onFindNext: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Find in page",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onFindNext() }),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            IconButton(
                onClick = onFindPrev,
                enabled = query.isNotBlank(),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(
                onClick = onFindNext,
                enabled = query.isNotBlank(),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close find in page")
            }
        }
    }
}

private fun safeDestroyWebView(webView: android.webkit.WebView) {
    try {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.onPause()
        webView.removeJavascriptInterface("AlexBrowserNative")
        webView.removeJavascriptInterface("AdBlockDevTools")
        webView.webChromeClient = null
        webView.clearFormData()
        webView.clearSslPreferences()
        webView.clearCache(false)
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
    } catch (_: Throwable) {}
}

@Composable
private fun ChromeMobileBottomBar(
    viewModel: BrowserViewModel,
    activeTab: BrowserTab,
    activeWebView: android.webkit.WebView?,
    tabsCount: Int,
    isIncognito: Boolean,
    pageBlockedCount: Int,
    shortcutBarVisible: Boolean,
    shortcutBarPosition: com.example.manager.ShortcutBarPosition,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onHome: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenShield: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onOpenSecurityShield: () -> Unit,
    adBlockEnabled: Boolean
) {
    val barBg = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface
    val iconTint = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface
    val accentColor = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = barBg,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = if (isIncognito) Color.White.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )

            // Integrated Collapsible Shortcut Bar (Auto Putar, Ekstensi, + Toolbar JS, etc.) inside Bottom Nav Dock
            AnimatedVisibility(
                visible = shortcutBarVisible && shortcutBarPosition == com.example.manager.ShortcutBarPosition.BOTTOM,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ChromeShortcutAndJsBar(
                        viewModel = viewModel,
                        activeTab = activeTab,
                        activeWebView = activeWebView,
                        isIncognito = isIncognito,
                        onOpenFindInPage = onOpenFindInPage,
                        onOpenSecurityShield = onOpenSecurityShield
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = if (isIncognito) Color.White.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canNavigateBack = activeTab.canGoBack || (activeTab.url.isNotBlank() && activeTab.url != "about:blank")
                IconButton(
                    onClick = onBack,
                    enabled = canNavigateBack,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("mobile_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (canNavigateBack) iconTint else iconTint.copy(alpha = 0.32f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = onForward,
                    enabled = activeTab.canGoForward,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (activeTab.canGoForward) iconTint else iconTint.copy(alpha = 0.32f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Center Chrome "+" New Tab Pill Button
                FilledTonalIconButton(
                    onClick = onNewTab,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isIncognito) ChromeIncognitoOmnibox else MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Tab",
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 1-Tap Collapsible Shortcut Bar Toggle (Auto Putar, Ekstensi, Script JS)
                IconButton(
                    onClick = { viewModel.setShortcutBarVisible(!shortcutBarVisible) },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("mobile_toggle_shortcut_bar")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (shortcutBarVisible) accentColor.copy(alpha = 0.16f)
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (shortcutBarVisible) Icons.Filled.Bolt else Icons.Outlined.Bolt,
                            contentDescription = if (shortcutBarVisible) "Tutup Pintasan" else "Buka Pintasan & Ekstensi",
                            tint = if (shortcutBarVisible) accentColor else iconTint.copy(alpha = 0.8f),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }

                // Iconic Chrome Rounded Square Tab Counter
                ChromeTabCountButton(
                    count = tabsCount,
                    isIncognito = isIncognito,
                    onClick = onOpenTabs
                )

                // AdShield Quick Button with live blocked badge
                IconButton(
                    onClick = onOpenShield,
                    modifier = Modifier.size(44.dp)
                ) {
                    BadgedBox(
                        badge = {
                            if (adBlockEnabled && pageBlockedCount > 0) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Text(
                                        text = if (pageBlockedCount > 99) "99+" else pageBlockedCount.toString(),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (adBlockEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                            contentDescription = "AdShield Guard",
                            tint = if (adBlockEnabled) MaterialTheme.colorScheme.primary else iconTint.copy(alpha = 0.65f),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChromeTabCountButton(
    count: Int,
    isIncognito: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp)
    ) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .border(1.8.dp, tint, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) ":D" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = tint
            )
        }
    }
}

@Composable
fun TabletHorizontalTabBar(
    viewModel: BrowserViewModel,
    tabs: List<BrowserTab>,
    activeTabId: String,
    modifier: Modifier = Modifier
) {
    val activeTab = tabs.find { it.id == activeTabId }
    val isIncognito = activeTab?.isPrivate == true
    val stripBg = if (isIncognito) ChromeIncognitoBg else MaterialTheme.colorScheme.surfaceContainerLowest
    val activeTabBg = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(42.dp)
            .background(stripBg)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { tab ->
                val isActive = tab.id == activeTabId
                val tabColor = if (isActive) {
                    activeTabBg
                } else {
                    if (isIncognito) ChromeIncognitoSurface.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                }
                val textColor = if (isIncognito) {
                    if (isActive) ChromeIncognitoText else ChromeIncognitoSubtext
                } else {
                    if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                }

                Surface(
                    modifier = Modifier
                        .widthIn(min = 130.dp, max = 220.dp)
                        .fillMaxHeight()
                        .clickable { viewModel.selectTab(tab.id) },
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    color = tabColor
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (tab.isPrivate) Icons.Default.VisibilityOff else Icons.Default.Public,
                                contentDescription = null,
                                tint = if (tab.isPrivate) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tab.title.ifBlank { "New Tab" },
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { viewModel.removeTab(tab.id) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Tab",
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = { viewModel.addNewTab(isPrivate = false) },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Tab",
                    tint = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(
            onClick = { viewModel.addNewTab(isPrivate = true) },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = "New Incognito Tab",
                tint = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun TabletOrMobileToolbar(
    isTablet: Boolean,
    activeTab: BrowserTab,
    tabsCount: Int = 1,
    addressInput: String,
    onAddressValueChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    isUrlInsecure: Boolean,
    isCurrentUrlBookmarked: Boolean,
    showTabsDialog: Boolean,
    showBookmarks: Boolean,
    showHistory: Boolean,
    pageBlockedCount: Int = 0,
    bookmarks: List<com.example.data.BookmarkItem> = emptyList(),
    history: List<com.example.data.HistoryItem> = emptyList(),
    onClearAddressInput: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleTabsSidebar: () -> Unit,
    onToggleBookmarksSidebar: () -> Unit,
    onToggleHistorySidebar: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onShowShield: () -> Unit,
    onShowSecurityDashboard: () -> Unit,
    onOpenFindInPage: () -> Unit,
    context: Context,
    viewModel: BrowserViewModel,
    addressBarFocusRequester: FocusRequester,
    onAddressFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val isIncognito = activeTab.isPrivate
    val toolbarBg = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface
    val omniboxBg = if (isIncognito) ChromeIncognitoOmnibox else MaterialTheme.colorScheme.surfaceContainer
    val contentTint = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface
    val subTint = if (isIncognito) ChromeIncognitoSubtext else MaterialTheme.colorScheme.onSurfaceVariant

    var isOmniboxFocused by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val currentDisplayMode by viewModel.displayMode.collectAsState()
    val adBlockEnabledState by viewModel.adBlockEnabled.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = toolbarBg
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!isTablet) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isTablet) {
                    val canNavigateBack = activeTab.canGoBack || (activeTab.url.isNotBlank() && activeTab.url != "about:blank")
                    IconButton(
                        onClick = { viewModel.goBack() },
                        enabled = canNavigateBack,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (canNavigateBack) contentTint else contentTint.copy(alpha = 0.32f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.goForward() },
                        enabled = activeTab.canGoForward,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (activeTab.canGoForward) contentTint else contentTint.copy(alpha = 0.32f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { if (activeTab.isLoading) viewModel.stop() else viewModel.reload() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = contentTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Home button (authentic Chrome Android left button)
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.goHome()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Home,
                        contentDescription = "Home",
                        tint = contentTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Authentic Google Chrome Pill Omnibox
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(omniboxBg)
                        .then(
                            if (isOmniboxFocused) {
                                Modifier.border(
                                    1.5.dp,
                                    if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(24.dp)
                                )
                            } else Modifier
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Chrome Page Info / SSL Lock button inside left of Omnibox
                    IconButton(
                        onClick = onShowSecurityDashboard,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                activeTab.url.isBlank() || activeTab.url == "about:blank" -> Icons.Default.Search
                                isUrlInsecure -> Icons.Outlined.Warning
                                else -> Icons.Outlined.Tune
                            },
                            contentDescription = "Page Info",
                            tint = if (isUrlInsecure && activeTab.url.isNotBlank()) MaterialTheme.colorScheme.error else subTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (addressInput.isEmpty()) {
                            Text(
                                text = if (isIncognito) "Search or type URL in Incognito" else "Search or type web address",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = subTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = addressInput,
                            onValueChange = onAddressValueChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                color = contentTint
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(addressBarFocusRequester)
                                .onFocusChanged {
                                    isOmniboxFocused = it.isFocused
                                    onAddressFocusChanged(it.isFocused)
                                },
                            cursorBrush = SolidColor(if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary)
                        )
                    }

                    if (addressInput.isNotEmpty() && isOmniboxFocused) {
                        IconButton(
                            onClick = onClearAddressInput,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear URL",
                                tint = subTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (!isOmniboxFocused && activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
                        IconButton(
                            onClick = { if (activeTab.isLoading) viewModel.stop() else viewModel.reload() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab.isLoading) "Stop" else "Reload",
                                tint = subTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (isTablet) {
                        IconButton(
                            onClick = onToggleBookmark,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isCurrentUrlBookmarked) Icons.Default.Star else Icons.Outlined.StarOutline,
                                contentDescription = "Bookmark",
                                tint = if (isCurrentUrlBookmarked) MaterialTheme.colorScheme.primary else subTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // On Mobile: Extensions Puzzle Button + Tab Switcher [N] button right next to Omnibox
                val extensionsList by viewModel.extensions.collectAsState()
                val enabledExtCount = remember(extensionsList) { extensionsList.count { it.isEnabled } }

                IconButton(
                    onClick = { viewModel.setExtensionsHubVisible(true) },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("toolbar_extensions_button")
                ) {
                    BadgedBox(
                        badge = {
                            if (enabledExtCount > 0) {
                                Badge { Text(enabledExtCount.toString(), fontSize = 8.sp) }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = "Extensions",
                            tint = if (enabledExtCount > 0) MaterialTheme.colorScheme.primary else contentTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (!isTablet) {
                    ChromeTabCountButton(
                        count = tabsCount,
                        isIncognito = isIncognito,
                        onClick = { viewModel.setTabsDialogVisible(true) }
                    )
                } else {
                    IconButton(
                        onClick = onShowShield,
                        modifier = Modifier.size(38.dp)
                    ) {
                        BadgedBox(
                            badge = {
                                if (adBlockEnabledState && pageBlockedCount > 0) {
                                    Badge { Text(if (pageBlockedCount > 99) "99+" else pageBlockedCount.toString(), fontSize = 9.sp) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (adBlockEnabledState) Icons.Filled.Shield else Icons.Outlined.Shield,
                                contentDescription = "AdShield",
                                tint = if (adBlockEnabledState) MaterialTheme.colorScheme.primary else subTint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleBookmarksSidebar,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bookmarks,
                            contentDescription = "Bookmarks",
                            tint = if (showBookmarks) MaterialTheme.colorScheme.primary else contentTint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Authentic Google Chrome 3-Dot Overflow Menu
                Box {
                    IconButton(
                        onClick = { showOverflowMenu = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = contentTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                        modifier = Modifier.widthIn(min = 256.dp)
                    ) {
                        // Chrome Top 5-Icon Action Bar inside 3-dot Menu
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.goForward()
                                },
                                enabled = activeTab.canGoForward
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Forward"
                                )
                            }
                            IconButton(
                                onClick = {
                                    showOverflowMenu = false
                                    onToggleBookmark()
                                }
                            ) {
                                Icon(
                                    imageVector = if (isCurrentUrlBookmarked) Icons.Default.Star else Icons.Outlined.StarOutline,
                                    contentDescription = "Bookmark",
                                    tint = if (isCurrentUrlBookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
                            }
                            IconButton(
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.setDownloadsVisible(true)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = "Downloads"
                                )
                            }
                            IconButton(
                                onClick = {
                                    showOverflowMenu = false
                                    onShowSecurityDashboard()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "Page Info"
                                )
                            }
                            IconButton(
                                onClick = {
                                    showOverflowMenu = false
                                    if (activeTab.isLoading) viewModel.stop() else viewModel.reload()
                                }
                            ) {
                                Icon(
                                    imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                    contentDescription = "Refresh"
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        DropdownMenuItem(
                            text = { Text("New tab") },
                            leadingIcon = { Icon(Icons.Outlined.AddBox, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.addNewTab(isPrivate = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("New Incognito tab") },
                            leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.addNewTab(isPrivate = true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Recent tabs / Reopen closed") },
                            leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.reopenClosedTab()
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        DropdownMenuItem(
                            text = { Text("History") },
                            leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                onToggleHistorySidebar()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Downloads") },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.setDownloadsVisible(true)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Bookmarks") },
                            leadingIcon = { Icon(Icons.Outlined.StarOutline, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                onToggleBookmarksSidebar()
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        if (activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
                            DropdownMenuItem(
                                text = { Text("Share...") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    try {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, activeTab.title)
                                            putExtra(Intent.EXTRA_TEXT, activeTab.url)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share link"))
                                    } catch (_: Exception) {}
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Find in page") },
                                leadingIcon = { Icon(Icons.Outlined.FindInPage, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenFindInPage()
                                }
                            )
                        }

                        // Chrome "Desktop site" toggle with Checkbox
                        DropdownMenuItem(
                            text = { Text("Desktop site") },
                            leadingIcon = { Icon(Icons.Outlined.DesktopWindows, contentDescription = null) },
                            trailingIcon = {
                                Checkbox(
                                    checked = currentDisplayMode == DisplayMode.DESKTOP,
                                    onCheckedChange = null
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                val nextMode = if (currentDisplayMode == DisplayMode.DESKTOP) DisplayMode.MOBILE else DisplayMode.DESKTOP
                                viewModel.setDisplayMode(nextMode)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("AdShield Guard")
                                    if (pageBlockedCount > 0) {
                                        Text(
                                            text = "$pageBlockedCount blocked on this page",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                onShowShield()
                            }
                        )

                        val currentOrientation by viewModel.screenOrientationMode.collectAsState()
                        val isImmersiveFsMenu by viewModel.isImmersivePageFullscreen.collectAsState()
                        DropdownMenuItem(
                            text = {
                                Text(if (isImmersiveFsMenu) "Keluar Layar Penuh (Full Screen)" else "Layar Penuh (Full Screen)")
                            },
                            leadingIcon = {
                                Icon(
                                    if (isImmersiveFsMenu) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.requestFullScreenToolToggle()
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Ekstensi Browser (WebExtensions)")
                                    Text(
                                        text = "$enabledExtCount aktif • Impor dari luar (.js/.zip/.css)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.setExtensionsHubVisible(true)
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Studio Pintasan & Custom JS") },
                            leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.setCustomJsStudioVisible(true)
                            }
                        )

                        val inspectEnabledMenu by viewModel.inspectToolsEnabled.collectAsState()
                        if (inspectEnabledMenu) {
                            DropdownMenuItem(
                                text = { Text("Developer Tools (Inspect)") },
                                leadingIcon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.toggleDevTools(activeTab.id)
                                }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            onClick = {
                                showOverflowMenu = false
                                onNavigateToSettings()
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Clear browsing data", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteSweep,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.clearCacheAndCookies(context)
                                viewModel.clearAllHistory()
                                Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        // Authentic Chrome Omnibox Quick Actions & Autocomplete Bar when Focused
        AnimatedVisibility(
            visible = isOmniboxFocused,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = toolbarBg,
                tonalElevation = 3.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    // Quick Actions Row (Copy URL, Share, Paste & Go)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
                            AssistChip(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", activeTab.url))
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                    focusManager.clearFocus()
                                },
                                label = { Text("Copy link", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(15.dp)) }
                            )
                            AssistChip(
                                onClick = {
                                    onAddressValueChange(activeTab.url)
                                },
                                label = { Text("Edit URL", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(15.dp)) }
                            )
                        }
                        AssistChip(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                if (!pasted.isNullOrBlank()) {
                                    onAddressValueChange(pasted)
                                    viewModel.navigateTo(pasted)
                                    focusManager.clearFocus()
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            label = { Text("Paste & Go", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(15.dp)) }
                        )
                    }

                    // Live Autocomplete Suggestions from Bookmarks & History
                    val query = addressInput.trim().lowercase()
                    val suggestions = remember(query, bookmarks, history) {
                        if (query.isBlank()) emptyList()
                        else {
                            val bMatches = bookmarks.filter {
                                it.title.lowercase().contains(query) || it.url.lowercase().contains(query)
                            }.map { Pair(it.title, it.url) }
                            val hMatches = history.filter {
                                it.title.lowercase().contains(query) || it.url.lowercase().contains(query)
                            }.map { Pair(it.title, it.url) }
                            (bMatches + hMatches).distinctBy { it.second }.take(4)
                        }
                    }

                    if (suggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        suggestions.forEach { (title, url) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onAddressValueChange(url)
                                        viewModel.navigateTo(url)
                                        focusManager.clearFocus()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Public,
                                    contentDescription = null,
                                    tint = subTint,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title.ifBlank { url },
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = contentTint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.NorthWest,
                                    contentDescription = "Fill URL",
                                    tint = subTint,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onAddressValueChange(url) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarTabsPanel(
    viewModel: BrowserViewModel,
    tabs: List<BrowserTab>,
    activeTabId: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tabs (${tabs.size})",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = { viewModel.setTabsDialogVisible(false) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close pane", modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { viewModel.addNewTab(isPrivate = false) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New tab", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { viewModel.addNewTab(isPrivate = true) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Incognito", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs, key = { it.id }) { tab ->
                val isActive = tab.id == activeTabId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewModel.selectTab(tab.id) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (tab.isPrivate) Icons.Default.VisibilityOff else Icons.Default.Public,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tab.title.ifBlank { "New Tab" },
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tab.url.ifBlank { "chrome://newtab" },
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.removeTab(tab.id) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarBookmarksPanel(
    viewModel: BrowserViewModel,
    bookmarks: List<com.example.data.BookmarkItem>,
    context: Context,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Bookmarks",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                if (bookmarks.isNotEmpty()) {
                    IconButton(onClick = {
                        viewModel.clearAllBookmarks()
                        Toast.makeText(context, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Clear all", modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { viewModel.setBookmarksVisible(false) }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (bookmarks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No bookmarks yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.navigateTo(bookmark.url)
                                viewModel.setBookmarksVisible(false)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.title,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = bookmark.url,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteBookmarkItem(bookmark.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarHistoryPanel(
    viewModel: BrowserViewModel,
    historyList: List<com.example.data.HistoryItem>,
    context: Context,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row {
                if (historyList.isNotEmpty()) {
                    IconButton(onClick = {
                        viewModel.clearAllHistory()
                        Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Clear history", modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { viewModel.setHistoryVisible(false) }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "History is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(historyList, key = { it.id }) { record ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                viewModel.navigateTo(record.url)
                                viewModel.setHistoryVisible(false)
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.title,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = record.url,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteHistoryItem(record.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MobileTabsDialog(
    viewModel: BrowserViewModel,
    tabs: List<BrowserTab>,
    activeTabId: String
) {
    val activeTab = tabs.find { it.id == activeTabId }
    var showIncognitoTabs by remember(activeTab?.isPrivate) {
        mutableStateOf(activeTab?.isPrivate == true)
    }
    var showTabSwitcherMenu by remember { mutableStateOf(false) }

    val filteredTabs = remember(tabs, showIncognitoTabs) {
        tabs.filter { it.isPrivate == showIncognitoTabs }
    }
    val regularCount = remember(tabs) { tabs.count { !it.isPrivate } }
    val incognitoCount = remember(tabs) { tabs.count { it.isPrivate } }

    Dialog(
        onDismissRequest = { viewModel.setTabsDialogVisible(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val bg = if (showIncognitoTabs) ChromeIncognitoBg else MaterialTheme.colorScheme.background
        val textPrimary = if (showIncognitoTabs) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface
        val textSecondary = if (showIncognitoTabs) ChromeIncognitoSubtext else MaterialTheme.colorScheme.onSurfaceVariant

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = bg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Chrome Tab Switcher Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: "+ New tab" button like Chrome Android
                    TextButton(
                        onClick = {
                            viewModel.addNewTab(isPrivate = showIncognitoTabs)
                            viewModel.setTabsDialogVisible(false)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New tab",
                            tint = textPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (showIncognitoTabs) "New Incognito" else "New tab",
                            color = textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Center Regular vs Incognito Segmented Switcher
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (showIncognitoTabs) ChromeIncognitoOmnibox else MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(modifier = Modifier.padding(3.dp)) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (!showIncognitoTabs) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                modifier = Modifier.clickable { showIncognitoTabs = false }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .border(
                                                1.5.dp,
                                                if (!showIncognitoTabs) MaterialTheme.colorScheme.onPrimaryContainer else textSecondary,
                                                RoundedCornerShape(4.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$regularCount",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 9.sp
                                            ),
                                            color = if (!showIncognitoTabs) MaterialTheme.colorScheme.onPrimaryContainer else textSecondary
                                        )
                                    }
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (showIncognitoTabs) ChromeIncognitoSurface else Color.Transparent,
                                modifier = Modifier.clickable { showIncognitoTabs = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VisibilityOff,
                                        contentDescription = "Incognito tabs",
                                        tint = if (showIncognitoTabs) ChromeIncognitoAccent else textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    if (incognitoCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "$incognitoCount",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (showIncognitoTabs) ChromeIncognitoAccent else textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Right: 3-dot Tab Switcher menu + Close
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { showTabSwitcherMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Tab options",
                                    tint = textPrimary
                                )
                            }
                            DropdownMenu(
                                expanded = showTabSwitcherMenu,
                                onDismissRequest = { showTabSwitcherMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("New tab") },
                                    leadingIcon = { Icon(Icons.Outlined.AddBox, null) },
                                    onClick = {
                                        showTabSwitcherMenu = false
                                        viewModel.addNewTab(isPrivate = false)
                                        viewModel.setTabsDialogVisible(false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New Incognito tab") },
                                    leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                                    onClick = {
                                        showTabSwitcherMenu = false
                                        viewModel.addNewTab(isPrivate = true)
                                        viewModel.setTabsDialogVisible(false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reopen closed tab") },
                                    leadingIcon = { Icon(Icons.Outlined.Restore, null) },
                                    onClick = {
                                        showTabSwitcherMenu = false
                                        viewModel.reopenClosedTab()
                                        viewModel.setTabsDialogVisible(false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Hibernasi tab lain (Hemat RAM)") },
                                    leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                    onClick = {
                                        showTabSwitcherMenu = false
                                        viewModel.requestHibernateInactiveTabs()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close all tabs", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showTabSwitcherMenu = false
                                        viewModel.closeAllTabs()
                                        viewModel.setTabsDialogVisible(false)
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.setTabsDialogVisible(false) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close tab switcher",
                                tint = textPrimary
                            )
                        }
                    }
                }

                if (filteredTabs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (showIncognitoTabs) Icons.Outlined.VisibilityOff else Icons.Outlined.Tab,
                                contentDescription = null,
                                tint = textSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (showIncognitoTabs) "No Incognito tabs open" else "No tabs open",
                                style = MaterialTheme.typography.titleMedium,
                                color = textPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    viewModel.addNewTab(isPrivate = showIncognitoTabs)
                                    viewModel.setTabsDialogVisible(false)
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (showIncognitoTabs) "Open Incognito Tab" else "Open New Tab")
                            }
                        }
                    }
                } else {
                    // Authentic Chrome 2-Column Tab Cards Grid
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredTabs, key = { it.id }) { tab ->
                            val isActive = tab.id == activeTabId
                            val cardColor = if (showIncognitoTabs) ChromeIncognitoSurface else MaterialTheme.colorScheme.surfaceContainer
                            val previewBg = if (showIncognitoTabs) ChromeIncognitoBg else MaterialTheme.colorScheme.surface
                            val borderStroke = if (isActive) {
                                BorderStroke(
                                    2.5.dp,
                                    if (showIncognitoTabs) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary
                                )
                            } else {
                                BorderStroke(
                                    1.dp,
                                    if (showIncognitoTabs) Color.White.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }

                            val domainHost = remember(tab.url) {
                                try {
                                    Uri.parse(tab.url).host?.removePrefix("www.") ?: ""
                                } catch (_: Exception) { "" }
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(198.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        viewModel.selectTab(tab.id)
                                        viewModel.setTabsDialogVisible(false)
                                    },
                                shape = RoundedCornerShape(18.dp),
                                color = cardColor,
                                border = borderStroke
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Tab Card Header (Favicon + Title + Close X)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (showIncognitoTabs) ChromeIncognitoOmnibox
                                                    else MaterialTheme.colorScheme.primaryContainer
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (tab.isPrivate) Icons.Default.VisibilityOff else Icons.Default.Public,
                                                contentDescription = null,
                                                tint = if (tab.isPrivate) ChromeIncognitoAccent else MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tab.title.ifBlank { "New Tab" },
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp
                                            ),
                                            color = textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = { viewModel.removeTab(tab.id) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close tab",
                                                tint = textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    // Tab Card Preview Body
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = previewBg
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (showIncognitoTabs) ChromeIncognitoOmnibox
                                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = domainHost.firstOrNull()?.uppercase() ?: "G",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (showIncognitoTabs) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = if (domainHost.isNotBlank()) domainHost else "Google New Tab",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (tab.url.isBlank() || tab.url == "about:blank") "chrome://newtab" else tab.url,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                color = textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                            if (tab.isSuspended && !isActive) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(100.dp),
                                                    color = MaterialTheme.colorScheme.secondaryContainer
                                                ) {
                                                    Text(
                                                        text = "Eco · Hemat RAM",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileBookmarksDialog(
    viewModel: BrowserViewModel,
    bookmarks: List<com.example.data.BookmarkItem>,
    context: Context
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredBookmarks = remember(bookmarks, searchQuery) {
        if (searchQuery.isBlank()) bookmarks
        else bookmarks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = { viewModel.setBookmarksVisible(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bookmarks", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setBookmarksVisible(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        if (bookmarks.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    viewModel.clearAllBookmarks()
                                    Toast.makeText(context, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Clear all", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (bookmarks.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search your bookmarks") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (filteredBookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Bookmarks,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching bookmarks" else "No bookmarks here yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredBookmarks, key = { it.id }) { bookmark ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        viewModel.navigateTo(bookmark.url)
                                        viewModel.setBookmarksVisible(false)
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bookmark,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = bookmark.title,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = bookmark.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteBookmarkItem(bookmark.id) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHistoryDialog(
    viewModel: BrowserViewModel,
    history: List<com.example.data.HistoryItem>,
    context: Context
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredHistory = remember(history, searchQuery) {
        if (searchQuery.isBlank()) history
        else history.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = { viewModel.setHistoryVisible(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("History", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setHistoryVisible(false) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        if (history.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    viewModel.clearAllHistory()
                                    Toast.makeText(context, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Clear browsing data", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (history.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search history") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (filteredHistory.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching history" else "Pages you visit appear here",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredHistory, key = { it.id }) { record ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        viewModel.navigateTo(record.url)
                                        viewModel.setHistoryVisible(false)
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = record.title,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = record.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val dateStr = remember(record.timestamp) {
                                            SimpleDateFormat("HH:mm · MMM d", Locale.getDefault()).format(Date(record.timestamp))
                                        }
                                        Text(
                                            text = dateStr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteHistoryItem(record.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WebErrorPlaceholderPage(
    errorMsg: String?,
    viewModel: BrowserViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = "Offline Error",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "This site can't be reached",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = errorMsg ?: "Check if there is a typo in the URL or verify your connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.reload() }) {
                Text("Reload")
            }
            OutlinedButton(onClick = { viewModel.goHome() }) {
                Text("Home")
            }
        }
    }
}

@Composable
fun DesktopBrowserLayout(
    viewModel: BrowserViewModel,
    activeTab: BrowserTab,
    tabs: List<BrowserTab>,
    bookmarks: List<com.example.data.BookmarkItem>,
    history: List<com.example.data.HistoryItem>,
    webViewPool: MutableMap<String, android.webkit.WebView>,
    onFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Unit,
    onNavigateToSettings: () -> Unit,
    isTablet: Boolean,
    addressInput: String,
    onAddressInputChanged: (String) -> Unit,
    isCurrentUrlBookmarked: Boolean,
    isUrlInsecure: Boolean,
    showBookmarks: Boolean,
    showHistory: Boolean,
    showTabsDialog: Boolean,
    adBlockEnabled: Boolean,
    trackerBlockEnabled: Boolean,
    pageBlockedCount: Int,
    onShowShield: () -> Unit,
    onShowSecurityDashboard: () -> Unit,
    onOpenFindInPage: () -> Unit,
    showFindInPage: Boolean,
    findInPageQuery: String,
    onFindQueryChange: (String) -> Unit,
    onFindNext: (Boolean) -> Unit,
    onCloseFindInPage: () -> Unit,
    onFullscreenView: (android.view.View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreenView: () -> Unit,
    addressBarFocusRequester: FocusRequester,
    onAddressFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isImmersiveDesktopFs by viewModel.isImmersivePageFullscreen.collectAsState()
    val isIncognitoDesktop = activeTab.isPrivate
    val desktopFrameBg = if (isIncognitoDesktop) ChromeIncognitoBg else MaterialTheme.colorScheme.surfaceContainerLowest
    val isSidebarOpen = showTabsDialog || showBookmarks || showHistory

    val toggleTabsAction = {
        if (showTabsDialog) {
            viewModel.setTabsDialogVisible(false)
        } else {
            viewModel.setBookmarksVisible(false)
            viewModel.setHistoryVisible(false)
            viewModel.setTabsDialogVisible(true)
        }
    }
    val toggleBookmarksAction = {
        if (showBookmarks) {
            viewModel.setBookmarksVisible(false)
        } else {
            viewModel.setTabsDialogVisible(false)
            viewModel.setHistoryVisible(false)
            viewModel.setBookmarksVisible(true)
        }
    }
    val toggleHistoryAction = {
        if (showHistory) {
            viewModel.setHistoryVisible(false)
        } else {
            viewModel.setTabsDialogVisible(false)
            viewModel.setBookmarksVisible(false)
            viewModel.setHistoryVisible(true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(desktopFrameBg)
            .then(if (isImmersiveDesktopFs) Modifier else Modifier.statusBarsPadding())
    ) {
        if (!isImmersiveDesktopFs) {
            // Tier 1: Structured Desktop Window Header & Tab Strip
            DesktopTabBar(
                viewModel = viewModel,
                tabs = tabs,
                activeTabId = activeTab.id,
                isSidebarOpen = isSidebarOpen,
                onToggleSidebar = toggleTabsAction
            )

            // Tier 2: Structured Desktop Navigation, Omnibox & Action Dock
            if (showFindInPage) {
                ChromeFindInPageBar(
                    query = findInPageQuery,
                    onQueryChange = onFindQueryChange,
                    onFindPrev = { onFindNext(false) },
                    onFindNext = { onFindNext(true) },
                    onClose = onCloseFindInPage
                )
            } else {
                DesktopToolbar(
                    viewModel = viewModel,
                    activeTab = activeTab,
                    addressInput = addressInput,
                    onAddressValueChange = onAddressInputChanged,
                    onSearchSubmit = {
                        if (addressInput.isNotBlank()) {
                            viewModel.navigateTo(addressInput.trim())
                            focusManager.clearFocus()
                        }
                    },
                    isUrlInsecure = isUrlInsecure,
                    isCurrentUrlBookmarked = isCurrentUrlBookmarked,
                    showBookmarks = showBookmarks,
                    showHistory = showHistory,
                    showTabs = showTabsDialog,
                    pageBlockedCount = pageBlockedCount,
                    onToggleBookmarks = toggleBookmarksAction,
                    onToggleHistory = toggleHistoryAction,
                    onToggleTabs = toggleTabsAction,
                    onNavigateToSettings = onNavigateToSettings,
                    onShowShield = onShowShield,
                    onShowSecurityDashboard = onShowSecurityDashboard,
                    onOpenFindInPage = onOpenFindInPage,
                    context = context,
                    addressBarFocusRequester = addressBarFocusRequester,
                    onAddressFocusChanged = onAddressFocusChanged
                )
            }

            // Tier 3: Structured Desktop Bookmarks & Quick Workspace Tools Bar
            DesktopBookmarkBar(
                viewModel = viewModel,
                bookmarks = bookmarks,
                tabsCount = tabs.size,
                isSidebarOpen = isSidebarOpen,
                onToggleSidebar = toggleTabsAction
            )
        }

        val shortcutBarVisible by viewModel.shortcutBarVisible.collectAsState()
        val shortcutBarPosition by viewModel.shortcutBarPosition.collectAsState()
        val tabSecurityThreats by viewModel.tabSecurityThreats.collectAsState()
        val activeDesktopThreat = tabSecurityThreats[activeTab.id]

        if (!isImmersiveDesktopFs && shortcutBarVisible && shortcutBarPosition == com.example.manager.ShortcutBarPosition.TOP) {
            ChromeShortcutAndJsBar(
                viewModel = viewModel,
                activeTab = activeTab,
                activeWebView = webViewPool[activeTab.id],
                isIncognito = activeTab.isPrivate,
                onOpenFindInPage = onOpenFindInPage,
                onOpenSecurityShield = onShowSecurityDashboard
            )
        }

        if (activeTab.isLoading) {
            val animatedProgress by animateFloatAsState(
                targetValue = (activeTab.progress / 100f).coerceIn(0.05f, 1f),
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "desktop_progress"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = if (isIncognitoDesktop) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        } else {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = if (isIncognitoDesktop) Color.White.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        }

        val inspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()
        val devToolsStates by viewModel.devToolsStates.collectAsState()
        val activeState = devToolsStates[activeTab.id] ?: DevToolsState()

        // Adaptive Desktop Workspace Viewport (Docked Side-by-Side on Wide Screens, Slide-Over Drawer on Compact Screens)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isWideWorkspace = maxWidth >= 640.dp

            Row(modifier = Modifier.fillMaxSize()) {
                // Docked Left Sidebar on Wide/Landscape Viewports
                AnimatedVisibility(
                    visible = isWideWorkspace && isSidebarOpen,
                    enter = slideInHorizontally() + fadeIn(),
                    exit = slideOutHorizontally() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .width(276.dp)
                            .fillMaxHeight(),
                        color = if (isIncognitoDesktop) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                DesktopSidebarSwitcherHeader(
                                    showTabs = showTabsDialog,
                                    showBookmarks = showBookmarks,
                                    showHistory = showHistory,
                                    onSelectTabs = {
                                        viewModel.setBookmarksVisible(false)
                                        viewModel.setHistoryVisible(false)
                                        viewModel.setTabsDialogVisible(true)
                                    },
                                    onSelectBookmarks = {
                                        viewModel.setTabsDialogVisible(false)
                                        viewModel.setHistoryVisible(false)
                                        viewModel.setBookmarksVisible(true)
                                    },
                                    onSelectHistory = {
                                        viewModel.setTabsDialogVisible(false)
                                        viewModel.setBookmarksVisible(false)
                                        viewModel.setHistoryVisible(true)
                                    }
                                )
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    if (showTabsDialog) {
                                        SidebarTabsPanel(viewModel, tabs, activeTab.id)
                                    } else if (showBookmarks) {
                                        SidebarBookmarksPanel(viewModel, bookmarks, context)
                                    } else if (showHistory) {
                                        SidebarHistoryPanel(viewModel, history, context)
                                    }
                                }
                            }
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }

                // Main Desktop Web Content Area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    if (activeDesktopThreat != null) {
                        ChromeSecurityThreatInterstitial(
                            threat = activeDesktopThreat,
                            onBackToSafety = {
                                viewModel.clearTabSecurityThreat(activeTab.id)
                                val wv = webViewPool[activeTab.id]
                                if (wv?.canGoBack() == true) {
                                    viewModel.goBack()
                                } else {
                                    viewModel.goHome()
                                }
                            },
                            onProceedAnyway = {
                                viewModel.proceedDespiteSecurityThreat(activeTab.id, activeDesktopThreat)
                            }
                        )
                    } else if (activeTab.loadError != null) {
                        WebErrorPlaceholderPage(activeTab.loadError, viewModel)
                    } else if (activeTab.url.isBlank() || activeTab.url == "about:blank") {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigate = { viewModel.navigateTo(it) },
                            onSearchSubmit = { viewModel.navigateTo(it) }
                        )
                    } else {
                        key(activeTab.id) {
                            CustomWebView(
                                tab = activeTab,
                                viewModel = viewModel,
                                adBlockEnabled = adBlockEnabled,
                                trackerBlockEnabled = trackerBlockEnabled,
                                webViewPool = webViewPool,
                                onFileChooser = onFileChooser,
                                onFullscreenView = onFullscreenView,
                                onHideFullscreenView = onHideFullscreenView
                            )
                        }
                    }
                }

                // Docked Right DevTools Inspector on Wide Viewports
                AnimatedVisibility(
                    visible = isWideWorkspace && inspectToolsEnabled && activeState.isOpen,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                ) {
                    DevToolsPanel(
                        viewModel = viewModel,
                        tabId = activeTab.id,
                        devToolsState = activeState,
                        webView = webViewPool[activeTab.id],
                        onClose = { viewModel.toggleDevTools(activeTab.id) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Slide-Over Left Sidebar Drawer on Compact/Portrait Viewports (Never crushes the Desktop WebView!)
            if (!isWideWorkspace && isSidebarOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable {
                            viewModel.setTabsDialogVisible(false)
                            viewModel.setBookmarksVisible(false)
                            viewModel.setHistoryVisible(false)
                        }
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !isWideWorkspace && isSidebarOpen,
                enter = slideInHorizontally() + fadeIn(),
                exit = slideOutHorizontally() + fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth(0.84f)
                        .fillMaxHeight(),
                    color = if (isIncognitoDesktop) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        DesktopSidebarSwitcherHeader(
                            showTabs = showTabsDialog,
                            showBookmarks = showBookmarks,
                            showHistory = showHistory,
                            onSelectTabs = {
                                viewModel.setBookmarksVisible(false)
                                viewModel.setHistoryVisible(false)
                                viewModel.setTabsDialogVisible(true)
                            },
                            onSelectBookmarks = {
                                viewModel.setTabsDialogVisible(false)
                                viewModel.setHistoryVisible(false)
                                viewModel.setBookmarksVisible(true)
                            },
                            onSelectHistory = {
                                viewModel.setTabsDialogVisible(false)
                                viewModel.setBookmarksVisible(false)
                                viewModel.setHistoryVisible(true)
                            }
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (showTabsDialog) {
                                SidebarTabsPanel(viewModel, tabs, activeTab.id)
                            } else if (showBookmarks) {
                                SidebarBookmarksPanel(viewModel, bookmarks, context)
                            } else if (showHistory) {
                                SidebarHistoryPanel(viewModel, history, context)
                            }
                        }
                    }
                }
            }

            // Bottom Sheet Docked DevTools on Compact Viewports
            androidx.compose.animation.AnimatedVisibility(
                visible = !isWideWorkspace && inspectToolsEnabled && activeState.isOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.48f)
            ) {
                DevToolsPanel(
                    viewModel = viewModel,
                    tabId = activeTab.id,
                    devToolsState = activeState,
                    webView = webViewPool[activeTab.id],
                    onClose = { viewModel.toggleDevTools(activeTab.id) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        AnimatedVisibility(
            visible = !isImmersiveDesktopFs && shortcutBarVisible && shortcutBarPosition == com.example.manager.ShortcutBarPosition.BOTTOM,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ChromeShortcutAndJsBar(
                    viewModel = viewModel,
                    activeTab = activeTab,
                    activeWebView = webViewPool[activeTab.id],
                    isIncognito = activeTab.isPrivate,
                    onOpenFindInPage = onOpenFindInPage,
                    onOpenSecurityShield = onShowSecurityDashboard
                )
            }
        }
    }
}

@Composable
private fun DesktopSidebarSwitcherHeader(
    showTabs: Boolean,
    showBookmarks: Boolean,
    showHistory: Boolean,
    onSelectTabs: () -> Unit,
    onSelectBookmarks: () -> Unit,
    onSelectHistory: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = showTabs,
                onClick = onSelectTabs,
                label = { Text("Tab", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Tab, null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = showBookmarks,
                onClick = onSelectBookmarks,
                label = { Text("Bookmark", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Bookmarks, null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = showHistory,
                onClick = onSelectHistory,
                label = { Text("Riwayat", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.History, null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
fun DesktopTabBar(
    viewModel: BrowserViewModel,
    tabs: List<BrowserTab>,
    activeTabId: String,
    isSidebarOpen: Boolean = false,
    onToggleSidebar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeTab = tabs.find { it.id == activeTabId }
    val isIncognito = activeTab?.isPrivate == true
    val stripBg = if (isIncognito) ChromeIncognitoBg else MaterialTheme.colorScheme.surfaceContainerLowest
    val activeTabBg = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface
    val accentColor = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary
    val orientationMode by viewModel.screenOrientationMode.collectAsState()
    val tiltState by viewModel.deviceTiltState.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(stripBg)
            .padding(start = 6.dp, end = 6.dp, top = 5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Left: Workspace Sidebar Toggle Button with Tab Counter
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isSidebarOpen) accentColor.copy(alpha = 0.18f) else Color.Transparent,
            modifier = Modifier
                .padding(bottom = 3.dp, end = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggleSidebar() }
                .testTag("desktop_sidebar_toggle")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ViewSidebar,
                    contentDescription = "Workspace Sidebar",
                    tint = if (isSidebarOpen) accentColor else if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${tabs.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSidebarOpen) accentColor else if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Center-Left: Scrollable Structured Desktop Tabs + Add Tab Button
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { tab ->
                val isActive = tab.id == activeTabId
                val tabColor = if (isActive) {
                    activeTabBg
                } else {
                    if (isIncognito) ChromeIncognitoSurface.copy(alpha = 0.42f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                }
                val textColor = if (isIncognito) {
                    if (isActive) ChromeIncognitoText else ChromeIncognitoSubtext
                } else {
                    if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                }
                val domainInitial = remember(tab.url, tab.title) {
                    try {
                        val h = Uri.parse(tab.url).host?.removePrefix("www.") ?: ""
                        (h.firstOrNull() ?: tab.title.firstOrNull() ?: 'G').uppercaseChar().toString()
                    } catch (_: Exception) { "G" }
                }

                Surface(
                    modifier = Modifier
                        .widthIn(min = 128.dp, max = 216.dp)
                        .fillMaxHeight()
                        .clickable { viewModel.selectTab(tab.id) },
                    shape = RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp),
                    color = tabColor,
                    tonalElevation = if (isActive) 2.dp else 0.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Active Accent Line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(if (isActive) accentColor else Color.Transparent)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (tab.isPrivate) ChromeIncognitoOmnibox
                                            else if (isActive) accentColor.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceContainerHighest
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (tab.isPrivate) {
                                        Icon(
                                            imageVector = Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = ChromeIncognitoAccent,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    } else {
                                        Text(
                                            text = domainInitial,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) accentColor else textColor
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tab.title.ifBlank { "Tab Baru" },
                                    fontSize = 12.sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (tab.isPlayingAudio) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Playing audio",
                                        tint = accentColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                } else if (tab.isSuspended && !isActive) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Outlined.Eco,
                                        contentDescription = "Eco Suspended",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.removeTab(tab.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Tab",
                                    tint = textColor.copy(alpha = 0.72f),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            // "+" New Tab Button right next to the last tab
            IconButton(
                onClick = { viewModel.addNewTab(isPrivate = false) },
                modifier = Modifier
                    .padding(bottom = 2.dp)
                    .size(30.dp)
                    .testTag("desktop_new_tab_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Tab Baru",
                    tint = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp)
                )
            }

            IconButton(
                onClick = { viewModel.addNewTab(isPrivate = true) },
                modifier = Modifier
                    .padding(bottom = 2.dp)
                    .size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.VisibilityOff,
                    contentDescription = "Tab Samaran Baru",
                    tint = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

    }
}

@Composable
fun DesktopToolbar(
    viewModel: BrowserViewModel,
    activeTab: BrowserTab,
    addressInput: String,
    onAddressValueChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    isUrlInsecure: Boolean,
    isCurrentUrlBookmarked: Boolean,
    showBookmarks: Boolean,
    showHistory: Boolean,
    showTabs: Boolean,
    pageBlockedCount: Int,
    onToggleBookmarks: () -> Unit,
    onToggleHistory: () -> Unit,
    onToggleTabs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onShowShield: () -> Unit,
    onShowSecurityDashboard: () -> Unit,
    onOpenFindInPage: () -> Unit,
    context: Context,
    addressBarFocusRequester: FocusRequester,
    onAddressFocusChanged: (Boolean) -> Unit = {}
) {
    var showDesktopOverflowMenu by remember { mutableStateOf(false) }
    val inspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()
    val adBlockEnabledState by viewModel.adBlockEnabled.collectAsState()
    val selectedSearchEngine by viewModel.selectedSearchEngine.collectAsState()
    val extensionsList by viewModel.extensions.collectAsState()
    val enabledExtCount = remember(extensionsList) { extensionsList.count { it.isEnabled } }
    val currentOrientation by viewModel.screenOrientationMode.collectAsState()
    val desktopZoomScale by viewModel.desktopZoomScale.collectAsState()
    val isImmersiveFsDesktop by viewModel.isImmersivePageFullscreen.collectAsState()
    val isIncognito = activeTab.isPrivate
    val toolbarBg = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface
    val omniboxBg = if (isIncognito) ChromeIncognitoOmnibox else MaterialTheme.colorScheme.surfaceContainer
    val contentTint = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface
    val subTint = if (isIncognito) ChromeIncognitoSubtext else MaterialTheme.colorScheme.onSurfaceVariant
    val accentTint = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary
    val canNavigateBack = activeTab.canGoBack || (activeTab.url.isNotBlank() && activeTab.url != "about:blank")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = toolbarBg
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompactDesktop = maxWidth < 620.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompactDesktop) 6.dp else 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Left Structured Navigation Capsule (Back, Forward, Reload, Home)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.goBack() },
                            enabled = canNavigateBack,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("desktop_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (canNavigateBack) contentTint else contentTint.copy(alpha = 0.32f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.goForward() },
                            enabled = activeTab.canGoForward,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (activeTab.canGoForward) contentTint else contentTint.copy(alpha = 0.32f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = { if (activeTab.isLoading) viewModel.stop() else viewModel.reload() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = contentTint,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        if (!isCompactDesktop) {
                            IconButton(
                                onClick = { viewModel.goHome() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Home,
                                    contentDescription = "Home",
                                    tint = contentTint,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }

                // 2. Center Structured Desktop Omnibox Pill
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(omniboxBg)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isUrlInsecure && activeTab.url.isNotBlank()) Icons.Outlined.Warning else Icons.Outlined.Lock,
                        contentDescription = "Page Info",
                        tint = if (isUrlInsecure && activeTab.url.isNotBlank()) MaterialTheme.colorScheme.error else accentTint,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { onShowSecurityDashboard() }
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (addressInput.isEmpty()) {
                            Text(
                                text = "Cari di $selectedSearchEngine atau ketik URL (Viewport Desktop 1280p)",
                                style = MaterialTheme.typography.bodySmall,
                                color = subTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = addressInput,
                            onValueChange = onAddressValueChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                color = contentTint
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(addressBarFocusRequester)
                                .onFocusChanged { onAddressFocusChanged(it.isFocused) },
                            cursorBrush = SolidColor(accentTint)
                        )
                    }

                    if (addressInput.isNotEmpty()) {
                        IconButton(
                            onClick = { onAddressValueChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = subTint, modifier = Modifier.size(14.dp))
                        }
                    }

                    // Desktop Zoom Scale Badge inside Omnibox (Tap to reset or open menu)
                    if (desktopZoomScale != 100 || !isCompactDesktop) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable {
                                    if (desktopZoomScale != 100) {
                                        viewModel.setDesktopZoomScale(100)
                                        Toast.makeText(context, "Zoom Desktop direset ke 100%", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showDesktopOverflowMenu = true
                                    }
                                }
                        ) {
                            Text(
                                text = "${desktopZoomScale}%",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            viewModel.toggleBookmark(activeTab.title, activeTab.url)
                            val msg = if (isCurrentUrlBookmarked) "Bookmark dihapus" else "Ditambahkan ke Bookmark"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (isCurrentUrlBookmarked) Icons.Default.Star else Icons.Outlined.StarOutline,
                            contentDescription = "Bookmark",
                            tint = if (isCurrentUrlBookmarked) accentTint else subTint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 3. Right Structured Action Dock Capsule
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // AdShield Guard Button
                        IconButton(onClick = onShowShield, modifier = Modifier.size(32.dp)) {
                            BadgedBox(
                                badge = {
                                    if (adBlockEnabledState && pageBlockedCount > 0) {
                                        Badge { Text(if (pageBlockedCount > 99) "99+" else pageBlockedCount.toString(), fontSize = 8.sp) }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (adBlockEnabledState) Icons.Filled.Shield else Icons.Outlined.Shield,
                                    contentDescription = "AdShield",
                                    tint = if (adBlockEnabledState) accentTint else subTint,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // Chrome Extensions Puzzle Button
                        IconButton(
                            onClick = { viewModel.setExtensionsHubVisible(true) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("desktop_extensions_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (enabledExtCount > 0) {
                                        Badge { Text(enabledExtCount.toString(), fontSize = 8.sp) }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Extension,
                                    contentDescription = "Extensions",
                                    tint = if (enabledExtCount > 0) accentTint else subTint,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // 1-Tap Full Screen Tool Button
                        IconButton(
                            onClick = { viewModel.requestFullScreenToolToggle() },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("desktop_fullscreen_button")
                        ) {
                            Icon(
                                imageVector = if (isImmersiveFsDesktop) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                contentDescription = "Full Screen",
                                tint = if (isImmersiveFsDesktop) accentTint else subTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 1-Tap Shortcut Bar Toggle on Desktop Toolbar
                        val isDesktopShortcutVisible by viewModel.shortcutBarVisible.collectAsState()
                        if (!isCompactDesktop) {
                            IconButton(
                                onClick = { viewModel.setShortcutBarVisible(!isDesktopShortcutVisible) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("desktop_toggle_shortcut_bar")
                            ) {
                                Icon(
                                    imageVector = if (isDesktopShortcutVisible) Icons.Filled.Bolt else Icons.Outlined.Bolt,
                                    contentDescription = "Pintasan & JS Bar",
                                    tint = if (isDesktopShortcutVisible) accentTint else subTint,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    viewModel.setDisplayMode(DisplayMode.MOBILE)
                                    Toast.makeText(context, "Beralih ke Mode Mobile", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PhoneAndroid,
                                    contentDescription = "Mode Mobile",
                                    tint = accentTint,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // Unified Structured Desktop 3-Dot Chrome Menu
                        Box {
                            IconButton(
                                onClick = { showDesktopOverflowMenu = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = contentTint,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showDesktopOverflowMenu,
                                onDismissRequest = { showDesktopOverflowMenu = false },
                                modifier = Modifier.widthIn(min = 272.dp)
                            ) {
                                // Structured Header 1: Desktop Zoom & Viewport Controller
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Zoom Desktop",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            OutlinedIconButton(
                                                onClick = { viewModel.adjustDesktopZoomScale(-15) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", modifier = Modifier.size(14.dp))
                                            }
                                            Text(
                                                text = "${desktopZoomScale}%",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable { viewModel.setDesktopZoomScale(100) }
                                                    .padding(horizontal = 6.dp)
                                            )
                                            OutlinedIconButton(
                                                onClick = { viewModel.adjustDesktopZoomScale(15) },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Zoom In", modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = { Text("Tab baru") },
                                    leadingIcon = { Icon(Icons.Outlined.AddBox, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.addNewTab(isPrivate = false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Tab Samaran (Incognito)") },
                                    leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.addNewTab(isPrivate = true)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isImmersiveFsDesktop) "Keluar Layar Penuh (Full Screen)" else "Layar Penuh Imersif (Full Screen)") },
                                    leadingIcon = { Icon(if (isImmersiveFsDesktop) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.requestFullScreenToolToggle()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Kembali ke Mode Mobile") },
                                    leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.setDisplayMode(DisplayMode.MOBILE)
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Ekstensi Browser (WebExtensions)")
                                            Text(
                                                text = "$enabledExtCount aktif • Impor file / URL luar",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Extension, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.setExtensionsHubVisible(true)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Studio Pintasan & Custom JS") },
                                    leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.setCustomJsStudioVisible(true)
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                DropdownMenuItem(
                                    text = { Text("Panel Samping: Daftar Tab (${activeTab.title.take(12)})") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ViewSidebar, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        onToggleTabs()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Panel Samping: Bookmark") },
                                    leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        onToggleBookmarks()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Panel Samping: Riwayat (History)") },
                                    leadingIcon = { Icon(Icons.Outlined.History, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        onToggleHistory()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Unduhan (Downloads)") },
                                    leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        viewModel.setDownloadsVisible(true)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cari di halaman") },
                                    leadingIcon = { Icon(Icons.Outlined.FindInPage, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        onOpenFindInPage()
                                    }
                                )
                                if (inspectToolsEnabled) {
                                    DropdownMenuItem(
                                        text = { Text("Developer Tools (Inspect)") },
                                        leadingIcon = { Icon(Icons.Outlined.Code, null) },
                                        onClick = {
                                            showDesktopOverflowMenu = false
                                            viewModel.toggleDevTools(activeTab.id)
                                        }
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("Setelan (Settings)") },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                    onClick = {
                                        showDesktopOverflowMenu = false
                                        onNavigateToSettings()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopBookmarkBar(
    viewModel: BrowserViewModel,
    bookmarks: List<com.example.data.BookmarkItem>,
    tabsCount: Int = 1,
    isSidebarOpen: Boolean = false,
    onToggleSidebar: () -> Unit = {}
) {
    val quickLinks = listOf(
        Pair("Google", "https://www.google.com"),
        Pair("YouTube", "https://www.youtube.com"),
        Pair("Wikipedia", "https://www.wikipedia.org"),
        Pair("GitHub", "https://github.com")
    )
    val isImmersiveFs by viewModel.isImmersivePageFullscreen.collectAsState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Zone 1: Structured Desktop Workspace Tools
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSidebarOpen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { onToggleSidebar() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ViewSidebar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Panel ($tabsCount)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                    .clickable { viewModel.setExtensionsHubVisible(true) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Ekstensi",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { viewModel.requestFullScreenToolToggle() }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isImmersiveFs) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Full Screen",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { viewModel.setElementZapperActive(true) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Zapper",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            VerticalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Zone 2: Quick Sites & User Bookmarks
            quickLinks.forEach { (name, url) ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.navigateTo(url) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = name,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (bookmarks.isNotEmpty()) {
                VerticalDivider(modifier = Modifier.padding(vertical = 4.dp))
                bookmarks.take(10).forEach { bmark ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.navigateTo(bmark.url) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = bmark.title,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WebGuardShieldDialog(
    viewModel: BrowserViewModel,
    activeTab: BrowserTab,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val adBlockDisabledDomains by viewModel.adBlockDisabledDomains.collectAsState()
    val liveShieldLogs by com.yourbrowser.adblock.AlexShieldV5LogStore.logs.collectAsState()

    val currentUrl = activeTab.url
    val host = remember(currentUrl) {
        try {
            Uri.parse(currentUrl).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    val isSiteWhitelisted = remember(host, adBlockDisabledDomains) {
        if (host.isEmpty()) false else {
            adBlockDisabledDomains.any { domain ->
                val clean = domain.lowercase().trim().removePrefix("www.")
                host == clean || host.endsWith(".$clean")
            }
        }
    }

    val blockedLogs = remember(currentUrl, liveShieldLogs) {
        viewModel.getBlockedLogsForPage(currentUrl)
    }

    val adCount = blockedLogs.count { it.type == "Ad" }
    val trackerCount = blockedLogs.count { it.type == "Tracker" }
    val popupCount = blockedLogs.count { it.type == "Popup" }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Chrome AdShield",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = if (host.isNotEmpty()) host else "Global Protection",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    val stealthTrapsDefused by viewModel.stealthTrapsDefusedCount.collectAsState()
                    val zapperRulesVersion by viewModel.zapperRulesVersion.collectAsState()
                    val customZapperCount = remember(host, zapperRulesVersion) {
                        if (host.isEmpty()) 0 else viewModel.getCustomZapperRulesForHost(host).size
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ChromeStatTile("Ads Blocked", adCount.toString(), Modifier.weight(1f))
                        ChromeStatTile("Trackers", trackerCount.toString(), Modifier.weight(1f))
                        ChromeStatTile("Anti-Detect", stealthTrapsDefused.toString(), Modifier.weight(1f))
                    }

                    // Stealth Anti-AdBlock Status Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Stealth Anti-AdBlock Engine V6",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Surrogate JS, 1x1 GIF Spoof, Bait Proxy & Native toString() Cloak aktif",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Shields for this site",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    text = if (isSiteWhitelisted) "Paused on this site" else "Active & filtering ads in Stealth mode",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = !isSiteWhitelisted,
                                onCheckedChange = { enableAdBlock ->
                                    if (host.isNotEmpty()) {
                                        viewModel.setAdBlockDisabledForDomain(host, !enableAdBlock)
                                        viewModel.reload()
                                    }
                                }
                            )
                        }
                    }

                    // Custom Element Zapper & RAM Hibernation Actions
                    if (host.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    viewModel.setElementZapperActive(true)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("launch_element_zapper_button")
                            ) {
                                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hapus Elemen", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.requestHibernateInactiveTabs()
                                    Toast.makeText(context, "Tab latar belakang dihibernasi (Hemat RAM)", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hibernate_tabs_button")
                            ) {
                                Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Hemat RAM", maxLines = 1)
                            }
                        }

                        if (customZapperCount > 0) {
                            TextButton(
                                onClick = {
                                    viewModel.clearCustomZapperRulesForHost(host)
                                    viewModel.reload()
                                    Toast.makeText(context, "Aturan elemen kustom direset untuk $host", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset $customZapperCount Elemen Terhapus di $host")
                            }
                        }
                    }

                    if (blockedLogs.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Recently blocked on this page ($popupCount popups)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                blockedLogs.take(4).forEach { log ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = log.host.ifBlank { log.url },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = log.type,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (host.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                viewModel.setAdBlockDisabledForDomain(host, true)
                                viewModel.clearBlockedLogs()
                                Toast.makeText(context, "Paused Shield for $host & reloading...", Toast.LENGTH_SHORT).show()
                                viewModel.reload()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Fix CAPTCHA / Verification Loop")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementZapperFloatingBar(
    selectedSelector: String,
    selectedTag: String,
    onZapConfirm: () -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("element_zapper_floating_bar")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.TouchApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Mode Pemilih Elemen (Zapper)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (selectedSelector.isBlank()) {
                                "Ketuk elemen iklan/banner di halaman untuk memilih"
                            } else {
                                "Dipilih: <$selectedTag> $selectedSelector"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedSelector.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Zapper")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onZapConfirm,
                    enabled = selectedSelector.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Hapus Elemen")
                }
                OutlinedButton(
                    onClick = onUndo,
                    modifier = Modifier.weight(0.75f)
                ) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Undo")
                }
            }
        }
    }
}

@Composable
private fun ChromeStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SecurityDashboardDialog(
    onDismiss: () -> Unit,
    isUrlInsecure: Boolean,
    adBlockEnabled: Boolean,
    trackerBlockEnabled: Boolean,
    context: Context,
    viewModel: BrowserViewModel? = null,
    activeTabId: String? = null
) {
    val trackerCookiesBlocked by viewModel?.trackerCookiesBlockedCount?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val fingerprintingBlocked by viewModel?.fingerprintingBlockedCount?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val privacyShieldMode by viewModel?.privacyShieldMode?.collectAsState() ?: remember { mutableStateOf(com.example.manager.PrivacyShieldMode.STANDARD) }
    val tabs by viewModel?.tabs?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val currentTab = tabs.find { it.id == activeTabId }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Page Info",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (!currentTab?.url.isNullOrBlank()) {
                                Text(
                                    text = currentTab?.url ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isUrlInsecure) Icons.Outlined.Warning else Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = if (isUrlInsecure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isUrlInsecure) "Connection is not secure" else "Connection is secure",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    text = if (isUrlInsecure) "Avoid entering sensitive data on HTTP pages" else "Your information (for example, passwords or credit card numbers) is private when it is sent to this site.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Cookies, Trackers & Privacy (${privacyShieldMode.name})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "$trackerCookiesBlocked Cross-site trackers blocked",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "$fingerprintingBlocked Fingerprinting scripts blocked",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    if (viewModel != null) {
                        val httpsOnly by viewModel.httpsOnlyMode.collectAsState()
                        val phishingGuard by viewModel.phishingProtectionEnabled.collectAsState()
                        val blockCamMic by viewModel.blockCameraMicStrict.collectAsState()
                        val blockClipboard by viewModel.blockClipboardHijack.collectAsState()
                        val blockPopups by viewModel.blockHiddenPopups.collectAsState()

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Perisai Keamanan Aktif",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Paksa HTTPS-Only", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text("Tingkatkan HTTP ke HTTPS & beri peringatan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = httpsOnly, onCheckedChange = { viewModel.setHttpsOnlyMode(it) })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Deteksi Situs Phishing & Malware", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text("Blokir domain tiruan, scam & IDN homograph", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = phishingGuard, onCheckedChange = { viewModel.setPhishingProtectionEnabled(it) })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Blokir Kamera & Mikrofon", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text("Tolak otomatis akses webcam & suara", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = blockCamMic, onCheckedChange = { viewModel.setBlockCameraMicStrict(it) })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Proteksi Clipboard & Pop-up", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                        Text("Cegah pencurian clipboard & redirect iklan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = blockClipboard && blockPopups,
                                        onCheckedChange = {
                                            viewModel.setBlockClipboardHijack(it)
                                            viewModel.setBlockHiddenPopups(it)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            viewModel?.emergencyPrivacyReset()
                            Toast.makeText(context, "All browsing data cleared", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Browsing Data & Reset")
                    }
                }
            }
        }
    }
}

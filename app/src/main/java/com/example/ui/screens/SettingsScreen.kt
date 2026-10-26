package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.manager.AppTheme
import com.example.manager.DisplayMode
import com.example.ui.components.DeveloperAiAttributionSection
import com.example.ui.viewmodel.BrowserViewModel

enum class SettingsCategory(val title: String, val icon: ImageVector) {
    GENERAL("Basics & Search", Icons.Outlined.Search),
    SECURITY("Privacy & Security", Icons.Outlined.Shield),
    CONNECTIVITY("Performance & Downloads", Icons.Outlined.Speed),
    STORAGE("Site Data & Storage", Icons.Outlined.Storage),
    ABOUT("About Chrome Browser", Icons.Outlined.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onBack)

    val currentEngine by viewModel.selectedSearchEngine.collectAsState()
    val adBlockMode by viewModel.adBlockMode.collectAsState()
    val isTrackerEnabled by viewModel.trackerBlockEnabled.collectAsState()
    val currentTheme by viewModel.appTheme.collectAsState()
    val currentMode by viewModel.displayMode.collectAsState()
    val isInspectToolsEnabled by viewModel.inspectToolsEnabled.collectAsState()
    val allowScreenRecording by viewModel.allowScreenRecording.collectAsState()
    val isStreamingBoostEnabled by viewModel.streamingBoostEnabled.collectAsState()
    val downloadAcceleration by viewModel.downloadAcceleration.collectAsState()
    val isDataSaverEnabled by viewModel.dataSaverEnabled.collectAsState()
    val isBackgroundPlaybackEnabled by viewModel.backgroundPlaybackEnabled.collectAsState()
    val openPopupsInNewTab by viewModel.openPopupsInNewTab.collectAsState()
    val blockSuspiciousRedirects by viewModel.blockSuspiciousRedirects.collectAsState()

    val privacyShieldMode by viewModel.privacyShieldMode.collectAsState()
    val preventWebRtcLeak by viewModel.preventWebRtcLeak.collectAsState()
    val strictWebRtcMode by viewModel.strictWebRtcMode.collectAsState()
    val clearCookiesOnExit by viewModel.clearCookiesOnExit.collectAsState()
    val forceWebPageDarkMode by viewModel.forceWebPageDarkMode.collectAsState()

    var showEngineMenu by remember { mutableStateOf(false) }
    var showSitePermissions by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }

    if (showSitePermissions) {
        SitePermissionsDialog(viewModel = viewModel, onDismiss = { showSitePermissions = false })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.resetToDefaults()
                            Toast.makeText(context, "Reset to default settings", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Reset")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isTablet = maxWidth >= 700.dp

            if (isTablet) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Chrome Settings Navigation Rail / Pane
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(260.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SettingsCategory.values().forEach { category ->
                                val isSelected = category == selectedCategory
                                NavigationDrawerItem(
                                    label = { Text(category.title, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                                    selected = isSelected,
                                    onClick = { selectedCategory = category },
                                    icon = { Icon(category.icon, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    // Detail Content Pane
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        AnimatedContent(
                            targetState = selectedCategory,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "chrome_settings_pane"
                        ) { category ->
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 680.dp)
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                when (category) {
                                    SettingsCategory.GENERAL -> {
                                        SearchEngineModule(
                                            currentEngine = currentEngine,
                                            showEngineMenu = showEngineMenu,
                                            onShowMenuChange = { showEngineMenu = it },
                                            onSelectEngine = { engine ->
                                                viewModel.setSearchEngine(engine)
                                                showEngineMenu = false
                                                Toast.makeText(context, "$engine set as default", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        ThemeSelectionModule(
                                            currentTheme = currentTheme,
                                            onSelectTheme = { viewModel.setTheme(it) },
                                            forceWebPageDarkMode = forceWebPageDarkMode,
                                            onForceWebPageDarkModeToggle = { viewModel.setForceWebPageDarkMode(it) }
                                        )
                                        DisplayModeModule(
                                            currentMode = currentMode,
                                            onSelectMode = { viewModel.setDisplayMode(it) }
                                        )
                                        MediaSettingsModule(
                                            isBackgroundPlaybackEnabled = isBackgroundPlaybackEnabled,
                                            onBackgroundPlaybackToggle = { viewModel.setBackgroundPlaybackEnabled(it) }
                                        )
                                        ScreenCompatibilityModule(
                                            allowScreenRecording = allowScreenRecording,
                                            onToggle = { viewModel.setAllowScreenRecording(it) }
                                        )
                                        DeveloperToolsModule(
                                            isInspectToolsEnabled = isInspectToolsEnabled,
                                            onInspectToolsToggle = { viewModel.setInspectToolsEnabled(it) },
                                            currentMode = currentMode
                                        )
                                    }

                                    SettingsCategory.SECURITY -> {
                                        SecurityShieldsModule(
                                            adBlockMode = adBlockMode,
                                            isTrackerEnabled = isTrackerEnabled,
                                            openPopupsInNewTab = openPopupsInNewTab,
                                            blockSuspiciousRedirects = blockSuspiciousRedirects,
                                            onAdBlockModeChange = { viewModel.setAdBlockMode(it) },
                                            onTrackerToggle = { viewModel.setTrackerBlockEnabled(it) },
                                            onOpenPopupsInNewTabToggle = { viewModel.setOpenPopupsInNewTab(it) },
                                            onBlockSuspiciousRedirectsToggle = { viewModel.setBlockSuspiciousRedirects(it) }
                                        )
                                        PrivacySettingsModule(
                                            privacyShieldMode = privacyShieldMode,
                                            preventWebRtcLeak = preventWebRtcLeak,
                                            strictWebRtcMode = strictWebRtcMode,
                                            clearCookiesOnExit = clearCookiesOnExit,
                                            onPrivacyShieldModeChange = { viewModel.setPrivacyShieldMode(it) },
                                            onPreventWebRtcLeakToggle = { viewModel.setPreventWebRtcLeak(it) },
                                            onStrictWebRtcModeToggle = { viewModel.setStrictWebRtcMode(it) },
                                            onClearCookiesOnExitToggle = { viewModel.setClearCookiesOnExit(it) },
                                            onOpenSitePermissions = { showSitePermissions = true }
                                        )
                                    }

                                    SettingsCategory.CONNECTIVITY -> {
                                        FastInternetModule(
                                            isStreamingBoostEnabled = isStreamingBoostEnabled,
                                            downloadAcceleration = downloadAcceleration,
                                            isDataSaverEnabled = isDataSaverEnabled,
                                            onStreamingBoostToggle = { viewModel.setStreamingBoostEnabled(it) },
                                            onDownloadAccelerationSelect = { viewModel.setDownloadAcceleration(it) },
                                            onDataSaverToggle = { viewModel.setDataSaverEnabled(it) }
                                        )
                                    }

                                    SettingsCategory.STORAGE -> {
                                        DataCleanersModule(
                                            onClearHistory = {
                                                viewModel.clearAllHistory()
                                                Toast.makeText(context, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                                            },
                                            onClearBookmarks = {
                                                viewModel.clearAllBookmarks()
                                                Toast.makeText(context, "Saved bookmarks cleared", Toast.LENGTH_SHORT).show()
                                            },
                                            onClearCache = {
                                                viewModel.clearCacheAndCookies(context)
                                                Toast.makeText(context, "Cache and cookies cleared", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }

                                    SettingsCategory.ABOUT -> {
                                        SettingsAboutBanner()
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Responsive Single-Column Chrome Settings on Handhelds
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SearchEngineModule(
                        currentEngine = currentEngine,
                        showEngineMenu = showEngineMenu,
                        onShowMenuChange = { showEngineMenu = it },
                        onSelectEngine = { engine ->
                            viewModel.setSearchEngine(engine)
                            showEngineMenu = false
                            Toast.makeText(context, "$engine set as default", Toast.LENGTH_SHORT).show()
                        }
                    )

                    SecurityShieldsModule(
                        adBlockMode = adBlockMode,
                        isTrackerEnabled = isTrackerEnabled,
                        openPopupsInNewTab = openPopupsInNewTab,
                        blockSuspiciousRedirects = blockSuspiciousRedirects,
                        onAdBlockModeChange = { viewModel.setAdBlockMode(it) },
                        onTrackerToggle = { viewModel.setTrackerBlockEnabled(it) },
                        onOpenPopupsInNewTabToggle = { viewModel.setOpenPopupsInNewTab(it) },
                        onBlockSuspiciousRedirectsToggle = { viewModel.setBlockSuspiciousRedirects(it) }
                    )

                    PrivacySettingsModule(
                        privacyShieldMode = privacyShieldMode,
                        preventWebRtcLeak = preventWebRtcLeak,
                        strictWebRtcMode = strictWebRtcMode,
                        clearCookiesOnExit = clearCookiesOnExit,
                        onPrivacyShieldModeChange = { viewModel.setPrivacyShieldMode(it) },
                        onPreventWebRtcLeakToggle = { viewModel.setPreventWebRtcLeak(it) },
                        onStrictWebRtcModeToggle = { viewModel.setStrictWebRtcMode(it) },
                        onClearCookiesOnExitToggle = { viewModel.setClearCookiesOnExit(it) },
                        onOpenSitePermissions = { showSitePermissions = true }
                    )

                    FastInternetModule(
                        isStreamingBoostEnabled = isStreamingBoostEnabled,
                        downloadAcceleration = downloadAcceleration,
                        isDataSaverEnabled = isDataSaverEnabled,
                        onStreamingBoostToggle = { viewModel.setStreamingBoostEnabled(it) },
                        onDownloadAccelerationSelect = { viewModel.setDownloadAcceleration(it) },
                        onDataSaverToggle = { viewModel.setDataSaverEnabled(it) }
                    )

                    ThemeSelectionModule(
                        currentTheme = currentTheme,
                        onSelectTheme = { viewModel.setTheme(it) },
                        forceWebPageDarkMode = forceWebPageDarkMode,
                        onForceWebPageDarkModeToggle = { viewModel.setForceWebPageDarkMode(it) }
                    )

                    DisplayModeModule(
                        currentMode = currentMode,
                        onSelectMode = { viewModel.setDisplayMode(it) }
                    )

                    MediaSettingsModule(
                        isBackgroundPlaybackEnabled = isBackgroundPlaybackEnabled,
                        onBackgroundPlaybackToggle = { viewModel.setBackgroundPlaybackEnabled(it) }
                    )

                    ScreenCompatibilityModule(
                        allowScreenRecording = allowScreenRecording,
                        onToggle = { viewModel.setAllowScreenRecording(it) }
                    )

                    DeveloperToolsModule(
                        isInspectToolsEnabled = isInspectToolsEnabled,
                        onInspectToolsToggle = { viewModel.setInspectToolsEnabled(it) },
                        currentMode = currentMode
                    )

                    DataCleanersModule(
                        onClearHistory = {
                            viewModel.clearAllHistory()
                            Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                        },
                        onClearBookmarks = {
                            viewModel.clearAllBookmarks()
                            Toast.makeText(context, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                        },
                        onClearCacheOnly = {
                            viewModel.clearBrowserCacheOnly(context)
                            Toast.makeText(context, "Browser cache cleared (cookies preserved)", Toast.LENGTH_SHORT).show()
                        },
                        onClearCache = {
                            viewModel.clearCacheAndCookies(context, closeTabs = false)
                            Toast.makeText(context, "Cookies, site data & cache cleared", Toast.LENGTH_SHORT).show()
                        }
                    )

                    SettingsAboutBanner()

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ChromeSettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SearchEngineModule(
    currentEngine: String,
    showEngineMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onSelectEngine: (String) -> Unit
) {
    ChromeSettingsSectionCard(title = "Search engine") {
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onShowMenuChange(true) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Address bar search engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentEngine,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select engine")
                }
            }

            DropdownMenu(
                expanded = showEngineMenu,
                onDismissRequest = { onShowMenuChange(false) },
                modifier = Modifier.widthIn(min = 240.dp)
            ) {
                com.example.manager.SearchEngine.getAllEngines().forEach { engine ->
                    val isSelected = currentEngine.equals(engine.engineName, ignoreCase = true)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = engine.engineName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (engine.description.isNotBlank()) {
                                    Text(
                                        text = engine.description,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        onClick = { onSelectEngine(engine.engineName) }
                    )
                }
            }
        }
    }
}

@Composable
fun ThemeSelectionModule(
    currentTheme: AppTheme,
    onSelectTheme: (AppTheme) -> Unit,
    forceWebPageDarkMode: Boolean,
    onForceWebPageDarkModeToggle: (Boolean) -> Unit
) {
    ChromeSettingsSectionCard(title = "Theme & Appearance") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppTheme.values().forEach { theme ->
                val isSelected = currentTheme == theme
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectTheme(theme) },
                    label = {
                        Text(
                            text = theme.name,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Dark theme for web contents", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Automatically render web pages in dark mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = forceWebPageDarkMode,
                onCheckedChange = onForceWebPageDarkModeToggle
            )
        }
    }
}

@Composable
fun DisplayModeModule(
    currentMode: DisplayMode,
    onSelectMode: (DisplayMode) -> Unit
) {
    ChromeSettingsSectionCard(title = "Default Site View") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DisplayMode.values().forEach { mode ->
                val isSelected = currentMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectMode(mode) },
                    label = {
                        Text(
                            text = mode.name,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun MediaSettingsModule(
    isBackgroundPlaybackEnabled: Boolean,
    onBackgroundPlaybackToggle: (Boolean) -> Unit
) {
    ChromeSettingsSectionCard(title = "Media & Playback") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Background audio playback", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Keep media playing when switching apps or locking screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isBackgroundPlaybackEnabled,
                onCheckedChange = onBackgroundPlaybackToggle
            )
        }
    }
}

@Composable
fun ScreenCompatibilityModule(
    allowScreenRecording: Boolean,
    onToggle: (Boolean) -> Unit
) {
    ChromeSettingsSectionCard(title = "Screen Capture Privacy") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Allow screenshots & screen sharing", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    if (allowScreenRecording) "Screen recording and sharing enabled"
                    else "Screenshots and screen recording blocked for privacy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = allowScreenRecording,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun DeveloperToolsModule(
    isInspectToolsEnabled: Boolean,
    onInspectToolsToggle: (Boolean) -> Unit,
    currentMode: DisplayMode
) {
    ChromeSettingsSectionCard(title = "Developer Options") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Enable Chrome DevTools Inspector", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Inspect DOM, Console, Network, and Storage in Desktop mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isInspectToolsEnabled,
                onCheckedChange = onInspectToolsToggle
            )
        }
    }
}

@Composable
fun SecurityShieldsModule(
    adBlockMode: com.example.manager.AdBlockMode,
    isTrackerEnabled: Boolean,
    openPopupsInNewTab: Boolean,
    blockSuspiciousRedirects: Boolean,
    onAdBlockModeChange: (com.example.manager.AdBlockMode) -> Unit,
    onTrackerToggle: (Boolean) -> Unit,
    onOpenPopupsInNewTabToggle: (Boolean) -> Unit,
    onBlockSuspiciousRedirectsToggle: (Boolean) -> Unit
) {
    ChromeSettingsSectionCard(title = "AdShield & Content Filtering") {
        Text("AdBlock level", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            com.example.manager.AdBlockMode.values().forEach { mode ->
                val isSelected = adBlockMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = { onAdBlockModeChange(mode) },
                    label = {
                        Text(
                            text = mode.name,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Anti-tracking protection", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Block cross-site analytics and tracking scripts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isTrackerEnabled, onCheckedChange = onTrackerToggle)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Open pop-ups in new tab", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Isolate allowed pop-up links into separate tabs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = openPopupsInNewTab, onCheckedChange = onOpenPopupsInNewTabToggle)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Block suspicious redirects", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Prevent automatic third-party ad redirects",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = blockSuspiciousRedirects, onCheckedChange = onBlockSuspiciousRedirectsToggle)
        }
    }
}

@Composable
fun PrivacySettingsModule(
    privacyShieldMode: com.example.manager.PrivacyShieldMode,
    preventWebRtcLeak: Boolean,
    strictWebRtcMode: Boolean,
    clearCookiesOnExit: Boolean,
    onPrivacyShieldModeChange: (com.example.manager.PrivacyShieldMode) -> Unit,
    onPreventWebRtcLeakToggle: (Boolean) -> Unit,
    onStrictWebRtcModeToggle: (Boolean) -> Unit,
    onClearCookiesOnExitToggle: (Boolean) -> Unit,
    onOpenSitePermissions: () -> Unit
) {
    ChromeSettingsSectionCard(title = "Privacy & Security") {
        Text("Safe Browsing & Privacy Mode", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            com.example.manager.PrivacyShieldMode.values().forEach { mode ->
                val isSelected = privacyShieldMode == mode
                FilterChip(
                    selected = isSelected,
                    onClick = { onPrivacyShieldModeChange(mode) },
                    label = {
                        Text(
                            text = mode.name,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("WebRTC IP leak protection", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Wraps WebRTC peer connections at page load (onPageStarted) to restrict local IP candidate exposure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = preventWebRtcLeak, onCheckedChange = onPreventWebRtcLeakToggle)
        }

        if (preventWebRtcLeak) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !strictWebRtcMode,
                    onClick = { onStrictWebRtcModeToggle(false) },
                    label = { Text("Standard Compatible", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = strictWebRtcMode,
                    onClick = { onStrictWebRtcModeToggle(true) },
                    label = { Text("Strict Block", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Clear cookies & site data on exit", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Automatically wipe cookies, site storage, and saved tabs when closing the browser",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = clearCookiesOnExit, onCheckedChange = onClearCookiesOnExitToggle)
        }

        Spacer(modifier = Modifier.height(12.dp))

        val dbEncryptionStatus by com.example.data.AppDatabase.encryptionStatusFlow.collectAsState()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (dbEncryptionStatus.isEncrypted) Icons.Outlined.Lock else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (dbEncryptionStatus.isEncrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Status Database: ${dbEncryptionStatus.title}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = dbEncryptionStatus.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
            onClick = onOpenSitePermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Site Settings & Permissions")
        }
    }
}

@Composable
fun FastInternetModule(
    isStreamingBoostEnabled: Boolean,
    downloadAcceleration: String,
    isDataSaverEnabled: Boolean,
    onStreamingBoostToggle: (Boolean) -> Unit,
    onDownloadAccelerationSelect: (String) -> Unit,
    onDataSaverToggle: (Boolean) -> Unit
) {
    ChromeSettingsSectionCard(title = "Performance & Downloads") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Preload pages & media buffer", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Pre-fetches resources for faster browsing and video playback",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isStreamingBoostEnabled, onCheckedChange = onStreamingBoostToggle)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("Parallel download threads", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("1", "4", "8", "16").forEach { threads ->
                FilterChip(
                    selected = downloadAcceleration == threads,
                    onClick = { onDownloadAccelerationSelect(threads) },
                    label = {
                        Text(
                            text = "${threads}x",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Lite mode / Data Saver", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Reduce mobile data usage by blocking heavy page bloat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isDataSaverEnabled, onCheckedChange = onDataSaverToggle)
        }
    }
}

@Composable
fun DataCleanersModule(
    onClearHistory: () -> Unit,
    onClearBookmarks: () -> Unit,
    onClearCacheOnly: () -> Unit = {},
    onClearCache: () -> Unit
) {
    ChromeSettingsSectionCard(title = "Clear Browsing Data") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onClearCacheOnly,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Cache Only", textAlign = TextAlign.Center)
                }
                Button(
                    onClick = onClearCache,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Cookies & Site Data", textAlign = TextAlign.Center)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onClearHistory,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear History")
                }
                OutlinedButton(
                    onClick = onClearBookmarks,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Bookmarks")
                }
            }
        }
    }
}

@Composable
fun SettingsAboutBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AdBlockBrandEmblem(
                size = 66.dp,
                cornerRadius = 18.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Alex Browser",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Fast, lightweight, and responsive modern web browser with built-in AdShield protection. External connections are limited to your selected search engine, Android Safe Browsing, HTTPS AdBlock filter list updates (EasyList, EasyPrivacy, Peter Lowe), and URLs you explicitly open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            DeveloperAiAttributionSection(
                isCompact = false,
                showCardContainer = false
            )
        }
    }
}

@Composable
fun SitePermissionsDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    var permissionsMap by remember { mutableStateOf(viewModel.permissionManager.getAllPermissions()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Site Settings & Permissions", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
            ) {
                if (permissionsMap.isEmpty()) {
                    Text(
                        text = "No customized site permissions saved.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(permissionsMap.keys.toList()) { origin ->
                            val sitePerms = permissionsMap[origin] ?: emptyMap()
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = origin,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    sitePerms.forEach { (type, state) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(type.name, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                text = state.name,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (state == com.example.manager.PermissionState.ALLOW) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    TextButton(
                                        onClick = {
                                            viewModel.permissionManager.resetWebsitePermission(origin)
                                            permissionsMap = viewModel.permissionManager.getAllPermissions()
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("Reset", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            if (permissionsMap.isNotEmpty()) {
                TextButton(
                    onClick = {
                        viewModel.permissionManager.clearAllPermissions()
                        permissionsMap = emptyMap()
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

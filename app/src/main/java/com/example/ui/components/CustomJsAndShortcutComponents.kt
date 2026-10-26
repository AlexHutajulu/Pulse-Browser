package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.manager.*
import com.example.manager.DisplayMode
import com.example.ui.theme.*
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.BrowserViewModel
import java.net.URLEncoder
import java.util.UUID

fun resolveToolbarIcon(iconName: String): ImageVector {
    return when (iconName) {
        "Fullscreen" -> Icons.Outlined.Fullscreen
        "DarkMode" -> Icons.Outlined.DarkMode
        "Speed" -> Icons.Outlined.Speed
        "LockOpen" -> Icons.Outlined.LockOpen
        "AutoAwesome" -> Icons.Outlined.AutoAwesome
        "Visibility" -> Icons.Outlined.Visibility
        "PlayArrow" -> Icons.Outlined.PlayArrow
        "CleaningServices" -> Icons.Outlined.CleaningServices
        "Collections" -> Icons.Outlined.Collections
        "MenuBook" -> Icons.AutoMirrored.Outlined.MenuBook
        "Bolt" -> Icons.Outlined.Bolt
        "Terminal" -> Icons.Outlined.Terminal
        else -> Icons.Outlined.Code
    }
}

fun printOrSaveWebViewAsPdf(context: Context, webView: WebView?, title: String) {
    if (webView == null) {
        Toast.makeText(context, "Buka halaman web terlebih dahulu untuk menyimpan PDF", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        val jobName = (title.ifBlank { "Chrome_Page" }).take(40) + "_Document"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager?.print(jobName, printAdapter, PrintAttributes.Builder().build())
    } catch (e: Exception) {
        Toast.makeText(context, "Gagal membuka pencetak PDF: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Scrollable Chrome Shortcut & Custom JavaScript Toolbar Bar.
 * Can be placed at the TOP (below Omnibox) or BOTTOM of the screen.
 */
@Composable
fun ChromeShortcutAndJsBar(
    viewModel: BrowserViewModel,
    activeTab: BrowserTab,
    activeWebView: WebView?,
    isIncognito: Boolean,
    onOpenFindInPage: () -> Unit,
    onOpenSecurityShield: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val orientationMode by viewModel.screenOrientationMode.collectAsState()
    val shortcutBarPos by viewModel.shortcutBarPosition.collectAsState()
    val enabledShortcuts by viewModel.enabledBuiltInShortcuts.collectAsState()
    val customScripts by viewModel.customJsScripts.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val displayMode by viewModel.displayMode.collectAsState()
    val isImmersiveFs by viewModel.isImmersivePageFullscreen.collectAsState()
    val tiltState by viewModel.deviceTiltState.collectAsState()

    val barBg = if (isIncognito) ChromeIncognitoSurface else MaterialTheme.colorScheme.surface
    val chipBg = if (isIncognito) ChromeIncognitoOmnibox else MaterialTheme.colorScheme.surfaceContainerHigh
    val chipContent = if (isIncognito) ChromeIncognitoText else MaterialTheme.colorScheme.onSurface
    val accentColor = if (isIncognito) ChromeIncognitoAccent else MaterialTheme.colorScheme.primary

    var scrolledDownToggle by remember { mutableStateOf(true) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = barBg,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 0. Full Screen Immersive Tool Pill (1-tap Kiwi/Chrome Full Screen)
                if (enabledShortcuts.contains(BuiltInShortcut.FULLSCREEN_PAGE.id)) {
                    ShortcutActionPill(
                        label = if (isImmersiveFs) "Keluar Full Screen" else "Full Screen",
                        icon = if (isImmersiveFs) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        isHighlighted = isImmersiveFs,
                        containerColor = if (isImmersiveFs) accentColor.copy(alpha = 0.2f) else chipBg,
                        contentColor = if (isImmersiveFs) accentColor else chipContent,
                        borderColor = if (isImmersiveFs) accentColor.copy(alpha = 0.6f) else null,
                        testTag = "shortcut_fullscreen_page",
                        onClick = {
                            viewModel.triggerFullScreenTool(activeWebView) { msg ->
                                if (msg.isNotBlank() && msg != "null") {
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                // Chrome Extensions Hub & External Importer Pill
                val enabledExtCount = remember(extensions) { extensions.count { it.isEnabled } }
                ShortcutActionPill(
                    label = "Ekstensi ($enabledExtCount)",
                    icon = Icons.Outlined.Extension,
                    isHighlighted = true,
                    containerColor = accentColor.copy(alpha = 0.16f),
                    contentColor = accentColor,
                    borderColor = accentColor.copy(alpha = 0.45f),
                    testTag = "shortcut_open_extensions_hub",
                    onClick = { viewModel.setExtensionsHubVisible(true) }
                )

                // 3. Pinned Browser Extensions (1-tap trigger)
                val pinnedExtensions = remember(extensions) {
                    extensions.filter { it.isEnabled && it.isPinnedToToolbar }
                }
                pinnedExtensions.forEach { ext ->
                    ShortcutActionPill(
                        label = ext.name,
                        icon = resolveExtensionIcon(ext.iconName),
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "pinned_ext_${ext.id}",
                        onClick = {
                            BrowserExtensionManager.executeExtensionInWebView(
                                webView = activeWebView,
                                extension = ext,
                                isManualTrigger = true
                            ) { msg ->
                                Toast.makeText(context, "${ext.name}: $msg", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // 4. "+ Buat / Studio JS" Button (Opens Custom JS Toolbar Builder)
                if (enabledShortcuts.contains(BuiltInShortcut.CUSTOM_JS_STUDIO.id)) {
                    ShortcutActionPill(
                        label = "+ Toolbar JS",
                        icon = Icons.Outlined.Terminal,
                        isHighlighted = true,
                        containerColor = accentColor.copy(alpha = 0.15f),
                        contentColor = accentColor,
                        borderColor = accentColor.copy(alpha = 0.45f),
                        testTag = "shortcut_open_js_studio",
                        onClick = { viewModel.setCustomJsStudioVisible(true) }
                    )
                }

                // 5. User's Enabled Custom JavaScript Toolbar Buttons!
                val activeScripts = remember(customScripts) { customScripts.filter { it.isEnabled } }
                activeScripts.forEach { script ->
                    val hasAutoRun = script.autoRunMode != JsAutoRunMode.MANUAL_ONLY
                    ShortcutActionPill(
                        label = if (hasAutoRun) "${script.name} • AUTO" else script.name,
                        icon = resolveToolbarIcon(script.iconName),
                        isHighlighted = hasAutoRun,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        borderColor = if (hasAutoRun) accentColor.copy(alpha = 0.4f) else null,
                        testTag = "custom_js_btn_${script.id}",
                        onClick = {
                            CustomJsToolbarManager.executeScriptInWebView(activeWebView, script) { resultMsg ->
                                val feedback = if (resultMsg.isNotBlank() && resultMsg != "null" && resultMsg != "OK") {
                                    "${script.name}: $resultMsg"
                                } else {
                                    "Toolbar '${script.name}' dijalankan"
                                }
                                Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                // 4. Built-in Quick Shortcuts
                if (enabledShortcuts.contains(BuiltInShortcut.DESKTOP_MODE.id)) {
                    val isDesktop = displayMode == DisplayMode.DESKTOP
                    ShortcutActionPill(
                        label = if (isDesktop) "Desktop Aktif" else "Mode Desktop",
                        icon = Icons.Outlined.DesktopWindows,
                        isHighlighted = isDesktop,
                        containerColor = if (isDesktop) accentColor.copy(alpha = 0.18f) else chipBg,
                        contentColor = if (isDesktop) accentColor else chipContent,
                        testTag = "shortcut_desktop_mode",
                        onClick = {
                            val next = if (isDesktop) DisplayMode.MOBILE else DisplayMode.DESKTOP
                            viewModel.setDisplayMode(next)
                            Toast.makeText(
                                context,
                                if (next == DisplayMode.DESKTOP) "Mode Desktop diaktifkan" else "Mode Mobile diaktifkan",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.READER_MODE.id)) {
                    ShortcutActionPill(
                        label = "Mode Baca",
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_reader_mode",
                        onClick = {
                            val readerTpl = CustomJsToolbarManager.TEMPLATE_GALLERY.firstOrNull { it.id == "tpl_reader_view" }
                            if (readerTpl != null) {
                                CustomJsToolbarManager.executeScriptInWebView(activeWebView, readerTpl) { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.TRANSLATE_PAGE.id)) {
                    ShortcutActionPill(
                        label = "Terjemahkan",
                        icon = Icons.Outlined.Translate,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_translate_page",
                        onClick = {
                            val curUrl = activeTab.url
                            if (curUrl.isBlank() || curUrl == "about:blank") {
                                Toast.makeText(context, "Buka halaman web terlebih dahulu untuk menerjemahkan", Toast.LENGTH_SHORT).show()
                            } else {
                                val encoded = URLEncoder.encode(curUrl, "UTF-8")
                                val translateUrl = "https://translate.google.com/translate?sl=auto&tl=id&u=$encoded"
                                viewModel.navigateTo(translateUrl)
                            }
                        }
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.FIND_IN_PAGE.id)) {
                    ShortcutActionPill(
                        label = "Cari Teks",
                        icon = Icons.Outlined.FindInPage,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_find_in_page",
                        onClick = onOpenFindInPage
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.SAVE_PDF.id)) {
                    ShortcutActionPill(
                        label = "Simpan PDF",
                        icon = Icons.Outlined.PictureAsPdf,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_save_pdf",
                        onClick = {
                            printOrSaveWebViewAsPdf(context, activeWebView, activeTab.title)
                        }
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.SCROLL_TOP_BOTTOM.id)) {
                    ShortcutActionPill(
                        label = if (scrolledDownToggle) "Ke Bawah" else "Ke Atas",
                        icon = if (scrolledDownToggle) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_scroll_jump",
                        onClick = {
                            if (activeWebView != null) {
                                val js = if (scrolledDownToggle) {
                                    "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});"
                                } else {
                                    "window.scrollTo({top: 0, behavior: 'smooth'});"
                                }
                                activeWebView.evaluateJavascript(js, null)
                                scrolledDownToggle = !scrolledDownToggle
                            }
                        }
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.INCOGNITO_TAB.id)) {
                    ShortcutActionPill(
                        label = "Tab Samaran",
                        icon = Icons.Outlined.VisibilityOff,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_incognito_tab",
                        onClick = {
                            viewModel.addNewTab(isPrivate = true)
                            Toast.makeText(context, "Tab Samaran (Incognito) dibuka", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                if (enabledShortcuts.contains(BuiltInShortcut.SECURITY_SHIELD.id)) {
                    ShortcutActionPill(
                        label = "Perisai Aman",
                        icon = Icons.Outlined.Shield,
                        containerColor = chipBg,
                        contentColor = chipContent,
                        testTag = "shortcut_security_shield",
                        onClick = onOpenSecurityShield
                    )
                }

                // Position Switcher Chip (Move Bar to Top or Bottom)
                ShortcutActionPill(
                    label = if (shortcutBarPos == ShortcutBarPosition.TOP) "Geser ke Bawah" else "Geser ke Atas",
                    icon = if (shortcutBarPos == ShortcutBarPosition.TOP) Icons.Outlined.VerticalAlignBottom else Icons.Outlined.VerticalAlignTop,
                    containerColor = chipBg,
                    contentColor = chipContent.copy(alpha = 0.85f),
                    testTag = "shortcut_toggle_bar_position",
                    onClick = {
                        val nextPos = viewModel.toggleShortcutBarPosition()
                        Toast.makeText(
                            context,
                            if (nextPos == ShortcutBarPosition.TOP) "Bar pintasan digeser ke Atas" else "Bar pintasan digabung ke Navigasi Bawah",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                // Collapse / Hide Bar Chip (Can be reopened anytime from Bottom Navigation Bar)
                ShortcutActionPill(
                    label = "Tutup Bar",
                    icon = if (shortcutBarPos == ShortcutBarPosition.BOTTOM) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    containerColor = chipBg,
                    contentColor = chipContent.copy(alpha = 0.85f),
                    testTag = "shortcut_collapse_bar",
                    onClick = {
                        viewModel.setShortcutBarVisible(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun ShortcutActionPill(
    label: String,
    icon: ImageVector,
    isHighlighted: Boolean = false,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = if (borderColor != null) BorderStroke(1.dp, borderColor) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.5.sp,
                    fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Full-Featured Chrome Material 3 Studio for Custom JavaScript Toolbars,
 * Ready-to-Use JS Templates, JSON Import/Export, and Shortcut Bar Configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomJsStudioDialog(
    viewModel: BrowserViewModel,
    activeWebView: WebView?,
    activeUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scripts by viewModel.customJsScripts.collectAsState()
    val enabledShortcuts by viewModel.enabledBuiltInShortcuts.collectAsState()
    val shortcutBarPos by viewModel.shortcutBarPosition.collectAsState()
    val shortcutBarVisible by viewModel.shortcutBarVisible.collectAsState()
    val orientationMode by viewModel.screenOrientationMode.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0 = Toolbar JS Saya, 1 = Buat/Edit JS, 2 = Template & Pintasan, 3 = Impor/Ekspor
    var editingScriptId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var descInput by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf("Code") }
    var jsCodeInput by remember { mutableStateOf("") }
    var autoRunMode by remember { mutableStateOf(JsAutoRunMode.MANUAL_ONLY) }
    var targetDomainsInput by remember { mutableStateOf("") }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var jsonTransferInput by remember { mutableStateOf("") }

    fun loadScriptIntoEditor(script: CustomToolbarScript) {
        editingScriptId = script.id
        nameInput = script.name
        descInput = script.description
        selectedIcon = script.iconName
        jsCodeInput = script.jsCode
        autoRunMode = script.autoRunMode
        targetDomainsInput = script.targetDomains.joinToString(", ")
        testOutput = null
        selectedTabIndex = 1
    }

    fun resetEditorForNew() {
        editingScriptId = null
        nameInput = ""
        descInput = ""
        selectedIcon = "Code"
        jsCodeInput = "// Tulis kode JavaScript Anda di sini\n(function() {\n    alert('Halo dari Toolbar ' + document.title);\n    return 'Berhasil dijalankan';\n})();"
        autoRunMode = JsAutoRunMode.MANUAL_ONLY
        targetDomainsInput = try {
            Uri.parse(activeUrl).host?.removePrefix("www.") ?: ""
        } catch (_: Exception) { "" }
        testOutput = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Studio Pintasan & Custom JS Toolbar",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Buat tombol fitur sendiri dengan JavaScript & atur pintasan Chrome",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup")
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = {
                                resetEditorForNew()
                                selectedTabIndex = 1
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Buat JS Baru", fontSize = 12.sp)
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
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("Toolbar JS (${scripts.size})") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = {
                            if (jsCodeInput.isBlank()) resetEditorForNew()
                            selectedTabIndex = 1
                        },
                        text = { Text(if (editingScriptId == null) "Buat Toolbar JS" else "Edit Toolbar JS") }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("Template & Pintasan") }
                    )
                    Tab(
                        selected = selectedTabIndex == 3,
                        onClick = {
                            jsonTransferInput = viewModel.exportCustomJsScriptsToJson()
                            selectedTabIndex = 3
                        },
                        text = { Text("Impor / Ekspor JSON") }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                when (selectedTabIndex) {
                    0 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Tampilkan Baris Pintasan & JS di Layar",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                            Text(
                                                text = "Posisi saat ini: ${if (shortcutBarPos == ShortcutBarPosition.TOP) "Di Atas (Bawah Omnibox)" else "Di Bawah Layar"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { viewModel.toggleShortcutBarPosition() }) {
                                                Text(if (shortcutBarPos == ShortcutBarPosition.TOP) "Pindah Bawah" else "Pindah Atas")
                                            }
                                            Switch(
                                                checked = shortcutBarVisible,
                                                onCheckedChange = { viewModel.setShortcutBarVisible(it) }
                                            )
                                        }
                                    }
                                }
                            }

                            items(scripts, key = { it.id }) { item ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(
                                        1.dp,
                                        if (item.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = resolveToolbarIcon(item.iconName),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = item.name,
                                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                                                    )
                                                    if (item.autoRunMode != JsAutoRunMode.MANUAL_ONLY) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(50),
                                                            color = MaterialTheme.colorScheme.secondaryContainer
                                                        ) {
                                                            Text(
                                                                text = if (item.autoRunMode == JsAutoRunMode.ALL_SITES) "Auto Semua Situs" else "Auto (${item.targetDomains.size} domain)",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (item.description.isNotBlank()) {
                                                    Text(
                                                        text = item.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = item.isEnabled,
                                                onCheckedChange = { viewModel.toggleCustomJsScriptEnabled(item.id) }
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilledTonalButton(
                                                onClick = {
                                                    CustomJsToolbarManager.executeScriptInWebView(activeWebView, item) { res ->
                                                        Toast.makeText(context, "${item.name}: $res", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Jalankan Sekarang", fontSize = 12.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { loadScriptIntoEditor(item) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Edit Kode", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.weight(1f))
                                            IconButton(
                                                onClick = {
                                                    viewModel.deleteCustomJsScript(item.id)
                                                    Toast.makeText(context, "Toolbar '${item.name}' dihapus", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = "Hapus",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Create / Edit Custom JS Toolbar Form
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Nama Tombol Toolbar (misal: Video 2x, AutoKlik)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = descInput,
                                onValueChange = { descInput = it },
                                label = { Text("Deskripsi Singkat (Opsional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "Pilih Ikon Tombol Toolbar:",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CustomJsToolbarManager.AVAILABLE_ICONS.forEach { iconKey ->
                                    val selected = selectedIcon == iconKey
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedIcon = iconKey },
                                        label = { Text(iconKey, fontSize = 11.sp) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = resolveToolbarIcon(iconKey),
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }

                            Text(
                                text = "Mode Eksekusi Script:",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = autoRunMode == JsAutoRunMode.MANUAL_ONLY,
                                    onClick = { autoRunMode = JsAutoRunMode.MANUAL_ONLY },
                                    label = { Text("Klik Manual di Toolbar") }
                                )
                                FilterChip(
                                    selected = autoRunMode == JsAutoRunMode.ALL_SITES,
                                    onClick = { autoRunMode = JsAutoRunMode.ALL_SITES },
                                    label = { Text("Auto-Run di Semua Situs") }
                                )
                                FilterChip(
                                    selected = autoRunMode == JsAutoRunMode.SPECIFIC_DOMAINS,
                                    onClick = { autoRunMode = JsAutoRunMode.SPECIFIC_DOMAINS },
                                    label = { Text("Auto-Run per Domain") }
                                )
                            }

                            if (autoRunMode == JsAutoRunMode.SPECIFIC_DOMAINS) {
                                OutlinedTextField(
                                    value = targetDomainsInput,
                                    onValueChange = { targetDomainsInput = it },
                                    label = { Text("Daftar Domain (pisahkan koma, misal: youtube.com, kompas.com)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Kode JavaScript:",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AssistChip(
                                        onClick = {
                                            jsCodeInput += "\ndocument.querySelectorAll('selector').forEach(el => el.remove());"
                                        },
                                        label = { Text("+ Hapus Elemen", fontSize = 11.sp) }
                                    )
                                    AssistChip(
                                        onClick = {
                                            jsCodeInput += "\nvar s = document.createElement('style'); s.textContent = 'body { }'; document.head.appendChild(s);"
                                        },
                                        label = { Text("+ Suntik CSS", fontSize = 11.sp) }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = jsCodeInput,
                                onValueChange = { jsCodeInput = it },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 8,
                                maxLines = 16,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (testOutput != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = "Hasil Uji Script: $testOutput",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        CustomJsToolbarManager.executeRawJsInWebView(
                                            webView = activeWebView,
                                            rawJsCode = jsCodeInput,
                                            scriptLabel = nameInput.ifBlank { "TestJS" }
                                        ) { res ->
                                            testOutput = res
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Uji di Tab Aktif")
                                }

                                Button(
                                    onClick = {
                                        if (nameInput.isBlank() || jsCodeInput.isBlank()) {
                                            Toast.makeText(context, "Nama tombol dan kode JavaScript wajib diisi", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        val domains = targetDomainsInput.split(",")
                                            .map { it.trim().lowercase().removePrefix("www.") }
                                            .filter { it.isNotEmpty() }
                                            .toSet()

                                        val newScript = CustomToolbarScript(
                                            id = editingScriptId ?: UUID.randomUUID().toString(),
                                            name = nameInput.trim(),
                                            iconName = selectedIcon,
                                            description = descInput.trim(),
                                            jsCode = jsCodeInput.trim(),
                                            autoRunMode = autoRunMode,
                                            targetDomains = domains,
                                            isEnabled = true
                                        )
                                        viewModel.saveCustomJsScript(newScript)
                                        Toast.makeText(context, "Toolbar '${newScript.name}' berhasil disimpan!", Toast.LENGTH_SHORT).show()
                                        selectedTabIndex = 0
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Simpan ke Toolbar")
                                }
                            }
                        }
                    }

                    2 -> {
                        // Built-in Shortcuts & Template Restoration
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {


                            item {
                                Text(
                                    text = "Pintasan Bawaan di Baris Toolbar",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }

                            items(BuiltInShortcut.entries) { shortcut ->
                                val isChecked = enabledShortcuts.contains(shortcut.id)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleBuiltInShortcut(shortcut.id) }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = shortcut.label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                        )
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { viewModel.toggleBuiltInShortcut(shortcut.id) }
                                        )
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Galeri Template Script JavaScript Siap Pakai",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    TextButton(onClick = {
                                        viewModel.restoreDefaultJsTemplates()
                                        Toast.makeText(context, "Semua template bawaan telah dipulihkan ke daftar Anda", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Text("Pulihkan Semua")
                                    }
                                }
                            }

                            items(CustomJsToolbarManager.TEMPLATE_GALLERY, key = { it.id }) { tpl ->
                                Surface(
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
                                            imageVector = resolveToolbarIcon(tpl.iconName),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tpl.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = tpl.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.saveCustomJsScript(tpl.copy(isEnabled = true))
                                                Toast.makeText(context, "Template '${tpl.name}' ditambahkan ke Toolbar!", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Pakai", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // Import & Export JSON
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Cadangkan atau bagikan seluruh daftar tombol JavaScript Anda dalam format JSON, atau tempel JSON dari perangkat lain.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        val exported = viewModel.exportCustomJsScriptsToJson()
                                        jsonTransferInput = exported
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("CustomJS_JSON", exported))
                                        Toast.makeText(context, "JSON berhasil disalin ke Clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Salin JSON")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val exported = viewModel.exportCustomJsScriptsToJson()
                                        try {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_SUBJECT, "Chrome Custom JS Toolbars")
                                                putExtra(Intent.EXTRA_TEXT, exported)
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Ekspor Script JS"))
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Bagikan JSON")
                                }
                            }

                            OutlinedTextField(
                                value = jsonTransferInput,
                                onValueChange = { jsonTransferInput = it },
                                label = { Text("Data JSON Script Toolbar") },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 10,
                                maxLines = 18,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    val count = viewModel.importCustomJsScriptsFromJson(jsonTransferInput, mergeWithExisting = true)
                                    if (count > 0) {
                                        Toast.makeText(context, "Berhasil mengimpor $count script Toolbar JS!", Toast.LENGTH_SHORT).show()
                                        selectedTabIndex = 0
                                    } else {
                                        Toast.makeText(context, "Format JSON tidak valid atau kosong", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Impor & Gabungkan Script dari JSON")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Authentic Google Chrome Safe Browsing / HTTPS-Only Security Interstitial Screen.
 */
@Composable
fun ChromeSecurityThreatInterstitial(
    threat: SecurityThreatAssessment,
    onBackToSafety: () -> Unit,
    onProceedAnyway: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdvancedDetails by remember { mutableStateOf(false) }
    val isHttpWarning = threat.severity == ThreatSeverity.HTTP_INSECURE
    val bannerBg = if (isHttpWarning) MaterialTheme.colorScheme.surface else Color(0xFF8C1D18)
    val contentColor = if (isHttpWarning) MaterialTheme.colorScheme.onSurface else Color.White

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bannerBg)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Icon(
            imageVector = if (isHttpWarning) Icons.Outlined.LockOpen else Icons.Outlined.GppBad,
            contentDescription = "Security Warning",
            tint = if (isHttpWarning) MaterialTheme.colorScheme.error else Color(0xFFFFB4AB),
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = threat.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = contentColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = threat.reason,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor.copy(alpha = 0.9f)
        )

        if (threat.recommendation.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = threat.recommendation,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.78f)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showAdvancedDetails = !showAdvancedDetails },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.5f))
            ) {
                Text(if (showAdvancedDetails) "Sembunyikan Detail" else "Lanjutan")
            }

            Button(
                onClick = onBackToSafety,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isHttpWarning) MaterialTheme.colorScheme.primary else Color.White,
                    contentColor = if (isHttpWarning) MaterialTheme.colorScheme.onPrimary else Color(0xFF8C1D18)
                )
            ) {
                Text("Kembali ke Halaman Aman", fontWeight = FontWeight.SemiBold)
            }
        }

        if (showAdvancedDetails) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.2f)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "URL Target: ${threat.url}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = contentColor.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = onProceedAnyway,
                        colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
                    ) {
                        Text(
                            text = "Tetap lanjutkan ke ${threat.host} (Tidak Disarankan)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

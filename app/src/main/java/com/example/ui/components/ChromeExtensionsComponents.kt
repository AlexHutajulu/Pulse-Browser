package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
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
import com.example.R
import com.example.manager.*
import com.example.ui.viewmodel.BrowserViewModel
import java.util.UUID

fun resolveExtensionIcon(iconName: String): ImageVector {
    return when (iconName) {
        "Fullscreen" -> Icons.Outlined.Fullscreen
        "DarkMode" -> Icons.Outlined.DarkMode
        "FastForward" -> Icons.Outlined.FastForward
        "LockOpen" -> Icons.Outlined.LockOpen
        "Speed" -> Icons.Outlined.Speed
        "Cookie" -> Icons.Outlined.Cookie
        "VolumeUp" -> Icons.AutoMirrored.Outlined.VolumeUp
        "Collections" -> Icons.Outlined.Collections
        "MenuBook" -> Icons.AutoMirrored.Outlined.MenuBook
        "Palette" -> Icons.Outlined.Palette
        "Terminal" -> Icons.Outlined.Terminal
        "Security" -> Icons.Outlined.Security
        "AutoAwesome" -> Icons.Outlined.AutoAwesome
        else -> Icons.Outlined.Extension
    }
}

private fun queryDisplayNameFromUri(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIdx)
            } else null
        } ?: (uri.lastPathSegment ?: "custom_extension.js")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "custom_extension.js"
    }
}

@Composable
fun ChromeExtensionsHubDialog(
    viewModel: BrowserViewModel,
    activeWebView: WebView?,
    activeUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val extensions by viewModel.extensions.collectAsState()

    // 0 = Terpasang, 1 = + Tambah dari Luar (File / URL / Manifest), 2 = Buat / Edit Kode
    var selectedTab by remember { mutableIntStateOf(0) }

    // Editor state
    var editingExtensionId by remember { mutableStateOf<String?>(null) }
    var extName by remember { mutableStateOf("") }
    var extVersion by remember { mutableStateOf("1.0.0") }
    var extDesc by remember { mutableStateOf("") }
    var extIcon by remember { mutableStateOf("Extension") }
    var extJsCode by remember { mutableStateOf("") }
    var extCssCode by remember { mutableStateOf("") }
    var extMatchesText by remember { mutableStateOf("*") }
    var extRunAt by remember { mutableStateOf(ExtensionRunAt.DOCUMENT_END) }

    // External URL & Raw Paste states
    var externalUrlInput by remember { mutableStateOf("") }
    var isDownloadingUrl by remember { mutableStateOf(false) }
    var rawPasteInput by remember { mutableStateOf("") }
    var rawPasteFileName by remember { mutableStateOf("custom_script.user.js") }

    // Native Android Document Picker for importing .js, .user.js, .css, .json, .zip from outside
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryDisplayNameFromUri(context, uri)
            viewModel.importExtensionsFromUri(uri, fileName) { count ->
                if (count > 0) {
                    Toast.makeText(
                        context,
                        "Berhasil memasang $count ekstensi dari '$fileName'!",
                        Toast.LENGTH_LONG
                    ).show()
                    selectedTab = 0
                } else {
                    Toast.makeText(
                        context,
                        "File '$fileName' tidak berisi format ekstensi (.js/.user.js/.css/.json/.zip) yang valid",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun startCreateNewExtension() {
        editingExtensionId = null
        extName = ""
        extVersion = "1.0.0"
        extDesc = ""
        extIcon = "Extension"
        extJsCode = "(function() {\n  // Tulis kode JavaScript ekstensi Anda di sini\n  console.log('Ekstensi berjalan di ' + location.hostname);\n  return 'Ekstensi Kustom Aktif!';\n})();"
        extCssCode = ""
        extMatchesText = "*"
        extRunAt = ExtensionRunAt.DOCUMENT_END
        selectedTab = 2
    }

    fun startEditExtension(ext: BrowserExtension) {
        editingExtensionId = ext.id
        extName = ext.name
        extVersion = ext.version
        extDesc = ext.description
        extIcon = ext.iconName
        extJsCode = ext.jsCode
        extCssCode = ext.cssCode
        extMatchesText = ext.matches.joinToString(", ")
        extRunAt = ext.runAt
        selectedTab = 2
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp)
                .widthIn(max = 760.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Chrome Extensions & Userscripts",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${extensions.count { it.isEnabled }} aktif dari ${extensions.size} ekstensi • Impor .js / .user.js / .css / .zip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { selectedTab = 1 },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Impor dari Luar", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup")
                        }
                    }
                }

                // Navigation Tabs
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    divider = { HorizontalDivider(thickness = 0.5.dp) }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Terpasang (${extensions.size})") },
                        icon = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("+ Tambah Ekstensi dari Luar") },
                        icon = { Icon(Icons.Outlined.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = {
                            if (extName.isBlank() && editingExtensionId == null) {
                                startCreateNewExtension()
                            } else {
                                selectedTab = 2
                            }
                        },
                        text = { Text(if (editingExtensionId != null) "Edit Ekstensi" else "Buat Kode Ekstensi") },
                        icon = { Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                // Content Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Punya file ekstensi atau script dari luar?",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                                Text(
                                                    text = "Impor file .js, .user.js (Tampermonkey), .css, .json, atau paket .zip WebExtension.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                                modifier = Modifier.testTag("btn_pick_external_extension_file")
                                            ) {
                                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Pilih File")
                                            }
                                        }
                                    }
                                }

                                items(extensions, key = { it.id }) { ext ->
                                    val isCustom = ext.sourceType != ExtensionSourceType.BUILT_IN
                                    val sourceBadgeText = when (ext.sourceType) {
                                        ExtensionSourceType.BUILT_IN -> "Bawaan"
                                        ExtensionSourceType.EXTERNAL_FILE -> "File Eksternal"
                                        ExtensionSourceType.EXTERNAL_URL -> "URL Eksternal"
                                        ExtensionSourceType.CUSTOM_CODE -> "Kustom"
                                    }
                                    val runAtLabel = when (ext.runAt) {
                                        ExtensionRunAt.DOCUMENT_START -> "Auto (Start)"
                                        ExtensionRunAt.DOCUMENT_END -> "Auto (Selesai)"
                                        ExtensionRunAt.MANUAL_ONLY -> "Klik Manual"
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(
                                            1.dp,
                                            if (ext.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (ext.isEnabled) MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    modifier = Modifier.size(42.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = resolveExtensionIcon(ext.iconName),
                                                            contentDescription = null,
                                                            tint = if (ext.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = ext.name,
                                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = MaterialTheme.colorScheme.secondaryContainer
                                                        ) {
                                                            Text(
                                                                text = "v${ext.version}",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    val scopeLabel = if (ext.matches.contains("*")) {
                                                        "Semua Situs (*)"
                                                    } else {
                                                        ext.matches.joinToString(", ")
                                                    }
                                                    val privateLabel = if (ext.allowInPrivate) {
                                                        context.getString(R.string.extension_tab_private_active)
                                                    } else {
                                                        context.getString(R.string.extension_tab_private_inactive)
                                                    }
                                                    Text(
                                                        text = "$sourceBadgeText • $runAtLabel • Target: $scopeLabel • $privateLabel",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Switch(
                                                    checked = ext.isEnabled,
                                                    onCheckedChange = {
                                                        viewModel.toggleExtensionEnabled(ext.id)
                                                    }
                                                )
                                            }

                                            if (ext.description.isNotBlank()) {
                                                Text(
                                                    text = ext.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            BrowserExtensionManager.executeExtensionInWebView(
                                                                webView = activeWebView,
                                                                extension = ext,
                                                                isManualTrigger = true
                                                            ) { msg ->
                                                                Toast.makeText(context, "${ext.name}: $msg", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(32.dp)
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Jalankan di Situs Ini", fontSize = 11.sp)
                                                    }

                                                    OutlinedButton(
                                                        onClick = {
                                                            viewModel.toggleExtensionPinned(ext.id)
                                                            val msg = if (!ext.isPinnedToToolbar) {
                                                                context.getString(R.string.extension_pinned)
                                                            } else {
                                                                context.getString(R.string.extension_unpinned)
                                                            }
                                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (ext.isPinnedToToolbar) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = if (ext.isPinnedToToolbar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(if (ext.isPinnedToToolbar) "Tersemat" else "Sematkan", fontSize = 11.sp)
                                                    }
                                                }

                                                Row {
                                                    IconButton(
                                                        onClick = { startEditExtension(ext) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Outlined.Edit, contentDescription = "Edit Kode", modifier = Modifier.size(18.dp))
                                                    }
                                                    if (isCustom) {
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.deleteExtension(ext.id)
                                                                Toast.makeText(
                                                                    context,
                                                                    context.getString(R.string.extension_deleted, ext.name),
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Outlined.Delete,
                                                                contentDescription = "Hapus",
                                                                tint = MaterialTheme.colorScheme.error,
                                                                modifier = Modifier.size(18.dp)
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

                        1 -> {
                            // Tab 1: Add Custom Extension from Outside (File Picker, URL, or Raw Script/Manifest)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Method 1: Local File Picker (.js, .user.js, .css, .json, .zip)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "1. Impor File Ekstensi dari Penyimpanan Luar",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = "Mendukung paket WebExtension (.zip dengan manifest.json), Userscript (.user.js / .js), UserStyle (.css), dan paket .json",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Pilih File Ekstensi (.zip / .user.js / .js / .css / .json)")
                                        }
                                    }
                                }

                                // Method 2: Install from External URL (GreasyFork, GitHub Raw, Direct Link)
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.Link,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "2. Pasang dari URL / Link Eksternal",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = "Masukkan tautan langsung ke file .user.js (GreasyFork / GitHub Raw), .js, .css, atau .json",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        OutlinedTextField(
                                            value = externalUrlInput,
                                            onValueChange = { externalUrlInput = it },
                                            label = { Text("URL Ekstensi (contoh: https://.../script.user.js)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Button(
                                            onClick = {
                                                if (externalUrlInput.isBlank()) {
                                                    Toast.makeText(context, "Masukkan URL ekstensi terlebih dahulu", Toast.LENGTH_SHORT).show()
                                                    return@Button
                                                }
                                                isDownloadingUrl = true
                                                viewModel.importExtensionsFromUrl(externalUrlInput) { res ->
                                                    isDownloadingUrl = false
                                                    res.onSuccess { count ->
                                                        Toast.makeText(context, "Berhasil mengunduh & memasang $count ekstensi!", Toast.LENGTH_LONG).show()
                                                        externalUrlInput = ""
                                                        selectedTab = 0
                                                    }.onFailure { err ->
                                                        Toast.makeText(context, "Gagal mengunduh: ${err.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            },
                                            enabled = !isDownloadingUrl,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isDownloadingUrl) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Mengunduh & Memasang...")
                                            } else {
                                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Unduh & Pasang dari URL")
                                            }
                                        }
                                    }
                                }

                                // Method 3: Paste Userscript / Manifest / JSON directly + Backup Export
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "3. Tempel Kode Userscript / Manifest JSON",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                                )
                                                Text(
                                                    text = "Otomatis mengenali header // ==UserScript==, manifest.json, atau cadangan JSON",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val json = viewModel.exportExtensionsToJson(onlyCustom = false)
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Chrome_Extensions_Backup", json))
                                                    Toast.makeText(context, "Seluruh ekstensi disalin ke Clipboard (JSON)", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Ekspor JSON", fontSize = 12.sp)
                                            }
                                        }

                                        OutlinedTextField(
                                            value = rawPasteFileName,
                                            onValueChange = { rawPasteFileName = it },
                                            label = { Text("Nama File / Jenis (.user.js / .css / .json)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = rawPasteInput,
                                            onValueChange = { rawPasteInput = it },
                                            label = { Text("Tempel kode // ==UserScript==, CSS, atau JSON di sini") },
                                            minLines = 5,
                                            maxLines = 10,
                                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Button(
                                            onClick = {
                                                val count = viewModel.importExtensionsFromRawText(rawPasteInput, rawPasteFileName)
                                                if (count > 0) {
                                                    Toast.makeText(context, "Berhasil memasang $count ekstensi!", Toast.LENGTH_SHORT).show()
                                                    rawPasteInput = ""
                                                    selectedTab = 0
                                                } else {
                                                    Toast.makeText(context, "Kode tidak valid atau kosong", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Pasang Ekstensi dari Kode yang Ditempel")
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            // Tab 2: Full Custom Extension Code Builder (JS + CSS + Matches + RunAt)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = extName,
                                        onValueChange = { extName = it },
                                        label = { Text("Nama Ekstensi") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = extVersion,
                                        onValueChange = { extVersion = it },
                                        label = { Text("Versi") },
                                        singleLine = true,
                                        modifier = Modifier.width(100.dp)
                                    )
                                }

                                OutlinedTextField(
                                    value = extDesc,
                                    onValueChange = { extDesc = it },
                                    label = { Text("Deskripsi Singkat") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = extMatchesText,
                                    onValueChange = { extMatchesText = it },
                                    label = { Text("Target Domain (* untuk semua situs, atau pisahkan koma: youtube.com, google.com)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Waktu Eksekusi (Run-At):",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = extRunAt == ExtensionRunAt.DOCUMENT_START,
                                        onClick = { extRunAt = ExtensionRunAt.DOCUMENT_START },
                                        label = { Text("Otomatis Awal (document_start)") }
                                    )
                                    FilterChip(
                                        selected = extRunAt == ExtensionRunAt.DOCUMENT_END,
                                        onClick = { extRunAt = ExtensionRunAt.DOCUMENT_END },
                                        label = { Text("Otomatis Selesai (document_end)") }
                                    )
                                    FilterChip(
                                        selected = extRunAt == ExtensionRunAt.MANUAL_ONLY,
                                        onClick = { extRunAt = ExtensionRunAt.MANUAL_ONLY },
                                        label = { Text("Manual saat Diklik") }
                                    )
                                }

                                OutlinedTextField(
                                    value = extJsCode,
                                    onValueChange = { extJsCode = it },
                                    label = { Text("Kode JavaScript (content.js)") },
                                    minLines = 5,
                                    maxLines = 10,
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = extCssCode,
                                    onValueChange = { extCssCode = it },
                                    label = { Text("Kode CSS Kustom (style.css - Opsional)") },
                                    minLines = 3,
                                    maxLines = 7,
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val tempExt = BrowserExtension(
                                                name = extName.ifBlank { "Test Extension" },
                                                jsCode = extJsCode,
                                                cssCode = extCssCode
                                            )
                                            BrowserExtensionManager.executeExtensionInWebView(
                                                webView = activeWebView,
                                                extension = tempExt,
                                                isManualTrigger = true
                                            ) { msg ->
                                                Toast.makeText(context, "Hasil Uji: $msg", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Uji di Halaman")
                                    }

                                    Button(
                                        onClick = {
                                            if (extName.isBlank() || (extJsCode.isBlank() && extCssCode.isBlank())) {
                                                Toast.makeText(context, "Isi nama ekstensi serta minimal kode JS atau CSS", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            val matchesList = extMatchesText.split(",")
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .ifEmpty { listOf("*") }

                                            val saved = BrowserExtension(
                                                id = editingExtensionId ?: ("ext_custom_" + UUID.randomUUID().toString().take(8)),
                                                name = extName.trim(),
                                                version = extVersion.trim().ifBlank { "1.0.0" },
                                                description = extDesc.trim().ifBlank { "Ekstensi kustom buatan pengguna" },
                                                author = "User",
                                                iconName = extIcon,
                                                jsCode = extJsCode.trim(),
                                                cssCode = extCssCode.trim(),
                                                matches = matchesList,
                                                runAt = extRunAt,
                                                isEnabled = true,
                                                isPinnedToToolbar = true,
                                                sourceType = ExtensionSourceType.CUSTOM_CODE
                                            )
                                            viewModel.saveOrUpdateExtension(saved)
                                            Toast.makeText(context, "Ekstensi '${saved.name}' berhasil disimpan!", Toast.LENGTH_SHORT).show()
                                            selectedTab = 0
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Simpan Ekstensi")
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

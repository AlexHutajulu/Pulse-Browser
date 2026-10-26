package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.DownloadItem
import com.example.manager.ActiveDownload
import com.example.ui.viewmodel.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private enum class DownloadFilterCategory(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEO("Video"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    APPS("Apps & Archives")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit = { viewModel.setDownloadsVisible(false) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val isIncognito = tabs.find { it.id == activeTabId }?.isPrivate == true

    val allDownloads by viewModel.downloadRepository.allDownloads.collectAsState(initial = emptyList())
    val nonIncognitoDownloads by viewModel.downloadRepository.nonIncognitoDownloads.collectAsState(initial = emptyList())
    val activeDownloadsMap by viewModel.downloadManager.activeDownloads.collectAsState()

    val sourceDownloads = if (isIncognito) allDownloads else nonIncognitoDownloads

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(DownloadFilterCategory.ALL) }

    val filteredDownloads = remember(sourceDownloads, searchQuery, selectedCategory) {
        sourceDownloads.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                item.fileName.contains(searchQuery.trim(), ignoreCase = true) ||
                item.domain.contains(searchQuery.trim(), ignoreCase = true)

            val mime = item.mimeType.lowercase()
            val name = item.fileName.lowercase()
            val matchesCategory = when (selectedCategory) {
                DownloadFilterCategory.ALL -> true
                DownloadFilterCategory.IMAGES -> mime.startsWith("image/") ||
                    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".gif")
                DownloadFilterCategory.VIDEO -> mime.startsWith("video/") ||
                    name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm")
                DownloadFilterCategory.AUDIO -> mime.startsWith("audio/") ||
                    name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".flac")
                DownloadFilterCategory.DOCUMENTS -> mime.contains("pdf") || mime.contains("document") || mime.contains("text") ||
                    name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".txt")
                DownloadFilterCategory.APPS -> name.endsWith(".apk") || name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z")
            }

            matchesQuery && matchesCategory
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            // Chrome Downloads Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Downloads",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${sourceDownloads.size} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sourceDownloads.any { it.status == "COMPLETED" || it.status == "FAILED" || it.status == "CANCELLED" }) {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    viewModel.downloadRepository.clearCompleted()
                                }
                            }
                        ) {
                            Text("Clear Done", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chrome Downloads Search Pill
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search downloads",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Chrome Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DownloadFilterCategory.values().forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredDownloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isBlank() && selectedCategory == DownloadFilterCategory.ALL)
                                "Files you download appear here"
                            else
                                "No matching downloads",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredDownloads, key = { it.id }) { item ->
                        val activeState = activeDownloadsMap[item.id]
                        ChromeDownloadItemCard(
                            item = item,
                            activeState = activeState,
                            onPause = { viewModel.downloadManager.pauseDownload(item.id) },
                            onResume = { viewModel.downloadManager.resumeDownload(item.id) },
                            onCancel = { viewModel.downloadManager.cancelDownload(item.id) },
                            onOpen = {
                                openDownloadedFile(context, item)
                            },
                            onRemove = {
                                viewModel.downloadManager.cancelDownload(item.id)
                                viewModel.downloadManager.removeActiveDownloadEntry(item.id)
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        File(item.fileUri).delete()
                                    } catch (_: Exception) {}
                                    viewModel.downloadRepository.deleteById(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeDownloadItemCard(
    item: DownloadItem,
    activeState: ActiveDownload?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val status = activeState?.status ?: item.status
    val downloadedBytes = activeState?.downloadedBytes ?: item.downloadedBytes
    val totalBytes = activeState?.totalBytes ?: item.totalBytes
    val speedBps = activeState?.speedBps ?: 0L

    val progress = if (totalBytes > 0L) {
        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (status) {
                            "COMPLETED" -> Icons.Default.CheckCircle
                            "FAILED", "CANCELLED" -> Icons.Default.ErrorOutline
                            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = when (status) {
                            "COMPLETED" -> MaterialTheme.colorScheme.tertiary
                            "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val sizeText = if (totalBytes > 0) {
                        "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                    } else {
                        formatBytes(downloadedBytes)
                    }
                    val statusLabel = when (status) {
                        "DOWNLOADING" -> "$sizeText • ${formatBytes(speedBps)}/s"
                        "PAUSED" -> "$sizeText • Paused"
                        "QUEUED" -> "Starting download..."
                        "COMPLETED" -> "${formatBytes(downloadedBytes)} • ${item.domain.ifBlank { "Complete" }}"
                        "FAILED" -> "Failed${if (item.failureReason.isNotBlank()) ": ${item.failureReason}" else ""}"
                        "CANCELLED" -> "Canceled"
                        else -> status
                    }
                    Text(
                        text = statusLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (status) {
                        "DOWNLOADING", "QUEUED" -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                            }
                        }
                        "PAUSED" -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Resume",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                            }
                        }
                        "COMPLETED" -> {
                            FilledTonalButton(
                                onClick = onOpen,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Open", fontSize = 12.sp)
                            }
                            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                            }
                        }
                        else -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            if (status == "DOWNLOADING" || status == "PAUSED" || status == "QUEUED") {
                Spacer(modifier = Modifier.height(10.dp))
                if (totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

private fun openDownloadedFile(context: android.content.Context, item: DownloadItem) {
    try {
        val file = File(item.fileUri).canonicalFile
        if (!file.exists() || !file.isFile) {
            Toast.makeText(context, "File no longer exists on device", Toast.LENGTH_SHORT).show()
            return
        }
        val allowedRoots = listOfNotNull(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.canonicalFile,
            File(context.filesDir, "downloads").canonicalFile
        )
        val isInsideAllowedRoot = allowedRoots.any { root ->
            file.path.startsWith(root.path + File.separator)
        }
        if (!isInsideAllowedRoot) {
            Toast.makeText(context, "Blocked opening file outside secure download directory", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = item.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            component = null
            selector = null
            clipData = null
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No application found to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

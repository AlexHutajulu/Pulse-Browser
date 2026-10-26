package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.ui.viewmodel.BrowserViewModel

private data class ChromeShortcutItem(
    val title: String,
    val url: String,
    val letter: String,
    val badgeColor: Color
)

private val defaultChromeShortcuts = listOf(
    ChromeShortcutItem("Google", "https://www.google.com", "G", GoogleBlue),
    ChromeShortcutItem("YouTube", "https://m.youtube.com", "Y", GoogleRed),
    ChromeShortcutItem("Wikipedia", "https://www.wikipedia.org", "W", Color(0xFF5F6368)),
    ChromeShortcutItem("GitHub", "https://github.com", "GH", Color(0xFF24292F)),
    ChromeShortcutItem("Reddit", "https://www.reddit.com", "R", Color(0xFFFF4500)),
    ChromeShortcutItem("DuckDuckGo", "https://duckduckgo.com", "D", Color(0xFFDE5833)),
    ChromeShortcutItem("Google News", "https://news.google.com", "N", GoogleGreen),
    ChromeShortcutItem("Translate", "https://translate.google.com", "T", Color(0xFF1A73E8))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: BrowserViewModel,
    onNavigate: (String) -> Unit,
    onSearchSubmit: (String) -> Unit
) {
    val context = LocalContext.current
    var searchInput by remember { mutableStateOf("") }

    val activeTabId by viewModel.activeTabId.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTab = tabs.find { it.id == activeTabId }
    val isIncognito = activeTab?.isPrivate == true

    val selectedEngine by viewModel.selectedSearchEngine.collectAsState()
    val adBlockEnabled by viewModel.adBlockEnabled.collectAsState()
    val adBlockMode by viewModel.adBlockMode.collectAsState()
    val trackerBlockEnabled by viewModel.trackerBlockEnabled.collectAsState()
    val trackersBlockedCount by viewModel.trackerCookiesBlockedCount.collectAsState()
    val bookmarks by viewModel.bookmarksState.collectAsState()
    val recentHistory by viewModel.historyState.collectAsState()

    if (isIncognito) {
        ChromeIncognitoNewTabPage(
            searchInput = searchInput,
            onSearchInputChange = { searchInput = it },
            onSearchSubmit = onSearchSubmit,
            onExitIncognito = {
                val normalTab = tabs.firstOrNull { !it.isPrivate }
                if (normalTab != null) {
                    viewModel.selectTab(normalTab.id)
                } else {
                    viewModel.addNewTab(isPrivate = false)
                }
            }
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        val isWide = maxWidth >= 600.dp
        val contentMaxWidth = if (isWide) 660.dp else maxWidth

        Column(
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isWide) 32.dp else 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Chrome NTP Top Header Strip (Search Engine Pill, Mode Toggle & Incognito Quick Switch)
            val currentDisplayMode by viewModel.displayMode.collectAsState()
            val extensionsList by viewModel.extensions.collectAsState()
            val enabledExtCount = remember(extensionsList) { extensionsList.count { it.isEnabled } }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                var showSearchEngineMenu by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .clickable { showSearchEngineMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "Ganti Mesin Pencari",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = selectedEngine,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showSearchEngineMenu,
                        onDismissRequest = { showSearchEngineMenu = false },
                        modifier = Modifier.widthIn(min = 230.dp)
                    ) {
                        com.example.manager.SearchEngine.getAllEngines().forEach { engine ->
                            val isSelected = selectedEngine.equals(engine.engineName, ignoreCase = true)
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
                                            contentDescription = "Dipilih",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setSearchEngine(engine.engineName)
                                    showSearchEngineMenu = false
                                    Toast.makeText(
                                        context,
                                        "${engine.engineName} disetel sebagai mesin pencari",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick Mobile / Desktop UI Mode Toggle
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .clickable {
                                val next = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                                    com.example.manager.DisplayMode.MOBILE
                                else com.example.manager.DisplayMode.DESKTOP
                                viewModel.setDisplayMode(next)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                                    Icons.Outlined.DesktopWindows else Icons.Outlined.PhoneAndroid,
                                contentDescription = "Toggle Display Mode",
                                tint = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                                    MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP) "Desktop" else "Mobile",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = if (currentDisplayMode == com.example.manager.DisplayMode.DESKTOP)
                                    MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .clickable { viewModel.addNewTab(isPrivate = true) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.VisibilityOff,
                                contentDescription = "New Incognito Tab",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Incognito",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isWide) 24.dp else 18.dp))

            // Alex Browser Singularity Brand Hero Logo
            Image(
                painter = painterResource(id = R.drawable.ic_alex_singularity),
                contentDescription = "Alex Browser Singularity",
                modifier = Modifier
                    .size(if (isWide) 84.dp else 72.dp)
                    .clip(CircleShape)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = Color(0xFF428BFF).copy(alpha = 0.3f),
                        spotColor = Color(0xFF7046FF).copy(alpha = 0.4f)
                    )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Adaptive Search Engine Wordmark Header
            val engineHeader = remember(selectedEngine) {
                when {
                    selectedEngine.equals("Google", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = GoogleBlue)) { append("G") }
                        withStyle(SpanStyle(color = GoogleRed)) { append("o") }
                        withStyle(SpanStyle(color = GoogleYellow)) { append("o") }
                        withStyle(SpanStyle(color = GoogleBlue)) { append("g") }
                        withStyle(SpanStyle(color = GoogleGreen)) { append("l") }
                        withStyle(SpanStyle(color = GoogleRed)) { append("e") }
                    }
                    selectedEngine.equals("DuckDuckGo", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFDE5833))) { append("DuckDuckGo") }
                    }
                    selectedEngine.equals("Bing", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF00838F))) { append("Bing") }
                    }
                    selectedEngine.equals("Brave Search", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFFB542B))) { append("Brave Search") }
                    }
                    selectedEngine.equals("Yahoo", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF6001D2))) { append("Yahoo!") }
                    }
                    selectedEngine.equals("Ecosia", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF00875A))) { append("Ecosia") }
                    }
                    selectedEngine.equals("Yandex", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFE60000))) { append("Yandex") }
                    }
                    selectedEngine.equals("Startpage", ignoreCase = true) -> buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF2252EC))) { append("Startpage") }
                    }
                    else -> buildAnnotatedString {
                        append(selectedEngine)
                    }
                }
            }

            Text(
                text = engineHeader,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = if (isWide) 52.sp else 42.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Authentic Chrome Fakebox / Omnibox Search Pill
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Google "G" / Search badge on the left of Fakebox
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        TextField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    text = "Cari di $selectedEngine atau ketik URL",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (searchInput.isNotBlank()) {
                                        onSearchSubmit(searchInput.trim())
                                    }
                                }
                            )
                        )
                    }

                    if (searchInput.isNotEmpty()) {
                        IconButton(
                            onClick = { searchInput = "" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { onSearchSubmit(searchInput.trim()) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = "Go",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Chrome Most Visited Circular Shortcuts Grid (2 rows of 4)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val rows = defaultChromeShortcuts.chunked(4)
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            rowItems.forEach { item ->
                                ChromeSiteShortcutTile(
                                    title = item.title,
                                    letter = item.letter,
                                    badgeColor = item.badgeColor,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onNavigate(item.url) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Chrome Quick Action Hub Row (Extensions, Bookmarks, History, Downloads)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromeQuickActionButton(
                    icon = Icons.Outlined.Extension,
                    label = if (enabledExtCount > 0) "Ekstensi ($enabledExtCount)" else "Ekstensi",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setExtensionsHubVisible(true) }
                )
                ChromeQuickActionButton(
                    icon = Icons.Outlined.StarOutline,
                    label = "Bookmark",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setBookmarksVisible(true) }
                )
                ChromeQuickActionButton(
                    icon = Icons.Outlined.History,
                    label = "Riwayat",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setHistoryVisible(true) }
                )
                ChromeQuickActionButton(
                    icon = Icons.Outlined.FileDownload,
                    label = "Unduhan",
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setDownloadsVisible(true) }
                )
            }

            // Saved Bookmarks Section (if user has bookmarks)
            if (bookmarks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(18.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Bookmarks,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Bookmarks",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = { viewModel.setBookmarksVisible(true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("More", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        bookmarks.take(3).forEach { bmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(bmark.url) }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bmark.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = bmark.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Continue Browsing / Recent History Card (like Chrome "Jump back in")
            if (recentHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Recently visited",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = { viewModel.setHistoryVisible(true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("History", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        recentHistory.take(3).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigate(item.url) }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.url },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = item.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chrome Safety & Privacy Shield Report Card
            ChromePrivacySummaryCard(
                adBlockEnabled = adBlockEnabled,
                adBlockModeName = adBlockMode.name,
                trackerBlockEnabled = trackerBlockEnabled,
                trackersBlockedCount = trackersBlockedCount,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChromeSiteShortcutTile(
    title: String,
    letter: String,
    badgeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChromeQuickActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChromePrivacySummaryCard(
    adBlockEnabled: Boolean,
    adBlockModeName: String,
    trackerBlockEnabled: Boolean,
    trackersBlockedCount: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Chrome Safety & AdShield",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (trackersBlockedCount > 0) "$trackersBlockedCount ads & trackers blocked across sites"
                        else "Real-time ad & tracker protection active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChromePrivacyChip(
                    label = "AdShield: ${if (adBlockEnabled) adBlockModeName else "Off"}",
                    isActive = adBlockEnabled,
                    modifier = Modifier.weight(1f)
                )
                ChromePrivacyChip(
                    label = "Trackers: ${if (trackerBlockEnabled) "Blocked" else "Allowed"}",
                    isActive = trackerBlockEnabled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ChromePrivacyChip(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Outlined.Info,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChromeIncognitoNewTabPage(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    onExitIncognito: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ChromeIncognitoBg),
        contentAlignment = Alignment.TopCenter
    ) {
        val isWide = maxWidth >= 600.dp
        Column(
            modifier = Modifier
                .widthIn(max = if (isWide) 620.dp else maxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onExitIncognito) {
                    Icon(
                        imageVector = Icons.Outlined.Public,
                        contentDescription = null,
                        tint = ChromeIncognitoAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Switch to Regular Tab", color = ChromeIncognitoAccent)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ChromeIncognitoOmnibox),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = "Incognito",
                    tint = ChromeIncognitoText,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "You've gone Incognito",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal),
                color = ChromeIncognitoText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Incognito Omnibox Search Pill
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                color = ChromeIncognitoOmnibox
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = ChromeIncognitoSubtext,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TextField(
                        value = searchInput,
                        onValueChange = onSearchInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Search or type URL in Incognito",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ChromeIncognitoSubtext
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = ChromeIncognitoAccent,
                            focusedTextColor = ChromeIncognitoText,
                            unfocusedTextColor = ChromeIncognitoText
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (searchInput.isNotBlank()) {
                                    onSearchSubmit(searchInput.trim())
                                }
                            }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Private tabs do not save browsing history, persistent DOM storage (localStorage), or form data, and block third-party cookies. Note: Android WebView shares a single first-party cookie engine across tabs; session cookies are cleared automatically when all private tabs close and no regular web tabs are active.",
                style = MaterialTheme.typography.bodyMedium,
                color = ChromeIncognitoSubtext,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IncognitoInfoBlock(
                        title = "Alex Browser won't save:",
                        bullets = listOf(
                            "Your browsing history & private downloads list",
                            "Persistent DOM storage (localStorage) & third-party cookies",
                            "Information entered in forms or disk HTTP cache"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IncognitoInfoBlock(
                        title = "Activity or cookies may still be shared with:",
                        bullets = listOf(
                            "Active first-party logins in open regular tabs (Android WebView limitation)",
                            "Websites you visit & your internet service provider",
                            "Your employer or school network"
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                IncognitoInfoBlock(
                    title = "Alex Browser won't save:",
                    bullets = listOf(
                        "Your browsing history & private downloads list",
                        "Persistent DOM storage (localStorage) & third-party cookies",
                        "Information entered in forms or disk HTTP cache"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                IncognitoInfoBlock(
                    title = "Activity or cookies may still be shared with:",
                    bullets = listOf(
                        "Active first-party logins in open regular tabs (Android WebView limitation)",
                        "Websites you visit & your internet service provider",
                        "Your employer or school network"
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun IncognitoInfoBlock(
    title: String,
    bullets: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = ChromeIncognitoSurface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ChromeIncognitoText
            )
            Spacer(modifier = Modifier.height(8.dp))
            bullets.forEach { item ->
                Text(
                    text = "•  $item",
                    style = MaterialTheme.typography.bodySmall,
                    color = ChromeIncognitoSubtext,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

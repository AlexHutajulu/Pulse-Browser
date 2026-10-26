package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.example.devtools.*
import com.example.ui.viewmodel.BrowserViewModel
import org.json.JSONObject

@Composable
fun DevToolsPanel(
    viewModel: BrowserViewModel,
    tabId: String,
    devToolsState: DevToolsState,
    webView: WebView?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 12.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 0.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Re-designed Header with diagnostic title and direct commands
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Developer Tools",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                
                Row {
                    FilledTonalIconButton(
                        onClick = { viewModel.setElementPickerActive(tabId, !devToolsState.isElementPickerActive) },
                        modifier = Modifier.size(32.dp).testTag("picker_toggle_header"),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (devToolsState.isElementPickerActive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Pick Element",
                            modifier = Modifier.size(16.dp),
                            tint = if (devToolsState.isElementPickerActive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp).testTag("devtools_panel_close")) {
                        Icon(Icons.Default.Close, contentDescription = "Close Inspector", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Scrollable Tab navigation
            ScrollableTabRow(
                selectedTabIndex = devToolsState.activeTab.ordinal,
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                edgePadding = 8.dp,
                divider = { HorizontalDivider() }
            ) {
                DevToolsTab.values().forEach { tab ->
                    Tab(
                        selected = devToolsState.activeTab == tab,
                        onClick = { viewModel.setDevToolsTab(tabId, tab) },
                        modifier = Modifier.testTag("devtools_tab_${tab.name.lowercase()}"),
                        text = {
                            Text(
                                text = tab.name,
                                fontSize = 11.sp,
                                fontWeight = if (devToolsState.activeTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Sub-Panel content switcher
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (devToolsState.activeTab) {
                    DevToolsTab.ELEMENTS -> ElementsInspector(tabId, devToolsState, webView, viewModel)
                    DevToolsTab.CONSOLE -> ConsoleInspector(tabId, devToolsState, webView, viewModel)
                    DevToolsTab.NETWORK -> NetworkInspector(tabId, devToolsState, viewModel)
                    DevToolsTab.STORAGE -> StorageInspector(tabId, devToolsState, webView, viewModel)
                    DevToolsTab.SECURITY -> SecurityInspector(tabId, devToolsState, viewModel)
                }
            }
        }
    }
}

@Composable
fun ElementsInspector(tabId: String, state: DevToolsState, webView: WebView?, viewModel: BrowserViewModel) {
    val context = LocalContext.current
    var isEditingStyle by remember { mutableStateOf(false) }
    var cssProperty by remember { mutableStateOf("") }
    var cssValue by remember { mutableStateOf("") }

    // Read full DOM outerHTML source on enter
    LaunchedEffect(Unit) {
        webView?.evaluateJavascript(
            "(function() { return document.documentElement.outerHTML; })();"
        ) { result ->
            val cleanResult = try {
                result?.removeSurrounding("\"")
                    ?.replace("\\u003C", "<")
                    ?.replace("\\u003E", ">")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\n", "\n")
                    ?.replace("\\\\", "\\") ?: "No HTML text captured"
            } catch (_: Exception) {
                "No HTML captured from frame"
            }
            val safeOutput = if (cleanResult.length > 20000) cleanResult.take(20000) + "\n... (truncated for UI stability)" else cleanResult
            viewModel.updateDomSource(tabId, safeOutput)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Element Picker Banner status
        if (state.isElementPickerActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        text = "Element Picker Active: Tap any element on website",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Selected inspector results (BUG-2 element details parser)
            val selected = state.selectedElement
            if (selected != null) {
                item {
                    Text(
                        text = "Inspected Element",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "TagName: <${selected.tagName}>",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            )
                            if (!selected.selector.isNullOrBlank()) {
                                Text(
                                    text = "Selector: ${selected.selector}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Metrics: X:${selected.rect.x.toInt()} Y:${selected.rect.y.toInt()} W:${selected.rect.width.toInt()} H:${selected.rect.height.toInt()}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                }

                // Inline visual CSS Editor (Highlight cosmetic properties)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Style Property Editor",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        TextButton(onClick = { isEditingStyle = !isEditingStyle }) {
                            Text(if (isEditingStyle) "Dismiss" else "Edit css", fontSize = 11.sp)
                        }
                    }

                    if (isEditingStyle) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                OutlinedTextField(
                                    value = cssProperty,
                                    onValueChange = { cssProperty = it },
                                    label = { Text("CSS Property (e.g. background-color)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("css_prop_input")
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = cssValue,
                                    onValueChange = { cssValue = it },
                                    label = { Text("New Value (e.g. yellow, red)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("css_value_input")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        if (cssProperty.isNotBlank()) {
                                            selected.selector?.let { selector ->
                                                val payloadScript = DevToolsScriptFactory.applyStyleScript(selector, cssProperty, cssValue)
                                                webView?.evaluateJavascript(payloadScript) { successResult ->
                                                    Toast.makeText(context, "Applied style: $successResult", Toast.LENGTH_SHORT).show()
                                                    val auditLog = ConsoleEntry(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        timestamp = System.currentTimeMillis(),
                                                        level = ConsoleLevel.DEBUG,
                                                        message = "CSS Applied: Styling [$selector] with property {$cssProperty: $cssValue} -> $successResult"
                                                    )
                                                    viewModel.addConsoleEntry(tabId, auditLog)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.End).testTag("css_apply_style")
                                ) {
                                    Text("Apply inline Style")
                                }
                            }
                        }
                    }
                }

                // Selected Node Attributes & Computed details list
                item {
                    Text(
                        text = "Attributes & Classes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (selected.attributes.isNotEmpty()) {
                        selected.attributes.forEach { (key, valString) ->
                            Text(
                                text = "• $key = \"$valString\"",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    } else {
                        Text("No attributes found on class list.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Fallback HTML page tree view
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Static Page HTML Source (truncated)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("dom_source", state.domSource))
                        Toast.makeText(context, "Copied HTML source to clipboard!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy DOM", fontSize = 10.sp)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = state.domSource,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConsoleInspector(tabId: String, state: DevToolsState, webView: WebView?, viewModel: BrowserViewModel) {
    var jsInput by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    val context = LocalContext.current

    // Auto-scroll to lowest items as console logs come
    LaunchedEffect(state.consoleEntries.size) {
        if (state.consoleEntries.isNotEmpty()) {
            lazyListState.animateScrollToItem(state.consoleEntries.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Console Logs (${state.consoleEntries.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row {
                TextButton(onClick = { viewModel.clearConsole(tabId) }, modifier = Modifier.testTag("console_clear")) {
                    Text("Clear logs", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalDivider()

        // Monospace rich logs list (BUG-3)
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            if (state.consoleEntries.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No JS logs captured yet. Trigger console events on-page.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            } else {
                items(state.consoleEntries) { entry ->
                    val color = when (entry.level) {
                        ConsoleLevel.ERROR -> MaterialTheme.colorScheme.error
                        ConsoleLevel.WARNING -> Color(0xFFE65100)
                        ConsoleLevel.INFO -> MaterialTheme.colorScheme.primary
                        ConsoleLevel.DEBUG -> MaterialTheme.colorScheme.secondary
                        ConsoleLevel.RESULT -> Color(0xFF1B5E20)
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    val prefix = when(entry.level) {
                        ConsoleLevel.ERROR -> "❌ "
                        ConsoleLevel.WARNING -> "⚠️ "
                        ConsoleLevel.INFO -> "ℹ️ "
                        ConsoleLevel.DEBUG -> "⚡ "
                        ConsoleLevel.RESULT -> "◀️ "
                        else -> "   "
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (entry.level == ConsoleLevel.RESULT) Color(0x0F4CAF50)
                                else if (entry.level == ConsoleLevel.ERROR) Color(0x0FFFEBEE)
                                else Color.Transparent
                            )
                            .padding(4.dp)
                    ) {
                        Text(
                            text = "$prefix ${entry.message}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = color
                        )
                        if ((entry.lineNumber ?: 0) > 0 || !entry.sourceId.isNullOrBlank()) {
                            Text(
                                text = "  at ${entry.sourceId ?: "unknown"}:${entry.lineNumber}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(start = 22.dp)
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Evaluator text field (BUG-3 capturing evaluation output safely)
        TextField(
            value = jsInput,
            onValueChange = { jsInput = it },
            modifier = Modifier.fillMaxWidth().testTag("console_eval_input"),
            placeholder = { Text("Run Javascript on web frame", fontSize = 11.sp) },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (jsInput.isNotBlank()) {
                            val cmd = jsInput
                            jsInput = ""
                            val script = DevToolsScriptFactory.getConsoleEvalScript(cmd)
                            webView?.evaluateJavascript(script) { rawResult ->
                                val cleanVal = try {
                                    if (rawResult == null || rawResult == "null") "undefined"
                                    else if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                                        // String output, unescape
                                        rawResult.removeSurrounding("\"").replace("\\\\", "\\").replace("\\\"", "\"")
                                    } else rawResult
                                } catch (_: Exception) {
                                    rawResult ?: "undefined"
                                }
                                viewModel.addConsoleResult(tabId, cmd, cleanVal)
                            }
                        }
                    },
                    modifier = Modifier.testTag("console_eval_submit")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run javascript", modifier = Modifier.size(16.dp))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (jsInput.isNotBlank()) {
                        val cmd = jsInput
                        jsInput = ""
                        val script = DevToolsScriptFactory.getConsoleEvalScript(cmd)
                        webView?.evaluateJavascript(script) { rawResult ->
                            val cleanVal = try {
                                if (rawResult == null || rawResult == "null") "undefined"
                                else if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                                    rawResult.removeSurrounding("\"").replace("\\\\", "\\").replace("\\\"", "\"")
                                } else rawResult
                            } catch (_: Exception) {
                                rawResult ?: "undefined"
                            }
                            viewModel.addConsoleResult(tabId, cmd, cleanVal)
                        }
                    }
                }
            )
        )
    }
}

@Composable
fun NetworkInspector(tabId: String, state: DevToolsState, viewModel: BrowserViewModel) {
    val context = LocalContext.current
    var activeFilter by remember { mutableStateOf<ResourceType?>(null) }
    var selectedReq by remember { mutableStateOf<NetworkEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Control Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Network Traffic (${state.networkEntries.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row {
                TextButton(
                    onClick = {
                        val json = DevToolsExporter.exportNetworkEntriesToJson(state.networkEntries)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("network_logs", json))
                        Toast.makeText(context, "Network logs exported & copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("network_export")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Export", fontSize = 10.sp)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = { viewModel.clearNetwork(tabId) }, modifier = Modifier.testTag("network_clear")) {
                    Text("Clear", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalDivider()

        // Resource types Filters row (Scrollable row)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "ALL" Option representation
            val isAllSelected = activeFilter == null
            Surface(
                selected = isAllSelected,
                onClick = { activeFilter = null },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .testTag("network_filter_all"),
                shape = RoundedCornerShape(12.dp),
                color = if (isAllSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = "ALL",
                    fontSize = 9.sp,
                    fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isAllSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            ResourceType.values().forEach { filterType ->
                val isSelected = activeFilter == filterType
                Surface(
                    selected = isSelected,
                    onClick = { activeFilter = filterType },
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag("network_filter_${filterType.name.lowercase()}"),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = filterType.name,
                        fontSize = 9.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        HorizontalDivider()

        // Filtering logs list (BUG-4 resource filtering)
        val filteredLogs = remember(state.networkEntries, activeFilter) {
            if (activeFilter == null) state.networkEntries
            else state.networkEntries.filter { it.resourceType == activeFilter }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (filteredLogs.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No match for filter ${activeFilter?.name ?: "ALL"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            } else {
                items(filteredLogs) { req ->
                    val statusColor = when (req.status) {
                        NetworkStatus.BLOCKED -> MaterialTheme.colorScheme.error
                        NetworkStatus.ALLOWED -> Color(0xFF2E7D32)
                        NetworkStatus.PENDING -> Color.Gray
                        NetworkStatus.FAILED -> Color.Red
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReq = req }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .border(
                                1.dp,
                                if (selectedReq?.id == req.id) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.12f),
                            modifier = Modifier.width(60.dp)
                        ) {
                            Text(
                                text = req.method,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                        }

                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                text = req.url,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${req.resourceType} • ${req.status}",
                                    fontSize = 8.sp,
                                    color = Color.Gray
                                )
                                if ((req.durationMs ?: 0L) > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${req.durationMs}ms",
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        if (req.status == NetworkStatus.BLOCKED) {
                            Icon(Icons.Default.Warning, contentDescription = "Blocked", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                        } else {
                            Text(
                                text = req.statusCode?.toString() ?: "200",
                                fontSize = 9.sp,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
                }
            }
        }

        // Sub-details selection card (BUG-4 detailed view info)
        val currentSelected = selectedReq
        if (currentSelected != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Request Diagnostics Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { selectedReq = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }

                    Text("• Target Url: ${currentSelected.url}", style = MaterialTheme.typography.bodySmall)
                    Text("• Hostname: ${currentSelected.host}", style = MaterialTheme.typography.bodySmall)
                    Text("• Method: ${currentSelected.method} | Scheme: ${currentSelected.scheme}", style = MaterialTheme.typography.bodySmall)
                    Text("• Resource Filter Category: ${currentSelected.resourceType}", style = MaterialTheme.typography.bodySmall)
                    Text("• MainFrame: ${currentSelected.isMainFrame} | Gesture Interaction: ${currentSelected.hasGesture}", style = MaterialTheme.typography.bodySmall)
                    
                    if (currentSelected.status == NetworkStatus.BLOCKED) {
                        Text("• Blocking Action Rule: ${currentSelected.blockedReason ?: "Tracker Blocked"}", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold))
                    } else {
                        Text("• Loaded Status code: ${currentSelected.statusCode ?: 200} | MimeType: ${currentSelected.mimeType ?: "text/html"}", style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Request Headers (secured & masked):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (currentSelected.requestHeaders.isNotEmpty()) {
                        currentSelected.requestHeaders.forEach { (k, v) ->
                            Text(
                                text = "  $k: $v",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text("  No headers captured", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun StorageInspector(tabId: String, state: DevToolsState, webView: WebView?, viewModel: BrowserViewModel) {
    val context = LocalContext.current
    var showGlobalWarning by remember { mutableStateOf(false) }

    fun refreshSnapshot() {
        val script = DevToolsScriptFactory.getStorageRefreshScript()
        webView?.evaluateJavascript(script) { result ->
            val cleanResult = try {
                result?.removeSurrounding("\"")?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
            } catch (_: Exception) {
                ""
            }
            if (cleanResult.isNotBlank() && !cleanResult.startsWith("{\"error\"")) {
                try {
                    val obj = JSONObject(cleanResult)
                    val rawCookies = obj.getString("cookies")
                    
                    val lsMapRaw = mutableMapOf<String, String>()
                    val lsObj = obj.getJSONObject("localStorage")
                    lsObj.keys().forEach { key ->
                        lsMapRaw[key] = lsObj.getString(key)
                    }
                    val lsMap = DevToolsSanitizer.sanitizeStorageMap(lsMapRaw)

                    val ssMapRaw = mutableMapOf<String, String>()
                    val ssObj = obj.getJSONObject("sessionStorage")
                    ssObj.keys().forEach { key ->
                        ssMapRaw[key] = ssObj.getString(key)
                    }
                    val ssMap = DevToolsSanitizer.sanitizeStorageMap(ssMapRaw)

                    val indexedDb = obj.getBoolean("indexedDbAvailable")

                    val newSnapshot = StorageSnapshot(
                        cookies = DevToolsSanitizer.sanitizeCookieString(rawCookies),
                        localStorage = lsMap,
                        sessionStorage = ssMap,
                        indexedDbAvailable = indexedDb
                    )
                    viewModel.updateStorageSnapshot(tabId, newSnapshot)
                } catch (e: Exception) {
                    Toast.makeText(context, "Parsing error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Storage blocked or empty: $cleanResult", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Capture storage details on load
    LaunchedEffect(Unit) {
        refreshSnapshot()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Site Storage Diagnostics",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row {
                TextButton(onClick = { refreshSnapshot() }, modifier = Modifier.testTag("storage_refresh")) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(10.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Refresh", fontSize = 10.sp)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = { showGlobalWarning = true }, modifier = Modifier.testTag("storage_clear_global")) {
                    Text("Purge All", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            val snapshot = state.storageSnapshot
            if (snapshot == null) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No snapshot loaded. Refresh storage.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            } else {
                // Cookies (BUG-5 domain-specific clear / view)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cookies (Sanitized)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            webView?.evaluateJavascript("document.cookie.split(';').forEach(el => { const eq = el.indexOf('='); const name = eq > -1 ? el.substr(0, eq) : el; document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/;'; });", null)
                            Toast.makeText(context, "Cleared document cookies for this origin!", Toast.LENGTH_SHORT).show()
                            refreshSnapshot()
                        }, modifier = Modifier.testTag("storage_clear_cookies")) {
                            Text("Clear Cookies", fontSize = 10.sp)
                        }
                    }
                    if (snapshot.cookies.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = snapshot.cookies,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Text("No public cookies available for this origin (or they are HttpOnly).", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                }

                // LocalStorage (BUG-5 clear local)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("LocalStorage (Sanitized)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            webView?.evaluateJavascript("localStorage.clear();", null)
                            Toast.makeText(context, "LocalStorage cleared for current site origin!", Toast.LENGTH_SHORT).show()
                            refreshSnapshot()
                        }, modifier = Modifier.testTag("storage_clear_ls")) {
                            Text("Clear LS", fontSize = 10.sp)
                        }
                    }
                    if (snapshot.localStorage.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                snapshot.localStorage.forEach { (k, v) ->
                                    Text(
                                        text = "$k: $v",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    } else {
                        Text("No keys inside LocalStorage.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                }

                // SessionStorage
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SessionStorage (Sanitized)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            webView?.evaluateJavascript("sessionStorage.clear();", null)
                            Toast.makeText(context, "SessionStorage cleared for current site origin!", Toast.LENGTH_SHORT).show()
                            refreshSnapshot()
                        }, modifier = Modifier.testTag("storage_clear_ss")) {
                            Text("Clear SS", fontSize = 10.sp)
                        }
                    }
                    if (snapshot.sessionStorage.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                snapshot.sessionStorage.forEach { (k, v) ->
                                    Text(
                                        text = "$k: $v",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    } else {
                        Text("No keys inside SessionStorage.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                }

                // Miscellaneous structures (IndexedDB availability check)
                item {
                    Text("Persistent Indexed Database Available: ${snapshot.indexedDbAvailable}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }

    // Modal warnings confirmation (BUG-5 Dialog protection)
    if (showGlobalWarning) {
        AlertDialog(
            onDismissRequest = { showGlobalWarning = false },
            title = { Text("Purge Global Databases") },
            text = { Text("Are you absolutely sure you want to delete ALL local databases, cookies, and cache files globally across ALL websites? This action can only be undone by re-authenticating on all pages.") },
            confirmButton = {
                Button(
                    onClick = {
                        android.webkit.WebStorage.getInstance().deleteAllData()
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        Toast.makeText(context, "Successfully deleted all site databases & cookies globally!", Toast.LENGTH_LONG).show()
                        showGlobalWarning = false
                        refreshSnapshot()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("storage_purge_confirm")
                ) {
                    Text("Purge Everything")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showGlobalWarning = false }) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
fun SecurityInspector(tabId: String, state: DevToolsState, viewModel: BrowserViewModel) {
    val tabs by viewModel.tabs.collectAsState()
    LaunchedEffect(tabId, state.securitySnapshot) {
        if (state.securitySnapshot == null) {
            val currentTab = tabs.find { it.id == tabId }
            if (currentTab != null && currentTab.url.isNotBlank()) {
                viewModel.refreshSecuritySnapshot(tabId, currentTab.url, currentTab.isPrivate)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        val sec = state.securitySnapshot
        if (sec == null) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("No Security audit collected. Browse secure pages.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        } else {
            // SSL Visual Accent header (BUG-10 green active icon / HTTP warning red)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (sec.isHttps) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (sec.isHttps) Icons.Default.Lock else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (sec.isHttps) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (sec.isHttps) "Connection is Encrypted" else "Connection is Unsecure",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (sec.isHttps) Color(0xFF1B5E20) else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Scheme: ${sec.scheme.uppercase()} | Host: ${sec.url}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.DarkGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Settings audit checklist (BUG-6 settings diagnostic display)
            Text("Browser Sandboxing & Content Policies:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("• Mixed Content Mode: ${sec.mixedContentMode}", style = MaterialTheme.typography.bodySmall)
                    Text("• Safe Browsing Active: ${sec.safeBrowsingEnabled}", style = MaterialTheme.typography.bodySmall)
                    Text("• JavaScript Interpreter Active: ${sec.javaScriptEnabled}", style = MaterialTheme.typography.bodySmall)
                    Text("• DOM Storage Key-Value Storage: ${sec.domStorageEnabled}", style = MaterialTheme.typography.bodySmall)
                    Text("• Local Files Storage Sandbox: Blocked (${!sec.fileAccessAllowed})", style = MaterialTheme.typography.bodySmall)
                    Text("• Content Provider Interface: Blocked (${!sec.contentAccessAllowed})", style = MaterialTheme.typography.bodySmall)
                    Text("• Third Party Cookie Blocking: Active (${!sec.thirdPartyCookiesAllowed})", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Block activities visual list (BUG-6 block action metrics)
            Text("AdBlock & Tracker Shield Metrics:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("• AdBlock Active Mode: ${sec.adBlockMode}", style = MaterialTheme.typography.bodySmall)
                    Text("• Tracker Prevention Shield: ${if (sec.trackerProtectionEnabled) "Enabled" else "Disabled"}", style = MaterialTheme.typography.bodySmall)
                    Text("• Incognito Environment Profile: ${if (sec.isPrivateTab) "Yes (Private Profile)" else "No"}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Warnings, SSL details, and Threats reports (BUG-10 SSL logs)
            if (!sec.lastSslError.isNullOrBlank() || !sec.lastSafeBrowsingThreat.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Security Diagnostics Events:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (!sec.lastSslError.isNullOrBlank()) {
                            Text(
                                text = "⚠️ Last certificate error:\n${sec.lastSslError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (!sec.lastSafeBrowsingThreat.isNullOrBlank()) {
                            if (!sec.lastSslError.isNullOrBlank()) Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "🚨 Safe Browsing threat identified:\n${sec.lastSafeBrowsingThreat}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.example.devtools

enum class DevToolsTab {
    ELEMENTS,
    CONSOLE,
    NETWORK,
    STORAGE,
    SECURITY
}

enum class ConsoleLevel {
    LOG,
    INFO,
    WARNING,
    ERROR,
    DEBUG,
    RESULT
}

data class ConsoleEntry(
    val id: String,
    val timestamp: Long,
    val level: ConsoleLevel,
    val message: String,
    val sourceId: String? = null,
    val lineNumber: Int? = null
)

enum class NetworkStatus {
    PENDING,
    ALLOWED,
    BLOCKED,
    FAILED
}

enum class ResourceType {
    DOCUMENT,
    SCRIPT,
    STYLESHEET,
    IMAGE,
    FONT,
    MEDIA,
    XHR,
    FETCH,
    WEBSOCKET,
    OTHER
}

data class NetworkEntry(
    val id: String,
    val timestamp: Long,
    val url: String,
    val method: String,
    val host: String,
    val scheme: String,
    val isMainFrame: Boolean,
    val hasGesture: Boolean,
    val resourceType: ResourceType,
    val requestHeaders: Map<String, String> = emptyMap(),
    val status: NetworkStatus = NetworkStatus.PENDING,
    val statusCode: Int? = null,
    val mimeType: String? = null,
    val blockedReason: String? = null,
    val durationMs: Long? = null
)

data class ElementRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class SelectedElement(
    val tagName: String,
    val id: String?,
    val classes: List<String>,
    val textPreview: String,
    val attributes: Map<String, String>,
    val computedStyles: Map<String, String>,
    val selector: String,
    val rect: ElementRect
)

data class StorageSnapshot(
    val cookies: String,
    val localStorage: Map<String, String>,
    val sessionStorage: Map<String, String>,
    val indexedDbAvailable: Boolean
)

data class SecuritySnapshot(
    val url: String,
    val scheme: String,
    val isHttps: Boolean,
    val mixedContentMode: String,
    val safeBrowsingEnabled: Boolean,
    val javaScriptEnabled: Boolean,
    val domStorageEnabled: Boolean,
    val fileAccessAllowed: Boolean,
    val contentAccessAllowed: Boolean,
    val thirdPartyCookiesAllowed: Boolean,
    val adBlockMode: String,
    val trackerProtectionEnabled: Boolean,
    val isPrivateTab: Boolean,
    val lastSslError: String? = null,
    val lastSafeBrowsingThreat: String? = null
)

data class DevToolsState(
    val isOpen: Boolean = false,
    val activeTab: DevToolsTab = DevToolsTab.CONSOLE,
    val isElementPickerActive: Boolean = false,
    val selectedElement: SelectedElement? = null,
    val consoleEntries: List<ConsoleEntry> = emptyList(),
    val networkEntries: List<NetworkEntry> = emptyList(),
    val domSource: String = "",
    val storageSnapshot: StorageSnapshot? = null,
    val securitySnapshot: SecuritySnapshot? = null,
    val searchQuery: String = "",
    val isRecordingNetwork: Boolean = true
)

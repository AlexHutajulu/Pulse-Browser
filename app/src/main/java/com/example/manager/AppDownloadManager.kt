package com.example.manager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.data.DownloadItem
import com.example.data.DownloadRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class ActiveDownload(
    val id: String,
    val fileName: String,
    val url: String,
    val domain: String,
    val mimeType: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBps: Long,
    val status: String, // QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    val fileUri: String,
    val isIncognito: Boolean,
    val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
    val job: Job? = null
)

class AppDownloadManager(
    private val context: Context,
    private val repository: DownloadRepository,
    private val scope: CoroutineScope
) {
    private val _activeDownloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, ActiveDownload>> = _activeDownloads.asStateFlow()

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "alex_browser_downloads"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File download progress and results"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun resolveWritableDownloadsDir(): File {
        // On Android 10+ (API 29+), Scoped Storage restricts direct raw File writes to public Downloads.
        // Using getExternalFilesDir(DIRECTORY_DOWNLOADS) guarantees reliable write + FileProvider sharing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            try {
                if (!publicDir.exists()) publicDir.mkdirs()
                if (publicDir.exists() && publicDir.canWrite()) {
                    return publicDir
                }
            } catch (_: Exception) {}
        }

        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExtDir != null) {
            if (!appExtDir.exists()) appExtDir.mkdirs()
            if (appExtDir.exists() && appExtDir.canWrite()) {
                return appExtDir
            }
        }

        return File(context.filesDir, "downloads").apply {
            if (!exists()) mkdirs()
        }
    }

    fun isPathWithinDirectory(file: File, baseDir: File): Boolean {
        return try {
            val canonicalDirPath = baseDir.canonicalFile.path
            val canonicalFilePath = file.canonicalFile.path
            val dirPrefix = if (canonicalDirPath.endsWith(File.separator)) {
                canonicalDirPath
            } else {
                canonicalDirPath + File.separator
            }
            canonicalFilePath.startsWith(dirPrefix)
        } catch (_: Exception) {
            false
        }
    }

    internal fun isSameOrSubdomainHost(currentHost: String, initialHost: String): Boolean {
        val cleanCurrent = currentHost.lowercase().trim().removeSurrounding("[", "]").trimEnd('.')
        val cleanInitial = initialHost.lowercase().trim().removeSurrounding("[", "]").trimEnd('.')
        if (cleanCurrent.isEmpty() || cleanInitial.isEmpty()) return false
        return cleanCurrent == cleanInitial || cleanCurrent.endsWith(".$cleanInitial")
    }

    private fun openSafeConnection(
        initialUrl: String,
        userAgent: String,
        isIncognito: Boolean = false,
        rangeHeader: String? = null,
        maxRedirects: Int = 5
    ): HttpURLConnection {
        val initialHost = try {
            URL(initialUrl.trim()).host ?: ""
        } catch (_: Exception) {
            ""
        }
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            if (!isSafeDownloadUrl(currentUrl, resolveDns = true)) {
                throw SecurityException("Blocked unsafe download URL or private network redirect target")
            }
            val urlObj = URL(currentUrl)
            val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (!rangeHeader.isNullOrBlank()) {
                    setRequestProperty("Range", rangeHeader)
                }
                // Never leak normal-session cookies in Incognito or across cross-domain redirects
                if (!isIncognito && isSameOrSubdomainHost(urlObj.host ?: "", initialHost)) {
                    val scopedCookie = try {
                        android.webkit.CookieManager.getInstance().getCookie(currentUrl)
                    } catch (_: Exception) {
                        null
                    }
                    if (!scopedCookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", scopedCookie)
                    }
                }
            }
            conn.connect()
            val code = conn.responseCode
            if (code in intArrayOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw Exception("Redirect response HTTP $code missing Location header")
                }
                if (++redirects > maxRedirects) {
                    throw SecurityException("Too many HTTP redirects (max $maxRedirects)")
                }
                val nextUrlObj = URL(urlObj, location)
                if (urlObj.protocol.equals("https", ignoreCase = true) &&
                    nextUrlObj.protocol.equals("http", ignoreCase = true)
                ) {
                    throw SecurityException("Blocked unsafe HTTPS to HTTP redirect downgrade")
                }
                currentUrl = nextUrlObj.toString()
                continue
            }
            return conn
        }
    }

    fun startDownload(
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String,
        domain: String,
        isIncognito: Boolean
    ): Boolean {
        if (!isSafeDownloadUrl(url, resolveDns = false)) return false
        val id = UUID.randomUUID().toString()
        val downloadsDir = resolveWritableDownloadsDir()
        val sanitizedName = sanitizeFileName(fileName)
        
        // Find a unique file name if it already exists to avoid corrupting previous downloads
        var finalFile = File(downloadsDir, sanitizedName)
        var finalName = sanitizedName
        if (finalFile.exists()) {
            val baseName = sanitizedName.substringBeforeLast(".")
            val extension = if (sanitizedName.contains(".")) "." + sanitizedName.substringAfterLast(".") else ""
            var count = 1
            while (finalFile.exists()) {
                finalName = "$baseName ($count)$extension"
                finalFile = File(downloadsDir, finalName)
                count++
            }
        }

        // Canonical directory traversal guard with strict separator check
        if (!isPathWithinDirectory(finalFile, downloadsDir)) {
            return false
        }

        val newDownload = ActiveDownload(
            id = id,
            fileName = finalName,
            url = url,
            domain = domain.ifBlank { "local" },
            mimeType = mimeType,
            totalBytes = 0L,
            downloadedBytes = 0L,
            speedBps = 0L,
            status = "QUEUED",
            fileUri = finalFile.absolutePath,
            isIncognito = isIncognito,
            userAgent = userAgent,
            job = null
        )
        
        _activeDownloads.update { it + (id to newDownload) }
        saveToDb(newDownload)

        val job = scope.launch(Dispatchers.IO) {
            executeDownload(id, url, userAgent, finalFile, isResume = false)
        }
        
        _activeDownloads.update { it.mapValues { entry -> 
            if (entry.key == id) entry.value.copy(job = job) else entry.value 
        } }
        return true
    }

    private suspend fun executeDownload(
        id: String,
        url: String,
        userAgent: String,
        destFile: File,
        isResume: Boolean = false
    ) {
        val currentDownload = _activeDownloads.value[id] ?: return
        val isIncognito = currentDownload.isIncognito
        
        _activeDownloads.update { it + (id to currentDownload.copy(status = "DOWNLOADING")) }
        saveToDb(currentDownload.copy(status = "DOWNLOADING"))

        try {
            if (!isSafeDownloadUrl(url, resolveDns = false)) {
                throw SecurityException("Blocked unsafe download URL scheme or private network target")
            }
            
            // Canonical directory traversal guard with strict separator check
            val parentDir = destFile.parentFile ?: resolveWritableDownloadsDir()
            if (!isPathWithinDirectory(destFile, parentDir)) {
                throw SecurityException("Path traversal attempt detected in download target")
            }

            // Support data: URI downloads (e.g. Base64 images saved from WebView) with strict size cap
            if (url.startsWith("data:", ignoreCase = true)) {
                if (url.length > MAX_DATA_URI_LENGTH) {
                    throw SecurityException("Data URI exceeds maximum safe length (${MAX_DATA_URI_LENGTH / (1024 * 1024)} MB)")
                }
                val commaIndex = url.indexOf(',')
                if (commaIndex == -1) throw Exception("Invalid data URI")
                val meta = url.substring(5, commaIndex)
                val payload = url.substring(commaIndex + 1)
                val bytes = if (meta.contains(";base64", ignoreCase = true)) {
                    android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                } else {
                    java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
                }
                val usableSpaceData = parentDir.usableSpace
                if (bytes.isNotEmpty() && usableSpaceData > 0 && usableSpaceData < bytes.size + (10 * 1024 * 1024)) {
                    throw Exception("Insufficient storage space for data URI. Need ${formatSize(bytes.size.toLong())} but only ${formatSize(usableSpaceData)} is available.")
                }
                destFile.writeBytes(bytes)
                val size = bytes.size.toLong()
                _activeDownloads.update {
                    val current = it[id] ?: return@update it
                    it + (id to current.copy(
                        downloadedBytes = size,
                        totalBytes = size,
                        speedBps = 0L,
                        status = "COMPLETED"
                    ))
                }
                _activeDownloads.value[id]?.let { finalDownload ->
                    saveToDb(finalDownload)
                    updateNotification(finalDownload)
                }
                return
            }

            // Establish target metadata of source URL using quick Range check with safe redirect validation
            var testConn: HttpURLConnection? = null
            var supportsRange = false
            var totalSize = -1L
            var resolvedTargetUrl = url
            try {
                testConn = openSafeConnection(url, userAgent, isIncognito = isIncognito, rangeHeader = "bytes=0-0")
                resolvedTargetUrl = testConn.url?.toString() ?: url
                val responseCode = testConn.responseCode
                supportsRange = (responseCode == HttpURLConnection.HTTP_PARTIAL)
                totalSize = if (supportsRange) {
                    testConn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: testConn.contentLengthLong
                } else {
                    testConn.contentLengthLong
                }
            } finally {
                try { testConn?.disconnect() } catch (_: Exception) {}
            }

            // Safe Scoped-Storage/Low-Storage Check
            val usableSpace = parentDir.usableSpace
            if (totalSize > 0 && usableSpace > 0 && usableSpace < totalSize + (10 * 1024 * 1024)) { // check space with 10MB safety margin
                throw Exception("Insufficient storage space. Need ${formatSize(totalSize)} but only ${formatSize(usableSpace)} is available.")
            }

            val segmentCount = 4
            if (supportsRange && totalSize > 1024 * 1024) {  // Segmented multi-part parallel download for sizes > 1 MB
                val segmentSize = totalSize / segmentCount
                
                _activeDownloads.update { 
                    val current = it[id] ?: return@update it
                    it + (id to current.copy(totalBytes = totalSize))
                }

                val progressJob = scope.launch(Dispatchers.IO) {
                    var lastUpdateTime = System.currentTimeMillis()
                    var lastDownloaded = 0L
                    while (isActive) {
                        delay(600)
                        val totalDownloaded = (0 until segmentCount).sumOf { i ->
                            File(destFile.absolutePath + ".part$i").let { if (it.exists()) it.length() else 0L }
                        }
                        val now = System.currentTimeMillis()
                        val duration = (now - lastUpdateTime) / 1000.0
                        val speed = if (duration > 0) ((totalDownloaded - lastDownloaded) / duration).toLong() else 0L
                        lastDownloaded = totalDownloaded
                        lastUpdateTime = now

                        _activeDownloads.update { 
                            val current = it[id] ?: return@update it
                            it + (id to current.copy(
                                downloadedBytes = totalDownloaded, 
                                speedBps = speed,
                                status = "DOWNLOADING"
                            ))
                        }
                        _activeDownloads.value[id]?.let { download ->
                            updateNotification(download)
                        }
                    }
                }

                try {
                    coroutineScope {
                        (0 until segmentCount).map { i ->
                            val startBytes = segmentSize * i
                            val endBytes = if (i == segmentCount - 1) totalSize - 1 else (segmentSize * (i + 1)) - 1
                            launch(Dispatchers.IO) {
                                downloadSegmentPart(resolvedTargetUrl, userAgent, isIncognito, destFile, i, startBytes, endBytes)
                            }
                        }
                    }
                    progressJob.cancel()

                    // Ensure all parts downloaded successfully matching total count
                    val finalDownloaded = (0 until segmentCount).sumOf { i ->
                        File(destFile.absolutePath + ".part$i").let { if (it.exists()) it.length() else 0L }
                    }

                    if (finalDownloaded == totalSize) {
                        mergeSegmentParts(destFile, segmentCount)
                        
                        _activeDownloads.update { 
                            val current = it[id] ?: return@update it
                            it + (id to current.copy(
                                downloadedBytes = totalSize,
                                totalBytes = totalSize,
                                speedBps = 0L,
                                status = "COMPLETED"
                            ))
                        }
                        _activeDownloads.value[id]?.let { finalDownload ->
                            saveToDb(finalDownload)
                            updateNotification(finalDownload)
                        }
                    } else {
                        throw Exception("Segment sync size mismatch: expected $totalSize, got $finalDownloaded")
                    }

                } catch (ce: CancellationException) {
                    progressJob.cancel()
                    throw ce
                } catch (e: Exception) {
                    progressJob.cancel()
                    throw e
                }

            } else {
                // Sequential fallback downloading
                var connection: HttpURLConnection? = null
                var inputStream: InputStream? = null
                var randomAccessFile: RandomAccessFile? = null

                try {
                    val resumeFromFile = isResume && destFile.exists()
                    val downloadedBefore = if (resumeFromFile) destFile.length() else 0L

                    val rangeHeader = if (resumeFromFile && downloadedBefore > 0) {
                        "bytes=$downloadedBefore-"
                    } else {
                        null
                    }
                    connection = openSafeConnection(resolvedTargetUrl, userAgent, isIncognito = isIncognito, rangeHeader = rangeHeader)

                    val seqResponseCode = connection.responseCode
                    val isPartial = seqResponseCode == HttpURLConnection.HTTP_PARTIAL
                    val isOk = seqResponseCode == HttpURLConnection.HTTP_OK

                    if (!isOk && !isPartial) {
                        throw Exception("Server connection error HTTP $seqResponseCode")
                    }

                    val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                    val totalSeqSize = if (isPartial) downloadedBefore + contentLength else contentLength

                    val usableSpaceSeq = parentDir.usableSpace
                    val remainingNeed = if (isPartial && contentLength > 0) contentLength else totalSeqSize
                    if (remainingNeed > 0 && usableSpaceSeq > 0 && usableSpaceSeq < remainingNeed + (10 * 1024 * 1024)) {
                        throw Exception("Insufficient storage space. Need ${formatSize(remainingNeed)} but only ${formatSize(usableSpaceSeq)} is available.")
                    }

                    _activeDownloads.update { 
                        val current = it[id] ?: return@update it
                        it + (id to current.copy(totalBytes = totalSeqSize, downloadedBytes = downloadedBefore))
                    }

                    inputStream = connection.inputStream
                    randomAccessFile = RandomAccessFile(destFile, "rw")
                    if (isPartial) {
                        randomAccessFile.seek(downloadedBefore)
                    } else {
                        randomAccessFile.setLength(0)
                    }

                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalDownloaded = if (isPartial) downloadedBefore else 0L
                    var lastUpdateTime = System.currentTimeMillis()
                    var speedLastTotal = totalDownloaded

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        yield()
                        randomAccessFile.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 500) {
                            val speed = ((totalDownloaded - speedLastTotal) / ((now - lastUpdateTime) / 1000.0)).toLong()
                            speedLastTotal = totalDownloaded
                            lastUpdateTime = now

                            _activeDownloads.update { 
                                val current = it[id] ?: return@update it
                                it + (id to current.copy(
                                    downloadedBytes = totalDownloaded, 
                                    speedBps = speed,
                                    status = "DOWNLOADING"
                                ))
                            }
                            _activeDownloads.value[id]?.let { download ->
                                updateNotification(download)
                            }
                        }
                    }

                    _activeDownloads.update { 
                        val current = it[id] ?: return@update it
                        it + (id to current.copy(
                            downloadedBytes = totalDownloaded,
                            totalBytes = totalDownloaded,
                            speedBps = 0L,
                            status = "COMPLETED"
                        ))
                    }
                    _activeDownloads.value[id]?.let { finalDownload ->
                        saveToDb(finalDownload)
                        updateNotification(finalDownload)
                    }

                } finally {
                    try { inputStream?.close() } catch (e: Exception) {}
                    try { randomAccessFile?.close() } catch (e: Exception) {}
                    try { connection?.disconnect() } catch (e: Exception) {}
                }
            }

        } catch (e: CancellationException) {
            val state = _activeDownloads.value[id]?.status ?: "CANCELLED"
            _activeDownloads.value[id]?.let { download ->
                if (state == "PAUSED") {
                    saveToDb(download)
                    updateNotification(download)
                } else if (state == "CANCELLED") {
                    destFile.delete()
                    (0..3).forEach { i -> File(destFile.absolutePath + ".part$i").delete() }
                    saveToDb(download)
                    notificationManager.cancel(id.hashCode())
                }
            }
            throw e
        } catch (e: Exception) {
            _activeDownloads.update { 
                val current = it[id] ?: return@update it
                it + (id to current.copy(status = "FAILED"))
            }
            _activeDownloads.value[id]?.let { download ->
                saveToDb(download)
                updateNotification(download)
            }
        }
    }

    private suspend fun downloadSegmentPart(
        url: String,
        userAgent: String,
        isIncognito: Boolean,
        destFile: File,
        partIndex: Int,
        startBytes: Long,
        endBytes: Long
    ) {
        val partFile = File(destFile.absolutePath + ".part$partIndex")
        val currentLength = if (partFile.exists()) partFile.length() else 0L
        val actualStartBytes = startBytes + currentLength

        if (actualStartBytes >= endBytes) {
            return // Segment already complete
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            connection = openSafeConnection(
                initialUrl = url,
                userAgent = userAgent,
                isIncognito = isIncognito,
                rangeHeader = "bytes=$actualStartBytes-$endBytes"
            )

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Segment $partIndex request denied: HTTP $responseCode")
            }

            inputStream = connection.inputStream
            outputStream = FileOutputStream(partFile, true)

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                yield()
                outputStream.write(buffer, 0, bytesRead)
            }
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            try { outputStream?.close() } catch (e: Exception) {}
            try { connection?.disconnect() } catch (e: Exception) {}
        }
    }

    private fun mergeSegmentParts(destFile: File, segmentCount: Int) {
        val outputStream = FileOutputStream(destFile)
        try {
            val buffer = ByteArray(64 * 1024)
            for (i in 0 until segmentCount) {
                val partFile = File(destFile.absolutePath + ".part$i")
                val inputStream = partFile.inputStream()
                try {
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                } finally {
                    inputStream.close()
                }
                partFile.delete()
            }
        } finally {
            outputStream.close()
        }
    }

    fun pauseDownload(id: String) {
        val download = _activeDownloads.value[id] ?: return
        if (download.status == "DOWNLOADING") {
            _activeDownloads.update { it + (id to download.copy(status = "PAUSED")) }
            download.job?.cancel()
        }
    }

    fun resumeDownload(id: String) {
        val download = _activeDownloads.value[id] ?: return
        if (download.status == "PAUSED" || download.status == "FAILED") {
            val job = scope.launch(Dispatchers.IO) {
                executeDownload(
                    id = id,
                    url = download.url,
                    userAgent = download.userAgent,
                    destFile = File(download.fileUri),
                    isResume = true
                )
            }
            _activeDownloads.update { it + (id to download.copy(job = job, status = "QUEUED")) }
        }
    }

    fun cancelDownload(id: String) {
        val download = _activeDownloads.value[id] ?: return
        _activeDownloads.update { it + (id to download.copy(status = "CANCELLED")) }
        download.job?.cancel()
    }
    
    fun removeActiveDownloadEntry(id: String) {
        _activeDownloads.update { it - id }
    }

    fun clearIncognitoEntries() {
        _activeDownloads.update { current ->
            current.filterValues { !it.isIncognito || it.status == "DOWNLOADING" }
        }
    }

    private fun saveToDb(download: ActiveDownload) {
        scope.launch {
            repository.insertOrUpdate(
                DownloadItem(
                    id = download.id,
                    fileName = download.fileName,
                    url = download.url,
                    domain = download.domain,
                    mimeType = download.mimeType,
                    totalBytes = download.totalBytes,
                    downloadedBytes = download.downloadedBytes,
                    status = download.status,
                    fileUri = download.fileUri,
                    isIncognito = download.isIncognito,
                    completedAt = if (download.status == "COMPLETED") System.currentTimeMillis() else 0L
                )
            )
        }
    }

    private fun updateNotification(download: ActiveDownload) {
        try {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(download.fileName)
                .setOnlyAlertOnce(true)
                .setOngoing(download.status == "DOWNLOADING")

            when (download.status) {
                "DOWNLOADING" -> {
                    val progress = if (download.totalBytes > 0) (download.downloadedBytes * 100 / download.totalBytes).toInt() else 0
                    builder.setProgress(100, progress, download.totalBytes <= 0)
                    builder.setContentText("Downloading... ${progress}% (${formatSize(download.speedBps)}/s)")
                }
                "PAUSED" -> {
                    builder.setContentText("Paused")
                    builder.setSmallIcon(android.R.drawable.ic_media_pause)
                }
                "COMPLETED" -> {
                    builder.setContentText("Download completed")
                    builder.setProgress(0, 0, false)
                    builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                }
                "FAILED" -> {
                    builder.setContentText("Download failed")
                    builder.setProgress(0, 0, false)
                    builder.setSmallIcon(android.R.drawable.stat_notify_error)
                }
            }

            val notificationId = download.id.hashCode()
            notificationManager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            // Suppressed to prevent edge failures in background
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val z = (63 - java.lang.Long.numberOfLeadingZeros(bytes)) / 10
        return String.format("%.1f %sB", bytes.toDouble() / (1L shl (z * 10)), " KMGTPE"[z])
    }

    fun isSafeDownloadUrl(url: String): Boolean = Companion.isSafeDownloadUrl(url, false)

    fun isSafeDownloadUrl(url: String, resolveDns: Boolean): Boolean = Companion.isSafeDownloadUrl(url, resolveDns)

    fun sanitizeFileName(name: String): String = Companion.sanitizeFileName(name)

    companion object {
        internal const val MAX_DATA_URI_LENGTH = 25 * 1024 * 1024 // 25 MB max character length for data: URIs

        internal fun isSafeDownloadUrl(url: String, resolveDns: Boolean = false): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("data:", ignoreCase = true)) {
                return trimmed.length <= MAX_DATA_URI_LENGTH && trimmed.contains(',')
            }
            if (trimmed.length > 4096) return false
            return try {
                val parsed = URL(trimmed)
                val protocol = parsed.protocol.lowercase()
                if (protocol != "http" && protocol != "https") return false
                val host = parsed.host.lowercase().removeSurrounding("[", "]").trimEnd('.')
                if (host.isBlank()) return false

                // Delegate to unified address validator (only checks IPv6 fc/fd prefixes on actual IPv6 literals)
                if (SecurityGuardEngine.isLocalOrPrivateAddress(host)) {
                    return false
                }

                if (resolveDns) {
                    val addresses = java.net.InetAddress.getAllByName(host)
                    if (addresses.isEmpty()) return false
                    if (addresses.any { addr -> FilterListManager.isRestrictedAddress(addr) }) {
                        return false
                    }
                }

                true
            } catch (_: Exception) {
                false
            }
        }

        internal fun isSafeRedirectHop(currentUrl: String, nextUrl: String, resolveDns: Boolean = false): Boolean {
            if (!isSafeDownloadUrl(currentUrl, resolveDns = resolveDns)) return false
            if (!isSafeDownloadUrl(nextUrl, resolveDns = resolveDns)) return false
            val fromHttps = currentUrl.trim().startsWith("https://", ignoreCase = true)
            val toHttp = nextUrl.trim().startsWith("http://", ignoreCase = true)
            if (fromHttps && toHttp) {
                return false
            }
            return true
        }

        internal fun sanitizeFileName(name: String): String {
            var decoded = name
            repeat(2) {
                decoded = try {
                    java.net.URLDecoder.decode(decoded, "UTF-8")
                } catch (_: Exception) {
                    decoded
                }
            }
            val cleanName = decoded
                // Strip Unicode bidirectional override chars (RLO/LRO/Bidi isolates) to prevent extension spoofing
                .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
                .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]"), "_")
                .replace(Regex("\\.\\.+"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.')
                .trim()
                .ifBlank { "download.bin" }
                .take(180)
                .trim('.', ' ')

            return if (cleanName.isBlank()) "download.bin" else cleanName
        }
    }
}

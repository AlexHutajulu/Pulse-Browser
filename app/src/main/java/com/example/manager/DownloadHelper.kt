package com.example.manager

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object DownloadHelper {

    suspend fun checkAcceptRanges(urlStr: String, userAgent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            var currentUrl = urlStr.trim()
            var redirects = 0
            val maxRedirects = 5
            while (true) {
                if (!AppDownloadManager.isSafeDownloadUrl(currentUrl, resolveDns = true)) {
                    return@withContext false
                }
                val url = URL(currentUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    useCaches = false
                    requestMethod = "HEAD"
                    setRequestProperty("User-Agent", userAgent)
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val status = connection.responseCode
                if (status in intArrayOf(
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP,
                        HttpURLConnection.HTTP_SEE_OTHER,
                        307,
                        308
                    )
                ) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank() || ++redirects > maxRedirects) {
                        return@withContext false
                    }
                    val nextUrl = URL(url, location).toString()
                    if (!AppDownloadManager.isSafeRedirectHop(currentUrl, nextUrl, resolveDns = true)) {
                        return@withContext false
                    }
                    currentUrl = nextUrl
                    continue
                }

                val acceptRanges = connection.getHeaderField("Accept-Ranges")
                val supportRange = acceptRanges?.equals("bytes", ignoreCase = true) == true
                connection.disconnect()
                return@withContext supportRange
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun downloadFile(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        downloadAcceleration: String,
        isIncognito: Boolean = false,
        appDownloadManager: AppDownloadManager? = null
    ) {
        if (!AppDownloadManager.isSafeDownloadUrl(url, resolveDns = false) || url.startsWith("data:", ignoreCase = true)) {
            val blockedMsg = context.getString(R.string.download_blocked_unsafe)
            android.widget.Toast.makeText(context, blockedMsg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val rawFilename = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val filename = AppDownloadManager.sanitizeFileName(rawFilename)

        // Safety extensions validation
        val suspiciousExtensions = listOf(".apk", ".exe", ".sh", ".bat", ".vbs", ".msi", ".jar")
        var finalMimeType = mimeType
        if (suspiciousExtensions.any { filename.lowercase().endsWith(it) }) {
            val warningMsg = context.getString(R.string.download_warning_dangerous_file)
            android.widget.Toast.makeText(context, warningMsg, android.widget.Toast.LENGTH_LONG).show()
            if (finalMimeType.isBlank() || finalMimeType == "application/octet-stream") {
                if (filename.lowercase().endsWith(".apk")) finalMimeType = "application/vnd.android.package-archive"
            }
        }

        val domain = try {
            URL(url.trim()).host ?: "local"
        } catch (_: Exception) {
            "local"
        }

        if (downloadAcceleration == "Fast") {
            if (appDownloadManager != null) {
                // Accelerated multi-connection parallel download routed directly via AppDownloadManager
                val fastMsg = context.getString(R.string.download_fast_accelerated)
                android.widget.Toast.makeText(context, fastMsg, android.widget.Toast.LENGTH_SHORT).show()
                appDownloadManager.startDownload(
                    url = url,
                    userAgent = userAgent,
                    fileName = filename,
                    mimeType = finalMimeType,
                    domain = domain,
                    isIncognito = isIncognito
                )
            } else {
                // Standalone / legacy fallback: check Range support and enqueue to system manager
                CoroutineScope(Dispatchers.Main).launch {
                    val supportsRange = checkAcceptRanges(url, userAgent)
                    if (supportsRange) {
                        val fastMsg = context.getString(R.string.download_fast_accelerated)
                        android.widget.Toast.makeText(context, fastMsg, android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val fallbackMsg = context.getString(R.string.download_range_fallback_normal)
                        android.widget.Toast.makeText(context, fallbackMsg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    enqueueStandardDownload(context, url, userAgent, filename, finalMimeType, isIncognito)
                }
            }
        } else {
            if (appDownloadManager != null) {
                appDownloadManager.startDownload(
                    url = url,
                    userAgent = userAgent,
                    fileName = filename,
                    mimeType = finalMimeType,
                    domain = domain,
                    isIncognito = isIncognito
                )
            } else {
                enqueueStandardDownload(context, url, userAgent, filename, finalMimeType, isIncognito)
            }
        }
    }

    private fun enqueueStandardDownload(
        context: Context,
        url: String,
        userAgent: String,
        filename: String,
        mimeType: String,
        isIncognito: Boolean = false
    ) {
        if (!AppDownloadManager.isSafeDownloadUrl(url, resolveDns = false) || url.startsWith("data:", ignoreCase = true)) {
            return
        }
        val safeFilename = AppDownloadManager.sanitizeFileName(filename)
        val request = DownloadManager.Request(Uri.parse(url))
        request.setMimeType(mimeType)
        if (!isIncognito) {
            val cookies = try {
                CookieManager.getInstance().getCookie(url)
            } catch (_: Exception) {
                null
            }
            if (!cookies.isNullOrBlank()) {
                request.addRequestHeader("cookie", cookies)
            }
        }
        request.addRequestHeader("User-Agent", userAgent)
        
        request.setDescription(context.getString(R.string.downloading_file))
        request.setTitle(safeFilename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFilename)

        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        } catch (e: Exception) {
            val failMsg = context.getString(R.string.download_failed_prefix, e.message ?: "")
            android.widget.Toast.makeText(context, failMsg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

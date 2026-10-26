package com.example.manager

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.yourbrowser.adblock.AdBlockWebViewClient
import com.yourbrowser.adblock.PrivacyMode

object AdBlockServiceWorker {

    private val adBlockClient = AdBlockWebViewClient(PrivacyMode.BALANCED)

    fun install() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            try {
                val controller = ServiceWorkerControllerCompat.getInstance()
                controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return adBlockClient.shouldInterceptRequest(null, request)
                    }
                })
            } catch (_: Exception) {
                // Silently fallback if device OS disables service worker customization
            }
        }
    }

    fun setMode(mode: PrivacyMode) {
        adBlockClient.setMode(mode)
    }

    fun setTrackerBlockingEnabled(enabled: Boolean, activeWhenAdBlockOff: Boolean = true) {
        adBlockClient.setTrackerBlockingEnabled(enabled, activeWhenAdBlockOff)
    }

    fun setStreamingBoostEnabled(enabled: Boolean) {
        adBlockClient.setStreamingBoostEnabled(enabled)
    }
}

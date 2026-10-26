package com.example.manager

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Injects cosmetic element hiding rules at DOCUMENT_START using WebViewCompat.
 * Ensures zero-layout-shift and stops ad banners before initial paint.
 */
object DocumentStartCosmetics {

    private const val SCRIPT = """
(function() {
    try {
        if (!window.AlexBrowserNative || !window.AlexBrowserNative.getCosmeticCss) return;
        var host = location.hostname;
        var css = window.AlexBrowserNative.getCosmeticCss(host);
        if (css && css.length > 0) {
            var inject = function() {
                var target = document.head || document.documentElement;
                if (!target) return false;
                var style = document.createElement('style');
                style.type = 'text/css';
                style.setAttribute('data-alex-cosmetics', 'doc-start');
                style.appendChild(document.createTextNode(css));
                target.appendChild(style);
                return true;
            };
            if (!inject()) {
                document.addEventListener('DOMContentLoaded', inject, { once: true });
            }
        }
    } catch(e) {}
})();
    """

    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    SCRIPT,
                    setOf("*")
                )
            } catch (_: Exception) {}
        }
    }

    fun buildCosmeticCssForHost(host: String): String {
        val rules = FilterListManager.getCachedRules() ?: return ""
        val selectors = rules.ruleSet.cosmetic.selectorsFor(host)
            .take(AbpRuleEngine.MAX_COSMETIC_SELECTORS_PER_HOST)
            .filter { AbpRuleEngine.isSafeCosmeticSelector(it) }

        if (selectors.isEmpty()) return ""

        // Satu aturan per selektor untuk mencegah satu selektor korup merusak seluruh stylesheet
        return selectors.joinToString("\n") { "$it { display: none !important; }" }
    }
}

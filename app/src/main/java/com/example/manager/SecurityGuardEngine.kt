package com.example.manager

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ThreatSeverity {
    SAFE,
    HTTP_INSECURE,
    SUSPICIOUS_PHISHING,
    MALWARE_OR_SCAM
}

data class SecurityThreatAssessment(
    val url: String,
    val host: String,
    val severity: ThreatSeverity,
    val title: String = "",
    val reason: String = "",
    val recommendation: String = ""
)

object SecurityGuardEngine {

    private const val PREFS_NAME = "chrome_security_guard_prefs"
    private const val KEY_HTTPS_ONLY = "sec_guard_https_only"
    private const val KEY_PHISHING_SHIELD = "sec_guard_phishing_enabled"
    private const val KEY_BLOCK_CAM_MIC = "sec_guard_block_cam_mic"
    private const val KEY_BLOCK_CLIPBOARD = "sec_guard_block_clipboard"
    private const val KEY_BLOCK_HIDDEN_POPUPS = "sec_guard_block_hidden_popups"
    private const val KEY_TRUSTED_HOSTS = "sec_guard_trusted_hosts"
    private const val KEY_MIGRATED_ENCRYPTED = "sec_guard_migrated_v2"

    private const val LEGACY_KEY_HTTPS_ONLY = "https_only_mode"
    private const val LEGACY_KEY_PHISHING_SHIELD = "phishing_protection_enabled"
    private const val LEGACY_KEY_BLOCK_CAM_MIC = "block_camera_mic_strict"
    private const val LEGACY_KEY_BLOCK_CLIPBOARD = "block_clipboard_hijack"
    private const val LEGACY_KEY_BLOCK_HIDDEN_POPUPS = "block_hidden_popups"
    private const val LEGACY_KEY_TRUSTED_HOSTS = "user_trusted_hosts"

    private val bypassedHostsSession = ConcurrentHashMap.newKeySet<String>()
    private val threatsBlockedCounter = AtomicInteger(0)
    private val httpsUpgradesCounter = AtomicInteger(0)
    private val clipboardBlocksCounter = AtomicInteger(0)

    private val CRITICAL_PROTECTED_DOMAINS = setOf(
        "accounts.google.com", "myaccount.google.com", "oauth2.googleapis.com",
        "login.microsoftonline.com", "login.live.com", "appleid.apple.com",
        "klikbca.com", "bca.co.id", "bankmandiri.co.id", "bri.co.id", "bni.co.id",
        "bsi.co.id", "cimbniaga.co.id", "permata.com", "jenius.com", "jago.com",
        "dana.id", "ovo.id", "gopay.co.id", "paypal.com", "stripe.com",
        "midtrans.com", "xendit.co", "doku.com",
        "challenges.cloudflare.com", "hcaptcha.com", "recaptcha.net"
    )

    private val TRUSTED_REGISTRABLE_DOMAINS = setOf(
        "google.com", "google.co.id", "youtube.com", "gmail.com", "gstatic.com",
        "googleapis.com", "github.com", "gitlab.com", "microsoft.com", "live.com",
        "office.com", "apple.com", "icloud.com", "amazon.com", "wikipedia.org",
        "reddit.com", "facebook.com", "instagram.com", "whatsapp.com", "x.com",
        "twitter.com", "linkedin.com", "netflix.com", "spotify.com", "discord.com",
        "cloudflare.com", "openai.com", "anthropic.com", "stackoverflow.com",
        // Trusted Indonesian Banking & E-Commerce Official Domains
        "klikbca.com", "bca.co.id", "bankmandiri.co.id", "bri.co.id", "bni.co.id",
        "bsi.co.id", "cimbniaga.co.id", "permata.com", "jenius.com", "jago.com",
        "tokopedia.com", "shopee.co.id", "bukalapak.com", "blibli.com", "traveloka.com",
        "tiket.com", "gojek.com", "grab.com", "dana.id", "ovo.id", "gopay.co.id",
        "midtrans.com", "xendit.co", "doku.com", "kompas.com", "detik.com", "cnnindonesia.com"
    )

    private val KNOWN_PHISHING_AND_MALWARE_DOMAINS = setOf(
        "malware-domain.com", "fake-virus-alert.com", "pckgupdate.com",
        "secure-login-verify.xyz", "account-update-security.top",
        "verify-wallet-connect.xyz", "free-dana-kaget-claim.xyz",
        "claim-saldo-dana-gratis.site", "undian-berhadiah-resmi.xyz",
        "tarif-bank-perubahan.xyz", "konfirmasi-tarif-transaksi.site",
        "formulir-perubahan-tarif.click", "aktifkan-autodebet-resmi.top"
    )

    private val BRAND_IMPERSONATION_TARGETS = listOf(
        "klikbca" to "bca.co.id",
        "mybca" to "bca.co.id",
        "livinmandiri" to "bankmandiri.co.id",
        "brimo" to "bri.co.id",
        "wondrbni" to "bni.co.id",
        "danakaget" to "dana.id",
        "paypalsecure" to "paypal.com",
        "googleaccounts" to "google.com",
        "steamcommunnity" to "steamcommunity.com",
        "discrod" to "discord.com",
        "faceb00k" to "facebook.com"
    )

    private val HIGH_RISK_TLDS = setOf(
        ".xyz", ".top", ".click", ".loan", ".work", ".gq", ".tk", ".ml", ".cf", ".ga", ".zip", ".mov"
    )

    private val DECEPTIVE_AUTH_KEYWORDS = listOf(
        "login-", "-login", "signin-", "verify-", "verification-", "update-account",
        "secure-banking", "perubahan-tarif", "saldo-gratis", "dana-kaget", "klaim-hadiah",
        "wallet-restore", "seed-phrase"
    )

    private fun getSecurePrefs(context: Context): android.content.SharedPreferences {
        val encPrefs = EncryptedPreferencesManager.getPreferences(context)
        if (!encPrefs.getBoolean(KEY_MIGRATED_ENCRYPTED, false)) {
            try {
                val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = encPrefs.edit()
                if (legacy.contains(LEGACY_KEY_HTTPS_ONLY)) {
                    editor.putBoolean(KEY_HTTPS_ONLY, legacy.getBoolean(LEGACY_KEY_HTTPS_ONLY, true))
                }
                if (legacy.contains(LEGACY_KEY_PHISHING_SHIELD)) {
                    editor.putBoolean(KEY_PHISHING_SHIELD, legacy.getBoolean(LEGACY_KEY_PHISHING_SHIELD, true))
                }
                if (legacy.contains(LEGACY_KEY_BLOCK_CAM_MIC)) {
                    editor.putBoolean(KEY_BLOCK_CAM_MIC, legacy.getBoolean(LEGACY_KEY_BLOCK_CAM_MIC, false))
                }
                if (legacy.contains(LEGACY_KEY_BLOCK_CLIPBOARD)) {
                    editor.putBoolean(KEY_BLOCK_CLIPBOARD, legacy.getBoolean(LEGACY_KEY_BLOCK_CLIPBOARD, true))
                }
                if (legacy.contains(LEGACY_KEY_BLOCK_HIDDEN_POPUPS)) {
                    editor.putBoolean(KEY_BLOCK_HIDDEN_POPUPS, legacy.getBoolean(LEGACY_KEY_BLOCK_HIDDEN_POPUPS, true))
                }
                val legacyTrusted = legacy.getStringSet(LEGACY_KEY_TRUSTED_HOSTS, null)
                if (!legacyTrusted.isNullOrEmpty()) {
                    editor.putStringSet(KEY_TRUSTED_HOSTS, legacyTrusted)
                }
                editor.putBoolean(KEY_MIGRATED_ENCRYPTED, true).apply()
                legacy.edit().clear().apply()
            } catch (_: Exception) {}
        }
        return encPrefs
    }

    fun getThreatsBlockedCount(): Int = threatsBlockedCounter.get()
    fun getHttpsUpgradesCount(): Int = httpsUpgradesCounter.get()
    fun getClipboardBlocksCount(): Int = clipboardBlocksCounter.get()
    fun incrementClipboardBlocked() { clipboardBlocksCounter.incrementAndGet() }

    fun isHttpsOnlyEnabled(context: Context): Boolean =
        getSecurePrefs(context).getBoolean(KEY_HTTPS_ONLY, true)

    fun setHttpsOnlyEnabled(context: Context, enabled: Boolean) {
        getSecurePrefs(context).edit().putBoolean(KEY_HTTPS_ONLY, enabled).apply()
    }

    fun isPhishingProtectionEnabled(context: Context): Boolean =
        getSecurePrefs(context).getBoolean(KEY_PHISHING_SHIELD, true)

    fun setPhishingProtectionEnabled(context: Context, enabled: Boolean) {
        getSecurePrefs(context).edit().putBoolean(KEY_PHISHING_SHIELD, enabled).apply()
    }

    fun isBlockCameraMicStrict(context: Context): Boolean =
        getSecurePrefs(context).getBoolean(KEY_BLOCK_CAM_MIC, false)

    fun setBlockCameraMicStrict(context: Context, enabled: Boolean) {
        getSecurePrefs(context).edit().putBoolean(KEY_BLOCK_CAM_MIC, enabled).apply()
    }

    fun isBlockClipboardHijack(context: Context): Boolean =
        getSecurePrefs(context).getBoolean(KEY_BLOCK_CLIPBOARD, true)

    fun setBlockClipboardHijack(context: Context, enabled: Boolean) {
        getSecurePrefs(context).edit().putBoolean(KEY_BLOCK_CLIPBOARD, enabled).apply()
    }

    fun isBlockHiddenPopups(context: Context): Boolean =
        getSecurePrefs(context).getBoolean(KEY_BLOCK_HIDDEN_POPUPS, true)

    fun setBlockHiddenPopups(context: Context, enabled: Boolean) {
        getSecurePrefs(context).edit().putBoolean(KEY_BLOCK_HIDDEN_POPUPS, enabled).apply()
    }

    fun allowHostTemporarily(context: Context, host: String, persist: Boolean = false) {
        val clean = normalizeHost(host)
        if (clean.isNotEmpty()) {
            bypassedHostsSession.add(clean)
            if (persist) {
                val prefs = getSecurePrefs(context)
                val current = prefs.getStringSet(KEY_TRUSTED_HOSTS, emptySet())?.toMutableSet() ?: mutableSetOf()
                current.add(clean)
                prefs.edit().putStringSet(KEY_TRUSTED_HOSTS, current).apply()
            }
        }
    }

    fun clearTemporaryBypasses() {
        bypassedHostsSession.clear()
    }

    fun isHostUserTrusted(context: Context, host: String): Boolean {
        val clean = normalizeHost(host)
        if (clean.isEmpty()) return false
        if (bypassedHostsSession.contains(clean)) return true
        val saved = getSecurePrefs(context).getStringSet(KEY_TRUSTED_HOSTS, emptySet()) ?: emptySet()
        return saved.contains(clean)
    }

    fun isCriticalProtectedHost(hostOrUrl: String?): Boolean {
        if (hostOrUrl.isNullOrBlank()) return false
        val trimmed = hostOrUrl.trim()
        val host = if (trimmed.contains("://")) extractHost(trimmed) else normalizeHost(trimmed)
        if (host.isEmpty()) return false
        return CRITICAL_PROTECTED_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    fun upgradeUrlIfHttpsOnly(url: String, httpsOnlyEnabled: Boolean): String {
        val trimmed = url.trim()
        if (!httpsOnlyEnabled || !trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        val host = extractHost(trimmed)
        if (isLocalOrPrivateAddress(host)) {
            return trimmed
        }
        httpsUpgradesCounter.incrementAndGet()
        return "https://" + trimmed.substring(7)
    }

    fun inspectUrlThreat(
        url: String,
        httpsOnlyEnabled: Boolean,
        phishingProtectionEnabled: Boolean,
        isUserTrustedHost: Boolean = false
    ): SecurityThreatAssessment {
        if (url.isBlank() || url == "about:blank") {
            return SecurityThreatAssessment(url, "", ThreatSeverity.SAFE)
        }

        val host = extractHost(url)
        if (host.isEmpty() || isUserTrustedHost || isOfficialTrustedDomain(host)) {
            return SecurityThreatAssessment(url, host, ThreatSeverity.SAFE)
        }

        if (phishingProtectionEnabled) {
            // 1. Known phishing & malware domain list
            if (KNOWN_PHISHING_AND_MALWARE_DOMAINS.any { host == it || host.endsWith(".$it") }) {
                threatsBlockedCounter.incrementAndGet()
                return SecurityThreatAssessment(
                    url = url,
                    host = host,
                    severity = ThreatSeverity.MALWARE_OR_SCAM,
                    title = "Situs Berbahaya Diblokir",
                    reason = "Domain '$host' terdaftar dalam basis data situs berbahaya yang menyebarkan phishing, scam, atau malware.",
                    recommendation = "Jangan pernah memasukkan kata sandi, PIN, kode OTP, atau mengunduh aplikasi (.apk) dari situs ini."
                )
            }

            // 2. IDN Homograph / Punycode Spoofing (selective homograph & brand-spoof detection)
            if (isSuspiciousIdnHomograph(host)) {
                threatsBlockedCounter.incrementAndGet()
                return SecurityThreatAssessment(
                    url = url,
                    host = host,
                    severity = ThreatSeverity.SUSPICIOUS_PHISHING,
                    title = "Terdeteksi Penyamaran Domain (IDN Homograph)",
                    reason = "Situs '$host' menggunakan kombinasi karakter lintas aksara atau homograf (Punycode xn--) yang meniru tampilan alamat situs resmi.",
                    recommendation = "Kembali ke halaman aman kecuali Anda benar-benar mengenali domain ini."
                )
            }

            // 3. Brand Impersonation / Typosquatting Check with strict dot-boundary domain verification
            val compactHost = host.replace("-", "").replace(".", "")
            for ((spoofKeyword, officialDomain) in BRAND_IMPERSONATION_TARGETS) {
                if (compactHost.contains(spoofKeyword) && !isSameOrSubdomainOf(host, officialDomain)) {
                    threatsBlockedCounter.incrementAndGet()
                    return SecurityThreatAssessment(
                        url = url,
                        host = host,
                        severity = ThreatSeverity.SUSPICIOUS_PHISHING,
                        title = "Peringatan Situs Tiruan (Phishing)",
                        reason = "Situs '$host' terdeteksi meniru layanan resmi ($officialDomain) untuk mencuri kredensial akun atau perbankan Anda.",
                        recommendation = "Pastikan Anda hanya membuka domain resmi '$officialDomain'."
                    )
                }
            }

            // 4. High-risk TLD combined with deceptive auth/banking/giveaway keywords
            val hasHighRiskTld = HIGH_RISK_TLDS.any { host.endsWith(it) }
            val lowerUrl = url.lowercase(Locale.US)
            val matchedKeyword = DECEPTIVE_AUTH_KEYWORDS.firstOrNull { lowerUrl.contains(it) }
            if (hasHighRiskTld && matchedKeyword != null) {
                threatsBlockedCounter.incrementAndGet()
                return SecurityThreatAssessment(
                    url = url,
                    host = host,
                    severity = ThreatSeverity.SUSPICIOUS_PHISHING,
                    title = "Situs Mencurigakan Terdeteksi",
                    reason = "Situs '$host' menggunakan ekstensi domain berisiko tinggi dengan pola jebakan '$matchedKeyword'.",
                    recommendation = "Hindari mengisi formulir login, nomor kartu, atau kode OTP di halaman ini."
                )
            }
        }

        // 5. Strict HTTPS-Only warning if connection is still plain HTTP on non-local host
        if (httpsOnlyEnabled && url.startsWith("http://", ignoreCase = true) && !isLocalOrPrivateAddress(host)) {
            return SecurityThreatAssessment(
                url = url,
                host = host,
                severity = ThreatSeverity.HTTP_INSECURE,
                title = "Koneksi Tidak Aman (HTTP)",
                reason = "Situs '$host' tidak mendukung enkripsi HTTPS. Informasi yang Anda kirim atau lihat dapat disadap oleh pihak ketiga di jaringan.",
                recommendation = "Disarankan kembali ke halaman aman."
            )
        }

        return SecurityThreatAssessment(url, host, ThreatSeverity.SAFE)
    }

    fun isSameOrSubdomainOf(host: String, officialDomain: String): Boolean {
        val cleanHost = normalizeHost(host).removeSurrounding("[", "]")
        val cleanOfficial = normalizeHost(officialDomain).removeSurrounding("[", "]")
        if (cleanHost.isEmpty() || cleanOfficial.isEmpty()) return false
        return cleanHost == cleanOfficial || cleanHost.endsWith(".$cleanOfficial")
    }

    private val CONFUSABLE_HOMOGLYPH_MAP = mapOf(
        // Cyrillic homoglyphs
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'х' to 'x',
        'у' to 'y', 'і' to 'i', 'ї' to 'i', 'ј' to 'j', 'к' to 'k', 'м' to 'm',
        'н' to 'h', 'т' to 't', 'в' to 'b', 'ѕ' to 's', 'ґ' to 'g', 'ӏ' to 'l',
        // Greek homoglyphs
        'α' to 'a', 'β' to 'b', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v',
        'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'χ' to 'x',
        // Latin lookalikes
        'ɑ' to 'a', 'ɡ' to 'g', 'ı' to 'i', 'ł' to 'l', 'ø' to 'o'
    )

    private val PROTECTED_BRAND_ROOTS = setOf(
        "google", "youtube", "gmail", "apple", "icloud", "microsoft", "office", "live",
        "paypal", "amazon", "facebook", "instagram", "whatsapp", "telegram", "discord",
        "github", "gitlab", "netflix", "spotify", "binance", "coinbase", "metamask",
        "klikbca", "mybca", "bca", "bankmandiri", "livin", "bri", "brimo", "bni",
        "cimbniaga", "jenius", "jago", "dana", "ovo", "gopay", "gojek", "grab",
        "shopee", "shopeepay", "tokopedia", "bukalapak", "blibli", "traveloka"
    )

    fun isSuspiciousIdnHomograph(host: String): Boolean {
        val clean = normalizeHost(host).removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        val hasPunycode = clean.contains("xn--")
        val hasNonAscii = clean.any { it.code > 127 }
        if (!hasPunycode && !hasNonAscii) return false

        val unicodeHost = try {
            java.net.IDN.toUnicode(clean, java.net.IDN.ALLOW_UNASSIGNED).lowercase(Locale.US)
        } catch (_: Exception) {
            return true
        }

        // If IDN decoding failed to decode an xn-- label, treat as malformed/suspicious punycode
        if (hasPunycode && unicodeHost == clean) return true

        val labels = unicodeHost.split('.')
        for (label in labels) {
            if (label.isEmpty()) continue
            var hasAsciiLetter = false
            var hasCyrillicOrGreek = false
            var hasConfusableHomoglyph = false

            for (ch in label) {
                if (ch in 'a'..'z') hasAsciiLetter = true
                val block = Character.UnicodeBlock.of(ch)
                if (block == Character.UnicodeBlock.CYRILLIC ||
                    block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY ||
                    block == Character.UnicodeBlock.GREEK ||
                    block == Character.UnicodeBlock.GREEK_EXTENDED
                ) {
                    hasCyrillicOrGreek = true
                }
                if (CONFUSABLE_HOMOGLYPH_MAP.containsKey(ch)) {
                    hasConfusableHomoglyph = true
                }
            }

            // Mixed ASCII + Cyrillic/Greek or confusable lookalikes within the same label is a classic IDN homograph attack
            if (hasAsciiLetter && (hasCyrillicOrGreek || hasConfusableHomoglyph)) {
                return true
            }
        }

        // Build ASCII skeleton from confusable homoglyphs and check if it impersonates a protected brand
        var usedConfusable = false
        val skeletonBuilder = StringBuilder(unicodeHost.length)
        for (ch in unicodeHost) {
            val mapped = CONFUSABLE_HOMOGLYPH_MAP[ch]
            if (mapped != null) {
                usedConfusable = true
                skeletonBuilder.append(mapped)
            } else {
                skeletonBuilder.append(ch)
            }
        }
        val skeletonHost = skeletonBuilder.toString()
        val compactSkeleton = skeletonHost.replace("-", "").replace(".", "")

        if (usedConfusable && PROTECTED_BRAND_ROOTS.any { compactSkeleton.contains(it) }) {
            return true
        }
        for ((spoofKeyword, officialDomain) in BRAND_IMPERSONATION_TARGETS) {
            if (compactSkeleton.contains(spoofKeyword) && !isSameOrSubdomainOf(clean, officialDomain)) {
                return true
            }
        }

        // Punycode combined with high-risk TLD or deceptive auth keyword
        if (HIGH_RISK_TLDS.any { clean.endsWith(it) } ||
            DECEPTIVE_AUTH_KEYWORDS.any { unicodeHost.contains(it) || clean.contains(it) }
        ) {
            return true
        }

        return false
    }

    fun buildClientSecurityScript(
        blockClipboardHijack: Boolean,
        blockHiddenPopups: Boolean
    ): String = """
        (function() {
            if (window.__ChromeSecurityGuardInjected) return;
            window.__ChromeSecurityGuardInjected = true;

            // 1. Neutralize reverse-tabnabbing
            try {
                if (window.opener) window.opener = null;
            } catch (e) {}

            // 2. Block fake virus-alert vibration spam
            try {
                if (navigator.vibrate) {
                    navigator.vibrate = function() { return false; };
                }
            } catch (e) {}

            // 3. Track genuine user activation for clipboard & popup protection
            var lastUserInteraction = 0;
            ['pointerdown', 'mousedown', 'touchend', 'keydown', 'click'].forEach(function(ev) {
                window.addEventListener(ev, function(e) {
                    if (e && e.isTrusted) lastUserInteraction = Date.now();
                }, true);
            });

            function hasRecentUserGesture() {
                return (Date.now() - lastUserInteraction) < 1500;
            }

            // 4. Clipboard Hijack & Silent Read Protection
            if ($blockClipboardHijack) {
                try {
                    if (navigator.clipboard) {
                        navigator.clipboard.readText = function() {
                            return Promise.reject(new DOMException('Clipboard read blocked by Chrome Security Shield', 'NotAllowedError'));
                        };
                        navigator.clipboard.read = function() {
                            return Promise.reject(new DOMException('Clipboard read blocked by Chrome Security Shield', 'NotAllowedError'));
                        };
                        var origWriteText = navigator.clipboard.writeText ? navigator.clipboard.writeText.bind(navigator.clipboard) : null;
                        if (origWriteText) {
                            navigator.clipboard.writeText = function(text) {
                                if (!hasRecentUserGesture()) {
                                    console.warn('[SecurityShield] Blocked silent clipboard write');
                                    return Promise.resolve();
                                }
                                return origWriteText(text);
                            };
                        }
                    }
                    var origExecCommand = document.execCommand ? document.execCommand.bind(document) : null;
                    if (origExecCommand) {
                        document.execCommand = function(cmd, showUI, val) {
                            var c = String(cmd || '').toLowerCase();
                            if ((c === 'copy' || c === 'cut' || c === 'paste') && !hasRecentUserGesture()) {
                                console.warn('[SecurityShield] Blocked background execCommand: ' + c);
                                return false;
                            }
                            return origExecCommand(cmd, showUI, val);
                        };
                    }
                } catch (e) {}
            }

            // 5. Block stealth background window.open & target="_blank" opener leaks
            if ($blockHiddenPopups) {
                try {
                    var origOpen = window.open;
                    window.open = function(url, target, features) {
                        if (!hasRecentUserGesture()) {
                            console.warn('[SecurityShield] Blocked background popup: ' + url);
                            return null;
                        }
                        var win = origOpen ? origOpen.apply(this, arguments) : null;
                        try { if (win) win.opener = null; } catch (e) {}
                        return win;
                    };
                } catch (e) {}
            }
        })();
    """.trimIndent()

    fun injectClientSecurityScript(
        webView: WebView?,
        blockClipboardHijack: Boolean,
        blockHiddenPopups: Boolean
    ) {
        if (webView == null) return
        try {
            webView.evaluateJavascript(
                buildClientSecurityScript(blockClipboardHijack, blockHiddenPopups),
                null
            )
        } catch (_: Exception) {}
    }

    private val AFP_BYPASS_DOMAINS = setOf(
        "google.com",
        "google.co.id",
        "discord.com",
        "discordapp.com",
        "zoom.us"
    )

    fun isAfpBypassDomain(host: String): Boolean {
        val clean = normalizeHost(host)
        if (clean.isEmpty()) return false
        return AFP_BYPASS_DOMAINS.any { domain -> isSameOrSubdomainOf(clean, domain) }
    }

    fun buildIncognitoStorageIsolationScript(): String = """
        (function() {
            if (window.__asIncognitoStorageIsolated) return;
            window.__asIncognitoStorageIsolated = true;
            function createMemoryStorage() {
                var store = Object.create(null);
                return {
                    getItem: function(k) {
                        var key = String(k);
                        return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null;
                    },
                    setItem: function(k, v) {
                        store[String(k)] = String(v);
                    },
                    removeItem: function(k) {
                        delete store[String(k)];
                    },
                    clear: function() {
                        store = Object.create(null);
                    },
                    key: function(i) {
                        var keys = Object.keys(store);
                        return (i >= 0 && i < keys.length) ? keys[i] : null;
                    },
                    get length() {
                        return Object.keys(store).length;
                    }
                };
            }
            try {
                var testLs = window.localStorage;
                if (!testLs) throw new Error('localStorage disabled');
            } catch (e) {
                try {
                    var memLocal = createMemoryStorage();
                    var memSession = createMemoryStorage();
                    Object.defineProperty(window, 'localStorage', { get: function() { return memLocal; }, configurable: true });
                    Object.defineProperty(window, 'sessionStorage', { get: function() { return memSession; }, configurable: true });
                } catch (err) {}
            }
        })();
    """.trimIndent()

    fun buildAntiFingerprintingScript(
        isDesktopMode: Boolean,
        isStrictMode: Boolean
    ): String {
        val targetPlatform = if (isDesktopMode) "Linux x86_64" else "Linux armv81"
        val standardAfp = """
            (function() {
                if (!window.__asStandardAfpInstalled) {
                    window.__asStandardAfpInstalled = true;
                    try {
                        Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 8; }, configurable: true });
                        Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 4; }, configurable: true });
                        Object.defineProperty(navigator, 'platform', { get: function() { return '$targetPlatform'; }, configurable: true });
                    } catch(e) {}
                }
            })();
        """.trimIndent()
        if (!isStrictMode) return standardAfp

        val strictAfp = """
            (function() {
                if (window.__asStrictAfpInstalled) return;
                window.__asStrictAfpInstalled = true;
                var hostSeed = (window.location && window.location.hostname ? window.location.hostname.length : 7) % 3 + 1;
                var origGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = function(type, contextAttributes) {
                    var ctx = origGetContext.call(this, type, contextAttributes);
                    if (ctx && type === '2d' && !ctx.__asPatched) {
                        ctx.__asPatched = true;
                        var origGetImageData = ctx.getImageData;
                        ctx.getImageData = function(x, y, w, h) {
                            var data = origGetImageData.call(this, x, y, w, h);
                            for (var i = 0; i < data.data.length; i += 16) {
                                data.data[i] = (data.data[i] ^ hostSeed) & 0xFF;
                            }
                            return data;
                        };
                    }
                    return ctx;
                };
                if (window.AudioContext || window.webkitAudioContext) {
                    var ac = window.AudioContext || window.webkitAudioContext;
                    ac.prototype.createOscillator = function() {
                        return { connect: function() {}, start: function() {}, stop: function() {}, frequency: { value: 440, setValueAtTime: function() {} }, type: 'sine' };
                    };
                }
            })();
        """.trimIndent()
        return "$standardAfp\n$strictAfp"
    }

    private fun isOfficialTrustedDomain(host: String): Boolean {
        val clean = normalizeHost(host)
        return TRUSTED_REGISTRABLE_DOMAINS.any { isSameOrSubdomainOf(clean, it) }
    }

    fun isIpv4Literal(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]").trimEnd('.')
        val parts = clean.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return false
            if (part.length > 1 && part.startsWith('0')) return false
            val num = part.toIntOrNull() ?: return false
            if (num !in 0..255) return false
        }
        return true
    }

    fun isIpv6Literal(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        return clean.contains(':')
    }

    fun isLocalOrPrivateAddress(host: String): Boolean {
        val clean = normalizeHost(host).removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        if (clean == "localhost" ||
            clean.endsWith(".localhost") ||
            clean.endsWith(".local") ||
            clean.endsWith(".internal") ||
            clean.endsWith(".localdomain")
        ) {
            return true
        }

        // Reject single-integer IP (e.g. 2130706433) or hex IP (0x7f000001) or octal-dotted IP (0177.0.0.1)
        if (clean.all { it.isDigit() } || clean.startsWith("0x")) {
            return true
        }
        val dotParts = clean.split('.')
        if (dotParts.size == 4 && dotParts.all { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch in 'a'..'f' || ch == 'x' } }) {
            if (!isIpv4Literal(clean)) {
                // 4 numeric/hex/octal parts that fail strict decimal IPv4 validation (e.g. 0177.0.0.1)
                return true
            }
        }

        // Check IPv6 literals ONLY when the host actually contains ':'
        if (isIpv6Literal(clean)) {
            val noZone = clean.substringBefore('%')
            if (noZone == "::1" ||
                noZone == "0:0:0:0:0:0:0:1" ||
                noZone == "::" ||
                noZone == "0:0:0:0:0:0:0:0" ||
                noZone.startsWith("fe8") ||
                noZone.startsWith("fe9") ||
                noZone.startsWith("fea") ||
                noZone.startsWith("feb") ||
                noZone.startsWith("fc") ||
                noZone.startsWith("fd") ||
                noZone.startsWith("ff")
            ) {
                return true
            }
            if (noZone.startsWith("::ffff:")) {
                val mappedIpv4 = noZone.removePrefix("::ffff:")
                if (isLocalOrPrivateAddress(mappedIpv4)) return true
            }
            return try {
                val addr = java.net.InetAddress.getByName(noZone)
                FilterListManager.isRestrictedAddress(addr)
            } catch (_: Exception) {
                true
            }
        }

        // Check IPv4 private/reserved ranges ONLY when the host is a 4-octet decimal IPv4 literal
        if (isIpv4Literal(clean)) {
            val octets = dotParts.map { it.toInt() }
            val b0 = octets[0]
            val b1 = octets[1]
            if (b0 == 10) return true
            if (b0 == 127) return true
            if (b0 == 0 || b0 >= 224) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
        }

        return false
    }

    private val DANGEROUS_INTERNAL_SCHEMES = setOf(
        "javascript", "vbscript", "data", "file", "content", "blob", "filesystem", "jar", "chrome",
        "chrome-extension", "view-source", "res", "android.resource", "wyciwyg", "ms-appx", "ms-appx-web"
    )

    private val NON_EXTERNAL_WEB_OR_RESERVED_SCHEMES = setOf(
        "http", "https", "ws", "wss", "ftp", "ftps", "about"
    )

    private val VALID_RFC3986_SCHEME_REGEX = Regex("^[a-z][a-z0-9+.-]{0,63}$")

    fun isAllowedExternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (trimmed.length > 2048 || trimmed.any { it.isISOControl() }) return false
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return false
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.US)
        if (!VALID_RFC3986_SCHEME_REGEX.matches(scheme)) return false
        if (scheme in DANGEROUS_INTERNAL_SCHEMES || scheme in NON_EXTERNAL_WEB_OR_RESERVED_SCHEMES) {
            return false
        }
        if (scheme == "intent") {
            return try {
                val parsed = android.content.Intent.parseUri(trimmed, android.content.Intent.URI_INTENT_SCHEME)
                val embeddedScheme = parsed.data?.scheme?.lowercase(Locale.US)
                embeddedScheme == null || (embeddedScheme !in DANGEROUS_INTERNAL_SCHEMES && embeddedScheme != "about")
            } catch (_: Exception) {
                false
            }
        }
        return true
    }

    fun isDangerousInternalScheme(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) return false
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.US)
        if (scheme == "about") {
            return !trimmed.equals("about:blank", ignoreCase = true)
        }
        return scheme in DANGEROUS_INTERNAL_SCHEMES
    }

    fun isSafeBlobUrlForContext(blobUrl: String?, activePageUrl: String?): Boolean {
        if (blobUrl.isNullOrBlank() || activePageUrl.isNullOrBlank()) return false
        val trimmedBlob = blobUrl.trim()
        val trimmedPage = activePageUrl.trim()
        if (trimmedBlob.length > 2048 || trimmedPage.length > 2048) return false
        if (trimmedBlob.any { it.isISOControl() } || trimmedPage.any { it.isISOControl() }) return false
        if (!trimmedBlob.startsWith("blob:", ignoreCase = true)) return false

        val innerOriginUrl = trimmedBlob.substring(5).trim()
        if (!innerOriginUrl.startsWith("https://", ignoreCase = true)) return false
        if (!trimmedPage.startsWith("https://", ignoreCase = true)) return false
        if (!isSafeWebNavigationUrl(innerOriginUrl) || !isSafeWebNavigationUrl(trimmedPage)) return false

        return try {
            val blobInnerUri = Uri.parse(innerOriginUrl)
            val pageUri = Uri.parse(trimmedPage)

            val blobHost = normalizeHost(blobInnerUri.host.orEmpty())
            val pageHost = normalizeHost(pageUri.host.orEmpty())
            if (blobHost.isEmpty() || pageHost.isEmpty()) return false
            if (blobHost != pageHost) return false
            if (isLocalOrPrivateAddress(blobHost)) return false

            val blobPort = if (blobInnerUri.port != -1) blobInnerUri.port else 443
            val pagePort = if (pageUri.port != -1) pageUri.port else 443
            blobPort == pagePort
        } catch (_: Exception) {
            false
        }
    }

    fun buildSafeExternalIntent(context: Context, rawUrl: String): android.content.Intent? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        if (!isAllowedExternalScheme(trimmed)) return null

        return try {
            val parsed = if (trimmed.startsWith("intent://", ignoreCase = true)) {
                android.content.Intent.parseUri(trimmed, android.content.Intent.URI_INTENT_SCHEME)
            } else {
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(trimmed))
            }

            // Force ACTION_VIEW only — block arbitrary actions like ACTION_DELETE, ACTION_CALL, ACTION_SEND, or custom actions
            parsed.action = android.content.Intent.ACTION_VIEW
            parsed.addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            parsed.component = null
            parsed.selector = null
            parsed.clipData = null

            // Strip all URI permission flags and dangerous activity launch flags
            val forbiddenFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            parsed.flags = parsed.flags and forbiddenFlags.inv()

            // Block targeting our own application package (prevents internal loop / confused deputy)
            val targetPkg = parsed.`package`
            if (!targetPkg.isNullOrBlank() && targetPkg.equals(context.packageName, ignoreCase = true)) {
                return null
            }

            // Validate embedded data URI scheme inside parsed intent://
            val dataUri = parsed.data
            if (dataUri != null) {
                val dataScheme = dataUri.scheme?.lowercase(Locale.US).orEmpty()
                if (dataScheme.isNotEmpty() && (
                        !VALID_RFC3986_SCHEME_REGEX.matches(dataScheme) ||
                            dataScheme in DANGEROUS_INTERNAL_SCHEMES ||
                            dataScheme == "about" ||
                            dataScheme == "intent"
                        )
                ) {
                    return null
                }
            }

            parsed
        } catch (_: Exception) {
            null
        }
    }

    fun extractSafeFallbackUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("intent://", ignoreCase = true) || trimmed.length > 2048) return null
        return try {
            val parsed = android.content.Intent.parseUri(trimmed, android.content.Intent.URI_INTENT_SCHEME)
            val fallback = parsed.getStringExtra("browser_fallback_url")?.trim() ?: return null
            if (fallback.isEmpty() || fallback.length > 2048) return null
            if (!fallback.startsWith("https://", ignoreCase = true) &&
                !fallback.startsWith("http://", ignoreCase = true)
            ) {
                return null
            }
            val host = extractHost(fallback)
            if (host.isEmpty() || isLocalOrPrivateAddress(host)) return null
            fallback
        } catch (_: Exception) {
            null
        }
    }

    fun isSafeWebNavigationUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (trimmed.length > 4096) return false
        if (trimmed.any { it.isISOControl() }) return false
        if (isDangerousInternalScheme(trimmed)) return false
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)
    }

    fun isSafeFileChooserResultUri(
        context: Context,
        uri: Uri?,
        allowedCameraUri: Uri? = null
    ): Boolean {
        if (uri == null) return false
        if (allowedCameraUri != null && uri == allowedCameraUri) return true

        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "content") return false

        val authority = uri.authority?.lowercase(Locale.US)?.trim().orEmpty()
        if (authority.isEmpty()) return false

        val pkgLower = context.packageName.lowercase(Locale.US)
        if (authority == "$pkgLower.fileprovider" ||
            authority == "$pkgLower.renderconfig" ||
            authority == pkgLower ||
            authority.startsWith("$pkgLower.")
        ) {
            return false
        }

        val rawPath = uri.path?.lowercase(Locale.US).orEmpty()
        val encodedPath = uri.encodedPath?.lowercase(Locale.US).orEmpty()
        val decodedEncodedPath = try {
            Uri.decode(encodedPath).lowercase(Locale.US)
        } catch (_: Exception) {
            encodedPath
        }

        val candidatePaths = listOf(rawPath, encodedPath, decodedEncodedPath)
        for (p in candidatePaths) {
            if (p.contains("..") ||
                p.contains("%2e%2e") ||
                p.contains("/data/data/$pkgLower") ||
                p.contains("/data/user/") && p.contains("/$pkgLower") ||
                p.contains("/$pkgLower/shared_prefs") ||
                p.contains("/$pkgLower/databases") ||
                p.contains("/$pkgLower/app_webview") ||
                p.contains("/$pkgLower/files") ||
                p.contains("/$pkgLower/cache")
            ) {
                return false
            }
        }

        return true
    }

    fun buildWebRtcProtectionScript(
        preventWebRtcLeak: Boolean,
        strictWebRtcMode: Boolean
    ): String {
        if (!preventWebRtcLeak) return ""
        return if (strictWebRtcMode) {
            """
                (function() {
                    if (window.__AlexWebRtcStrictInstalled) return;
                    window.__AlexWebRtcStrictInstalled = true;
                    var blockedCtor = function() {
                        try {
                            if (window.AlexBrowserNative && window.AlexBrowserNative.onWebRtcBlocked) {
                                window.AlexBrowserNative.onWebRtcBlocked(window.location.href);
                            }
                        } catch (e) {}
                        throw new DOMException('WebRTC connection blocked by AlexBrowser Strict Privacy Mode.', 'NotAllowedError');
                    };
                    try {
                        Object.defineProperty(window, 'RTCPeerConnection', { value: blockedCtor, writable: false, configurable: false });
                    } catch (e) { window.RTCPeerConnection = blockedCtor; }
                    try {
                        Object.defineProperty(window, 'webkitRTCPeerConnection', { value: blockedCtor, writable: false, configurable: false });
                    } catch (e) { window.webkitRTCPeerConnection = blockedCtor; }
                })();
            """.trimIndent()
        } else {
            """
                (function() {
                    if (window.__AlexWebRtcCompatInstalled) return;
                    window.__AlexWebRtcCompatInstalled = true;
                    var OrigPeerConnection = window.RTCPeerConnection || window.webkitRTCPeerConnection;
                    if (!OrigPeerConnection) return;

                    var privateIpRegex = /(192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|\b(?:fe80|fc[0-9a-f]{2}|fd[0-9a-f]{2}):[0-9a-f:]+|\b::1\b|\btyp\s+host\b)/i;

                    function scrubSdp(sdp) {
                        if (!sdp || typeof sdp !== 'string') return sdp;
                        return sdp.split(/\r?\n/).filter(function(line) {
                            var trimmed = line.trim();
                            if (trimmed.indexOf('a=candidate:') === 0) {
                                return !privateIpRegex.test(trimmed);
                            }
                            return true;
                        }).map(function(line) {
                            if (line.indexOf('c=IN IP4 ') === 0 || line.indexOf('c=IN IP6 ') === 0) {
                                if (privateIpRegex.test(line)) {
                                    return 'c=IN IP4 0.0.0.0';
                                }
                            }
                            return line;
                        }).join('\r\n');
                    }

                    function isPrivateCandidate(candObj) {
                        if (!candObj) return false;
                        var str = typeof candObj === 'string' ? candObj : (candObj.candidate || '');
                        var addr = candObj.address || candObj.ip || '';
                        var type = candObj.type || '';
                        return type === 'host' || privateIpRegex.test(str) || (addr && privateIpRegex.test(addr));
                    }

                    var WrappedRTCPeerConnection = function(config, constraints) {
                        var safeConfig = config ? Object.assign({}, config) : {};
                        var pc = new OrigPeerConnection(safeConfig, constraints);

                        var origSetLocalDescription = pc.setLocalDescription;
                        if (origSetLocalDescription) {
                            pc.setLocalDescription = function(desc) {
                                if (desc && desc.sdp) {
                                    desc = { type: desc.type, sdp: scrubSdp(desc.sdp) };
                                }
                                return origSetLocalDescription.call(this, desc);
                            };
                        }

                        var origAddIceCandidate = pc.addIceCandidate;
                        if (origAddIceCandidate) {
                            pc.addIceCandidate = function(candidate, successCb, failureCb) {
                                if (isPrivateCandidate(candidate)) {
                                    if (typeof successCb === 'function') successCb();
                                    return Promise.resolve();
                                }
                                return origAddIceCandidate.apply(this, arguments);
                            };
                        }

                        var origAddEventListener = pc.addEventListener;
                        if (origAddEventListener) {
                            pc.addEventListener = function(type, listener, options) {
                                if (type === 'icecandidate' && typeof listener === 'function') {
                                    var wrappedListener = function(event) {
                                        if (event && event.candidate && isPrivateCandidate(event.candidate)) {
                                            return;
                                        }
                                        return listener.call(this, event);
                                    };
                                    return origAddEventListener.call(this, type, wrappedListener, options);
                                }
                                return origAddEventListener.apply(this, arguments);
                            };
                        }

                        var userOnIceCandidate = null;
                        try {
                            Object.defineProperty(pc, 'onicecandidate', {
                                get: function() { return userOnIceCandidate; },
                                set: function(fn) {
                                    userOnIceCandidate = fn;
                                    origAddEventListener.call(pc, 'icecandidate', function(event) {
                                        if (event && event.candidate && isPrivateCandidate(event.candidate)) {
                                            return;
                                        }
                                        if (typeof userOnIceCandidate === 'function') {
                                            userOnIceCandidate.call(pc, event);
                                        }
                                    });
                                },
                                configurable: true
                            });
                        } catch (e) {}

                        return pc;
                    };

                    Object.setPrototypeOf(WrappedRTCPeerConnection, OrigPeerConnection);
                    WrappedRTCPeerConnection.prototype = OrigPeerConnection.prototype;
                    window.RTCPeerConnection = WrappedRTCPeerConnection;
                    window.webkitRTCPeerConnection = WrappedRTCPeerConnection;
                })();
            """.trimIndent()
        }
    }

    fun extractHost(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            normalizeHost(Uri.parse(url).host.orEmpty())
        } catch (_: Exception) {
            ""
        }
    }

    private fun normalizeHost(host: String): String =
        host.trim().lowercase(Locale.US).removeSurrounding("[", "]").trimEnd('.').removePrefix("www.")
}


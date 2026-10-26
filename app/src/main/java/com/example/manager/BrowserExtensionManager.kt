package com.example.manager

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ExtensionRunAt {
    DOCUMENT_START,
    DOCUMENT_END,
    MANUAL_ONLY
}

enum class ExtensionSourceType {
    BUILT_IN,
    EXTERNAL_FILE,
    EXTERNAL_URL,
    CUSTOM_CODE
}

data class BrowserExtension(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "User",
    val iconName: String = "Extension",
    val jsCode: String = "",
    val cssCode: String = "",
    val matches: List<String> = listOf("*"), // "*" for all sites, or domain list e.g. ["youtube.com", "google.com"]
    val runAt: ExtensionRunAt = ExtensionRunAt.DOCUMENT_END,
    val isEnabled: Boolean = true,
    val isPinnedToToolbar: Boolean = false,
    val allowInPrivate: Boolean = false,
    val sourceType: ExtensionSourceType = ExtensionSourceType.CUSTOM_CODE,
    val sourceUrl: String = ""
)

object BrowserExtensionManager {
    private const val TAG = "BrowserExtensionMgr"
    private const val PREFS_NAME = "chrome_extensions_prefs_v1"
    private const val KEY_EXTENSIONS_JSON = "installed_extensions_json_v1"
    private const val KEY_SEEDED_DEFAULTS = "seeded_default_extensions_v1"

    val BUILT_IN_EXTENSIONS: List<BrowserExtension> = listOf(
        BrowserExtension(
            id = "ext_fullscreen_kiwi_pro",
            name = "Full Screen Immersive Tool",
            version = "2.0.0",
            description = "Mode Layar Penuh (Full Screen) imersif untuk seluruh situs web dengan dukungan scroll penuh, deteksi latar otomatis, tombol keluar mengambang, dan prompt sentuh cerdas.",
            author = "Kiwi & Chrome Tools",
            iconName = "Fullscreen",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = CustomJsToolbarManager.UPGRADED_FULLSCREEN_SCRIPT
        ),
        BrowserExtension(
            id = "ext_dark_reader_pro",
            name = "Dark Reader Pro",
            version = "2.4.0",
            description = "Mode gelap pintar untuk seluruh situs web tanpa merusak warna gambar, video, atau elemen kanvas.",
            author = "Chrome Web Store",
            iconName = "DarkMode",
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = false,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            cssCode = """
                html {
                    background-color: #121212 !important;
                    filter: invert(0.92) hue-rotate(180deg) !important;
                }
                img, video, picture, canvas, iframe, svg, [style*="background-image"] {
                    filter: invert(1) hue-rotate(180deg) !important;
                }
            """.trimIndent(),
            jsCode = """
                (function() {
                    var st = document.getElementById('__ext_css_ext_dark_reader_pro');
                    if (st && window.__extManualTrigger) {
                        st.remove();
                        return 'Dark Reader Pro: Nonaktif di halaman ini';
                    }
                    return 'Dark Reader Pro: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_sponsor_skip",
            name = "SponsorBlock & Auto-Skip Video",
            version = "3.1.2",
            description = "Otomatis melewati iklan video, menekan tombol Skip Ad, dan mempercepat segmen iklan di YouTube & situs streaming.",
            author = "SponsorBlock Community",
            iconName = "FastForward",
            matches = listOf("youtube.com", "youtu.be", "*"),
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    if (window.__extSponsorSkipActive) return 'SponsorBlock sudah berjalan aktif';
                    window.__extSponsorSkipActive = true;
                    function checkSkip() {
                        try {
                            var skipSelectors = [
                                '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern',
                                '.ytp-skip-ad-button', '.videoAdUiSkipButton',
                                'button[class*="skip-ad" i]', 'div[class*="skip-button" i]'
                            ];
                            for (var i = 0; i < skipSelectors.length; i++) {
                                var btn = document.querySelector(skipSelectors[i]);
                                if (btn) { btn.click(); }
                            }
                            var closeOverlay = document.querySelector('.ytp-ad-overlay-close-button');
                            if (closeOverlay) closeOverlay.click();
                            var adShowing = document.querySelector('.ad-showing, .ytp-ad-player-overlay');
                            var vid = document.querySelector('video');
                            if (adShowing && vid && isFinite(vid.duration) && vid.duration > 0) {
                                vid.muted = true;
                                vid.currentTime = Math.max(vid.duration - 0.1, 0);
                            }
                        } catch (e) {}
                    }
                    setInterval(checkSkip, 450);
                    checkSkip();
                    return 'SponsorBlock & Auto-Skip: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_unblock_copy",
            name = "Allow Copy & Right-Click Unlocker",
            version = "1.8.0",
            description = "Membuka kunci seleksi teks, salin (copy-paste), dan menu klik kanan pada situs artikel atau tugas yang membatasinya.",
            author = "Web Freedom Tools",
            iconName = "LockOpen",
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            cssCode = """
                *, *::before, *::after {
                    -webkit-user-select: text !important;
                    user-select: text !important;
                }
            """.trimIndent(),
            jsCode = """
                (function() {
                    ['copy', 'cut', 'paste', 'contextmenu', 'selectstart', 'dragstart'].forEach(function(ev) {
                        window.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                        document.addEventListener(ev, function(e) { e.stopPropagation(); }, true);
                    });
                    if (document.body) {
                        document.body.oncopy = null;
                        document.body.oncontextmenu = null;
                        document.body.onselectstart = null;
                    }
                    return 'Allow Copy & Right-Click: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_video_controller",
            name = "Video Speed & PIP Controller",
            version = "2.1.0",
            description = "Menambahkan panel mengambang mini pada video HTML5 untuk mengatur kecepatan (0.5x - 3x) dan lompat 10 detik.",
            author = "Media Tools",
            iconName = "Speed",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var existing = document.getElementById('__ext_vid_ctrl_panel');
                    if (existing) { existing.remove(); return 'Panel Video Controller ditutup'; }
                    var v = document.querySelector('video');
                    if (!v) return 'Tidak ada video HTML5 di halaman ini';
                    var bar = document.createElement('div');
                    bar.id = '__ext_vid_ctrl_panel';
                    bar.style.cssText = 'position:fixed;bottom:76px;right:14px;z-index:2147483647;background:rgba(20,22,26,0.92);color:#fff;padding:6px 10px;border-radius:999px;display:flex;align-items:center;gap:8px;font-family:sans-serif;font-size:12px;box-shadow:0 4px 16px rgba(0,0,0,0.45);border:1px solid rgba(255,255,255,0.18);';
                    function mkBtn(txt, fn) {
                        var b = document.createElement('button');
                        b.textContent = txt;
                        b.style.cssText = 'background:#2b2f36;color:#8ab4f8;border:none;border-radius:99px;padding:4px 9px;font-size:12px;font-weight:bold;cursor:pointer;';
                        b.onclick = fn;
                        return b;
                    }
                    var lbl = document.createElement('span');
                    lbl.textContent = (v.playbackRate || 1).toFixed(2) + 'x';
                    lbl.style.cssText = 'font-weight:bold;min-width:36px;text-align:center;color:#fff;';
                    bar.appendChild(mkBtn('-0.25x', function() { v.playbackRate = Math.max(0.25, (v.playbackRate || 1) - 0.25); lbl.textContent = v.playbackRate.toFixed(2) + 'x'; }));
                    bar.appendChild(lbl);
                    bar.appendChild(mkBtn('+0.25x', function() { v.playbackRate = Math.min(4.0, (v.playbackRate || 1) + 0.25); lbl.textContent = v.playbackRate.toFixed(2) + 'x'; }));
                    bar.appendChild(mkBtn('2x', function() { v.playbackRate = v.playbackRate === 2 ? 1 : 2; lbl.textContent = v.playbackRate.toFixed(2) + 'x'; }));
                    bar.appendChild(mkBtn('✕', function() { bar.remove(); }));
                    document.body.appendChild(bar);
                    return 'Panel Video Speed ditampilkan';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_cookie_autodismiss",
            name = "I Don't Care About Cookies",
            version = "1.5.4",
            description = "Menyembunyikan spanduk persetujuan cookie (GDPR/CCPA) dan pop-up langganan newsletter yang menutupi layar.",
            author = "Privacy Community",
            iconName = "Cookie",
            runAt = ExtensionRunAt.DOCUMENT_END,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            cssCode = """
                #onetrust-consent-sdk, .onetrust-pc-dark-filter,
                #CybotCookiebotDialog, #cookie-law-info-bar,
                div[id*="cookie-banner" i], div[class*="cookie-banner" i],
                div[id*="cookie-consent" i], div[class*="cookie-consent" i],
                div[class*="gdpr-banner" i] {
                    display: none !important;
                }
            """.trimIndent(),
            jsCode = """
                (function() {
                    if (document.body) {
                        document.body.style.setProperty('overflow', 'auto', 'important');
                    }
                    return 'Cookie Banner Blocker: Aktif';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_volume_booster",
            name = "Volume Booster 200% (Audio Gain)",
            version = "1.3.0",
            description = "Meningkatkan volume suara video dan audio hingga 200% menggunakan Web Audio API GainNode.",
            author = "Audio Labs",
            iconName = "VolumeUp",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = true,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var media = document.querySelectorAll('video, audio');
                    if (!media.length) return 'Tidak ada video/audio di halaman ini';
                    window.__extAudioBoostLevel = window.__extAudioBoostLevel === 2.0 ? 1.0 : 2.0;
                    var gainVal = window.__extAudioBoostLevel;
                    media.forEach(function(el) {
                        try {
                            if (!el.__extGainNode) {
                                var AudioCtx = window.AudioContext || window.webkitAudioContext;
                                var ctx = new AudioCtx();
                                var src = ctx.createMediaElementSource(el);
                                var gain = ctx.createGain();
                                src.connect(gain);
                                gain.connect(ctx.destination);
                                el.__extGainNode = gain;
                            }
                            el.__extGainNode.gain.value = gainVal;
                        } catch (e) {}
                    });
                    return 'Volume Booster: ' + Math.round(gainVal * 100) + '%';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_media_downloader",
            name = "Image & Media Extractor",
            version = "1.9.1",
            description = "Menampilkan galeri seluruh gambar beresolusi tinggi dan tautan media di halaman saat ini untuk diunduh cepat.",
            author = "Media Tools",
            iconName = "Collections",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var old = document.getElementById('__ext_img_extractor_modal');
                    if (old) { old.remove(); return 'Galeri Media ditutup'; }
                    var urls = [];
                    document.querySelectorAll('img').forEach(function(img) {
                        var s = img.currentSrc || img.src;
                        if (s && s.startsWith('http') && (img.naturalWidth > 64 || img.width > 64) && urls.indexOf(s) === -1) {
                            urls.push(s);
                        }
                    });
                    if (!urls.length) return 'Tidak ditemukan gambar utama di halaman ini';
                    var wrap = document.createElement('div');
                    wrap.id = '__ext_img_extractor_modal';
                    wrap.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:rgba(15,17,21,0.96);color:#fff;overflow-y:auto;padding:18px;font-family:sans-serif;';
                    var top = document.createElement('div');
                    top.style.cssText = 'display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;';
                    var titleEl = document.createElement('b');
                    titleEl.style.fontSize = '16px';
                    titleEl.textContent = 'Ekstraktor Gambar (' + urls.length + ')';
                    top.appendChild(titleEl);
                    var cls = document.createElement('button');
                    cls.textContent = 'Tutup ✕';
                    cls.style.cssText = 'padding:7px 14px;background:#8ab4f8;color:#111;border:none;border-radius:99px;font-weight:bold;';
                    cls.onclick = function() { wrap.remove(); };
                    top.appendChild(cls);
                    wrap.appendChild(top);
                    var grid = document.createElement('div');
                    grid.style.cssText = 'display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:10px;';
                    urls.forEach(function(u) {
                        var card = document.createElement('a');
                        card.href = u;
                        card.target = '_blank';
                        card.rel = 'noopener noreferrer';
                        card.style.cssText = 'display:block;background:#20232a;padding:6px;border-radius:10px;text-align:center;color:#8ab4f8;text-decoration:none;font-size:11px;';
                        var imgEl = document.createElement('img');
                        imgEl.src = u;
                        imgEl.style.cssText = 'width:100%;height:100px;object-fit:contain;border-radius:6px;margin-bottom:4px;display:block;';
                        card.appendChild(imgEl);
                        card.appendChild(document.createTextNode('Buka Ukuran Penuh'));
                        grid.appendChild(card);
                    });
                    wrap.appendChild(grid);
                    document.body.appendChild(wrap);
                    return 'Ditemukan ' + urls.length + ' gambar';
                })();
            """.trimIndent()
        ),
        BrowserExtension(
            id = "ext_clean_reader",
            name = "Clean Reader View",
            version = "1.4.0",
            description = "Mengubah artikel berita yang penuh sidebar menjadi tampilan bacaan bersih yang nyaman di mata.",
            author = "Reader Labs",
            iconName = "MenuBook",
            runAt = ExtensionRunAt.MANUAL_ONLY,
            isEnabled = true,
            isPinnedToToolbar = false,
            sourceType = ExtensionSourceType.BUILT_IN,
            jsCode = """
                (function() {
                    var ex = document.getElementById('__ext_clean_reader_overlay');
                    if (ex) { ex.remove(); return 'Keluar dari Clean Reader View'; }
                    var target = document.querySelector('article, main, [role="main"]') || document.body;
                    var modal = document.createElement('div');
                    modal.id = '__ext_clean_reader_overlay';
                    modal.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:#181a1f;color:#e8eaed;overflow-y:auto;padding:24px 18px 80px;font-family:Georgia,serif;line-height:1.75;font-size:18px;';
                    var inner = document.createElement('div');
                    inner.style.cssText = 'max-width:680px;margin:0 auto;';
                    var closeBtn = document.createElement('button');
                    closeBtn.textContent = '✕ Tutup Mode Baca';
                    closeBtn.style.cssText = 'position:fixed;top:14px;right:14px;padding:8px 14px;background:#8ab4f8;color:#111;border:none;border-radius:99px;font-family:sans-serif;font-weight:bold;font-size:13px;';
                    closeBtn.onclick = function() { modal.remove(); };
                    var heading = document.createElement('h1');
                    heading.style.cssText = 'font-family:sans-serif;font-size:24px;line-height:1.35;margin-bottom:16px;';
                    heading.textContent = document.title || '';
                    var contentBox = document.createElement('div');
                    var parser = new DOMParser();
                    var parsedDoc = parser.parseFromString(target.innerHTML || '', 'text/html');
                    parsedDoc.querySelectorAll('script,iframe,object,embed,nav,aside,footer,form,style,link,meta,base').forEach(function(n) { n.remove(); });
                    parsedDoc.querySelectorAll('*').forEach(function(el) {
                        Array.from(el.attributes).forEach(function(attr) {
                            var name = attr.name.toLowerCase();
                            var val = (attr.value || '').trim().toLowerCase();
                            if (name.indexOf('on') === 0 ||
                                ((name === 'href' || name === 'src' || name === 'xlink:href' || name === 'action' || name === 'formaction') &&
                                 (val.indexOf('javascript:') === 0 || val.indexOf('vbscript:') === 0 || val.indexOf('data:text/html') === 0))) {
                                el.removeAttribute(attr.name);
                            }
                        });
                    });
                    while (parsedDoc.body.firstChild) {
                        contentBox.appendChild(document.adoptNode(parsedDoc.body.firstChild));
                    }
                    inner.appendChild(heading);
                    inner.appendChild(contentBox);
                    modal.appendChild(closeBtn);
                    modal.appendChild(inner);
                    document.body.appendChild(modal);
                    return 'Clean Reader View Aktif';
                })();
            """.trimIndent()
        )
    )

    private fun getSecurePrefs(context: Context): android.content.SharedPreferences {
        val encPrefs = EncryptedPreferencesManager.getPreferences(context)
        val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.contains(KEY_EXTENSIONS_JSON) && !encPrefs.contains(KEY_EXTENSIONS_JSON)) {
            try {
                val legacyRaw = legacy.getString(KEY_EXTENSIONS_JSON, null)
                val legacySeeded = legacy.getBoolean(KEY_SEEDED_DEFAULTS, false)
                val editor = encPrefs.edit()
                if (!legacyRaw.isNullOrBlank()) {
                    editor.putString(KEY_EXTENSIONS_JSON, legacyRaw)
                }
                editor.putBoolean(KEY_SEEDED_DEFAULTS, legacySeeded).apply()
                legacy.edit().clear().apply()
            } catch (_: Exception) {}
        }
        return encPrefs
    }

    fun loadExtensions(context: Context): List<BrowserExtension> {
        val prefs = getSecurePrefs(context)
        val seeded = prefs.getBoolean(KEY_SEEDED_DEFAULTS, false)
        val raw = prefs.getString(KEY_EXTENSIONS_JSON, null)
        if (!seeded || raw.isNullOrBlank()) {
            saveExtensions(context, BUILT_IN_EXTENSIONS)
            prefs.edit().putBoolean(KEY_SEEDED_DEFAULTS, true).apply()
            return BUILT_IN_EXTENSIONS
        }
        val builtInById = BUILT_IN_EXTENSIONS.associateBy { it.id }
        val parsed = parseExtensionsJson(raw).map { ext ->
            val latestBuiltIn = builtInById[ext.id]
            if (latestBuiltIn != null && ext.sourceType == ExtensionSourceType.BUILT_IN) {
                ext.copy(
                    jsCode = latestBuiltIn.jsCode,
                    cssCode = latestBuiltIn.cssCode,
                    version = latestBuiltIn.version
                )
            } else {
                ext
            }
        }
        if (parsed.isEmpty()) return BUILT_IN_EXTENSIONS

        // Ensure any new built-in extensions are present
        val existingIds = parsed.map { it.id }.toSet()
        val missingBuiltIns = BUILT_IN_EXTENSIONS.filter { it.id !in existingIds }
        return if (missingBuiltIns.isNotEmpty()) {
            val merged = parsed + missingBuiltIns
            saveExtensions(context, merged)
            merged
        } else {
            parsed
        }
    }

    fun saveExtensions(context: Context, extensions: List<BrowserExtension>) {
        val prefs = getSecurePrefs(context)
        prefs.edit()
            .putString(KEY_EXTENSIONS_JSON, serializeExtensionsJson(extensions))
            .putBoolean(KEY_SEEDED_DEFAULTS, true)
            .apply()
    }

    fun serializeExtensionsJson(extensions: List<BrowserExtension>): String {
        val arr = JSONArray()
        extensions.forEach { ext ->
            val obj = JSONObject().apply {
                put("id", ext.id)
                put("name", ext.name)
                put("version", ext.version)
                put("description", ext.description)
                put("author", ext.author)
                put("iconName", ext.iconName)
                put("jsCode", ext.jsCode)
                put("cssCode", ext.cssCode)
                put("matches", JSONArray(ext.matches))
                put("runAt", ext.runAt.name)
                put("isEnabled", ext.isEnabled)
                put("isPinnedToToolbar", ext.isPinnedToToolbar)
                put("allowInPrivate", ext.allowInPrivate)
                put("sourceType", ext.sourceType.name)
                put("sourceUrl", ext.sourceUrl)
            }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    fun parseExtensionsJson(jsonStr: String): List<BrowserExtension> {
        return try {
            val trimmed = jsonStr.trim()
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                when {
                    root.has("extensions") -> root.getJSONArray("extensions")
                    root.has("manifest_version") || root.has("content_scripts") -> {
                        // Single Chrome WebExtension manifest.json!
                        return listOfNotNull(parseWebExtensionManifestJson(trimmed))
                    }
                    root.has("name") && (root.has("jsCode") || root.has("cssCode") || root.has("code")) -> {
                        JSONArray().put(root)
                    }
                    else -> JSONArray()
                }
            }
            val result = mutableListOf<BrowserExtension>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val jsCode = obj.optString("jsCode", obj.optString("code", "")).trim()
                val cssCode = obj.optString("cssCode", "").trim()
                if (name.isEmpty() || (jsCode.isEmpty() && cssCode.isEmpty())) continue

                val matchesArr = obj.optJSONArray("matches")
                val matchesList = mutableListOf<String>()
                if (matchesArr != null) {
                    for (j in 0 until matchesArr.length()) {
                        val m = matchesArr.optString(j, "").trim()
                        if (m.isNotEmpty()) matchesList.add(m)
                    }
                }
                if (matchesList.isEmpty()) matchesList.add("*")

                val runAtStr = obj.optString("runAt", ExtensionRunAt.DOCUMENT_END.name)
                val runAt = try {
                    ExtensionRunAt.valueOf(runAtStr)
                } catch (_: Exception) {
                    ExtensionRunAt.DOCUMENT_END
                }

                val srcStr = obj.optString("sourceType", ExtensionSourceType.CUSTOM_CODE.name)
                val sourceType = try {
                    ExtensionSourceType.valueOf(srcStr)
                } catch (_: Exception) {
                    ExtensionSourceType.CUSTOM_CODE
                }

                val normalizedMatches = matchesList
                    .map { normalizeMatchPattern(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .ifEmpty { listOf("*") }

                val isExternalSource = sourceType == ExtensionSourceType.EXTERNAL_FILE ||
                    sourceType == ExtensionSourceType.EXTERNAL_URL
                val defaultEnabled = !isExternalSource

                result.add(
                    BrowserExtension(
                        id = obj.optString("id", UUID.randomUUID().toString()).ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        version = obj.optString("version", "1.0.0").ifBlank { "1.0.0" },
                        description = obj.optString("description", "Ekstensi kustom"),
                        author = obj.optString("author", "External"),
                        iconName = obj.optString("iconName", "Extension"),
                        jsCode = jsCode,
                        cssCode = cssCode,
                        matches = normalizedMatches,
                        runAt = runAt,
                        isEnabled = obj.optBoolean("isEnabled", defaultEnabled),
                        isPinnedToToolbar = obj.optBoolean("isPinnedToToolbar", true),
                        allowInPrivate = obj.optBoolean("allowInPrivate", false),
                        sourceType = sourceType,
                        sourceUrl = obj.optString("sourceUrl", "")
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse extensions JSON: ${e.message}")
            emptyList()
        }
    }

    fun parseUserScriptOrJs(rawContent: String, fallbackFileName: String = "Custom Extension"): BrowserExtension? {
        val code = rawContent.trim()
        if (code.isBlank()) return null

        var name = fallbackFileName
            .removeSuffix(".user.js")
            .removeSuffix(".js")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Custom Script Extension" }
        var version = "1.0.0"
        var description = "Diimpor dari file skrip eksternal (.user.js / .js)"
        var author = "Userscript Author"
        val matches = mutableListOf<String>()
        var runAt = ExtensionRunAt.DOCUMENT_END

        // Parse ==UserScript== header block if present
        val headerRegex = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""")
        val headerMatch = headerRegex.find(code)
        if (headerMatch != null) {
            val headerBlock = headerMatch.groupValues[1]
            headerBlock.lines().forEach { line ->
                val metaMatch = Regex("""^//\s*@(\S+)\s+(.+)$""").find(line.trim())
                if (metaMatch != null) {
                    val key = metaMatch.groupValues[1].lowercase(Locale.US)
                    val value = metaMatch.groupValues[2].trim()
                    when (key) {
                        "name" -> if (value.isNotBlank()) name = value
                        "version" -> if (value.isNotBlank()) version = value
                        "description" -> if (value.isNotBlank()) description = value
                        "author", "namespace" -> if (value.isNotBlank() && author == "Userscript Author") author = value
                        "match", "include" -> {
                            val normalizedPattern = normalizeMatchPattern(value)
                            if (normalizedPattern.isNotBlank() && normalizedPattern !in matches) {
                                matches.add(normalizedPattern)
                            }
                        }
                        "run-at" -> {
                            runAt = when {
                                value.contains("document-start", ignoreCase = true) -> ExtensionRunAt.DOCUMENT_START
                                value.contains("context-menu", ignoreCase = true) -> ExtensionRunAt.MANUAL_ONLY
                                else -> ExtensionRunAt.DOCUMENT_END
                            }
                        }
                    }
                }
            }
        }

        if (matches.isEmpty()) matches.add("*")

        // Provide hardened GM_* compatibility shim with SSRF & host-scope enforcement
        val gmShim = buildSafeGmXhrShim(matches)

        return BrowserExtension(
            id = "ext_user_" + UUID.randomUUID().toString().take(8),
            name = name.take(45),
            version = version.take(16),
            description = description.take(180),
            author = author.take(40),
            iconName = "Terminal",
            jsCode = "$gmShim\n$code",
            cssCode = "",
            matches = matches,
            runAt = runAt,
            isEnabled = false,
            isPinnedToToolbar = true,
            allowInPrivate = false,
            sourceType = ExtensionSourceType.EXTERNAL_FILE
        )
    }

    internal fun buildSafeGmXhrShim(allowedMatches: List<String>): String {
        val cleanMatches = allowedMatches
            .map { normalizeMatchPattern(it) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("*") }
        val matchesJson = JSONArray(cleanMatches).toString()
        return """
            if (typeof window.GM_addStyle === 'undefined') {
                window.GM_addStyle = function(css) {
                    var s = document.createElement('style');
                    s.textContent = String(css || '');
                    (document.head || document.documentElement).appendChild(s);
                    return s;
                };
            }
            if (typeof window.GM_setValue === 'undefined') {
                window.GM_setValue = function(k, v) { try { localStorage.setItem('__gm_' + k, JSON.stringify(v)); } catch(e){} };
            }
            if (typeof window.GM_getValue === 'undefined') {
                window.GM_getValue = function(k, defVal) {
                    try {
                        var v = localStorage.getItem('__gm_' + k);
                        return v !== null ? JSON.parse(v) : defVal;
                    } catch(e) { return defVal; }
                };
            }
            if (typeof window.GM_xmlhttpRequest === 'undefined') {
                (function() {
                    var allowedScopes = $matchesJson;
                    function isPrivateOrLocalHost(h) {
                        if (!h) return true;
                        var host = h.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
                        if (!host || host === 'localhost' || host.endsWith('.localhost') ||
                            host.endsWith('.local') || host.endsWith('.internal') ||
                            host.endsWith('.home.arpa') || host.endsWith('.lan')) {
                            return true;
                        }
                        if (host.indexOf(':') !== -1) {
                            if (host === '::1' || host === '0:0:0:0:0:0:0:1' || host === '::' ||
                                /^fe[89ab][0-9a-f]:/i.test(host) || /^f[cd][0-9a-f]{2}:/i.test(host)) {
                                return true;
                            }
                        }
                        var ipv4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
                        if (ipv4) {
                            var a = parseInt(ipv4[1], 10), b = parseInt(ipv4[2], 10);
                            if (a === 0 || a === 10 || a === 127 || a >= 224) return true;
                            if (a === 169 && b === 254) return true;
                            if (a === 192 && b === 168) return true;
                            if (a === 172 && b >= 16 && b <= 31) return true;
                            if (a === 100 && b >= 64 && b <= 127) return true;
                        }
                        return false;
                    }
                    function isHostInScope(targetHost) {
                        var th = targetHost.toLowerCase().replace(/^www\./, '');
                        var pageHost = (window.location && window.location.hostname || '').toLowerCase().replace(/^www\./, '');
                        if (pageHost && (th === pageHost || th.endsWith('.' + pageHost))) return true;
                        for (var i = 0; i < allowedScopes.length; i++) {
                            var pat = String(allowedScopes[i] || '').toLowerCase().replace(/^\*\./, '').replace(/^www\./, '');
                            if (pat === '*' || th === pat || (pat && th.endsWith('.' + pat))) return true;
                        }
                        return false;
                    }
                    window.GM_xmlhttpRequest = function(details) {
                        if (!details || typeof details.url !== 'string') return;
                        var parsed;
                        try {
                            parsed = new URL(details.url.trim(), window.location.href);
                        } catch (e) {
                            if (details.onerror) details.onerror(new Error('Invalid GM_xmlhttpRequest URL'));
                            return;
                        }
                        if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
                            if (details.onerror) details.onerror(new Error('Blocked unsafe GM_xmlhttpRequest scheme'));
                            return;
                        }
                        var targetHost = (parsed.hostname || '').toLowerCase();
                        if (isPrivateOrLocalHost(targetHost)) {
                            if (details.onerror) details.onerror(new Error('Blocked GM_xmlhttpRequest to private/local address'));
                            return;
                        }
                        if (!isHostInScope(targetHost)) {
                            if (details.onerror) details.onerror(new Error('Blocked GM_xmlhttpRequest outside extension host scope'));
                            return;
                        }
                        var safeHeaders = {};
                        if (details.headers && typeof details.headers === 'object') {
                            Object.keys(details.headers).forEach(function(k) {
                                var lk = String(k).toLowerCase().trim();
                                if (lk && lk !== 'cookie' && lk !== 'cookie2' &&
                                    lk !== 'authorization' && lk !== 'proxy-authorization' &&
                                    lk !== 'host' && lk !== 'origin' && lk !== 'referer' &&
                                    lk.indexOf('sec-') !== 0 && lk.indexOf('proxy-') !== 0) {
                                    safeHeaders[k] = String(details.headers[k]);
                                }
                            });
                        }
                        fetch(parsed.href, {
                            method: details.method || 'GET',
                            headers: safeHeaders,
                            body: details.data,
                            credentials: 'omit',
                            cache: 'no-store',
                            redirect: 'error',
                            mode: 'cors'
                        })
                            .then(function(r) {
                                return r.text().then(function(t) {
                                    var bounded = (t && t.length > 1048576) ? t.slice(0, 1048576) : t;
                                    if (details.onload) details.onload({ status: r.status, responseText: bounded });
                                });
                            })
                            .catch(function(e) { if (details.onerror) details.onerror(e); });
                    };
                })();
            }
        """.trimIndent()
    }

    fun isAllowedGmXhrUrl(
        targetUrl: String,
        pageUrl: String?,
        extensionMatches: List<String>
    ): Boolean {
        val trimmed = targetUrl.trim()
        if (!SecurityGuardEngine.isSafeWebNavigationUrl(trimmed)) return false
        if (SecurityGuardEngine.isCriticalProtectedHost(trimmed)) return false

        val targetHost = try {
            Uri.parse(trimmed).host?.lowercase(Locale.US)?.removeSurrounding("[", "]")?.trimEnd('.') ?: ""
        } catch (_: Exception) {
            ""
        }
        if (targetHost.isBlank()) return false
        if (SecurityGuardEngine.isLocalOrPrivateAddress(targetHost)) return false

        val cleanTargetHost = targetHost.removePrefix("www.")
        val pageHost = try {
            if (!pageUrl.isNullOrBlank()) {
                Uri.parse(pageUrl).host?.lowercase(Locale.US)?.removeSurrounding("[", "]")?.trimEnd('.')?.removePrefix("www.") ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
        if (pageHost.isNotEmpty() && !SecurityGuardEngine.isLocalOrPrivateAddress(pageHost)) {
            if (cleanTargetHost == pageHost || cleanTargetHost.endsWith(".$pageHost")) {
                return true
            }
        }

        return extensionMatches.any { rawPattern ->
            val clean = normalizeMatchPattern(rawPattern)
            clean.isNotEmpty() && (clean == "*" || cleanTargetHost == clean || cleanTargetHost.endsWith(".$clean"))
        }
    }

    fun parseCssExtension(rawCss: String, fallbackFileName: String = "Custom Style"): BrowserExtension? {
        val css = rawCss.trim()
        if (css.isBlank()) return null
        val name = fallbackFileName
            .removeSuffix(".user.css")
            .removeSuffix(".css")
            .replace("_", " ")
            .replace("-", " ")
            .trim()
            .ifBlank { "Custom CSS Style" }

        return BrowserExtension(
            id = "ext_css_" + UUID.randomUUID().toString().take(8),
            name = name.take(45),
            version = "1.0.0",
            description = "UserStyle CSS kustom yang diimpor dari luar",
            author = "UserStyle",
            iconName = "Palette",
            jsCode = "",
            cssCode = css,
            matches = listOf("*"),
            runAt = ExtensionRunAt.DOCUMENT_START,
            isEnabled = false,
            isPinnedToToolbar = true,
            allowInPrivate = false,
            sourceType = ExtensionSourceType.EXTERNAL_FILE
        )
    }

    fun parseWebExtensionManifestJson(
        manifestJsonStr: String,
        bundledFiles: Map<String, String> = emptyMap()
    ): BrowserExtension? {
        return try {
            val manifest = JSONObject(manifestJsonStr)
            val name = manifest.optString("name", "WebExtension").trim()
            val version = manifest.optString("version", "1.0.0").trim()
            val description = manifest.optString("description", "Diimpor dari paket WebExtension (manifest.json)")
            val author = manifest.optString("author", "WebExtension Developer")

            val jsBuilder = StringBuilder()
            val cssBuilder = StringBuilder()
            val matches = mutableListOf<String>()
            var runAt = ExtensionRunAt.DOCUMENT_END

            // Extract content_scripts if present
            val contentScripts = manifest.optJSONArray("content_scripts")
            if (contentScripts != null) {
                for (i in 0 until contentScripts.length()) {
                    val cs = contentScripts.optJSONObject(i) ?: continue
                    val mArr = cs.optJSONArray("matches")
                    if (mArr != null) {
                        for (j in 0 until mArr.length()) {
                            val pat = normalizeMatchPattern(mArr.optString(j, ""))
                            if (pat.isNotBlank() && pat !in matches) matches.add(pat)
                        }
                    }
                    val runAtStr = cs.optString("run_at", "document_end")
                    if (runAtStr.contains("document_start", ignoreCase = true)) {
                        runAt = ExtensionRunAt.DOCUMENT_START
                    }
                    val jsArr = cs.optJSONArray("js")
                    if (jsArr != null) {
                        for (j in 0 until jsArr.length()) {
                            val fileKey = sanitizeZipEntryName(jsArr.optString(j, "")) ?: continue
                            val content = findBundledFile(bundledFiles, fileKey)
                            if (!content.isNullOrBlank()) {
                                jsBuilder.append("\n/* --- ").append(fileKey).append(" --- */\n").append(content)
                            }
                        }
                    }
                    val cssArr = cs.optJSONArray("css")
                    if (cssArr != null) {
                        for (j in 0 until cssArr.length()) {
                            val fileKey = sanitizeZipEntryName(cssArr.optString(j, "")) ?: continue
                            val content = findBundledFile(bundledFiles, fileKey)
                            if (!content.isNullOrBlank()) {
                                cssBuilder.append("\n/* --- ").append(fileKey).append(" --- */\n").append(content)
                            }
                        }
                    }
                }
            }

            // If no content_scripts matched, gather any .js / .css files in the ZIP package
            if (jsBuilder.isEmpty() && cssBuilder.isEmpty() && bundledFiles.isNotEmpty()) {
                bundledFiles.forEach { (path, content) ->
                    when {
                        path.endsWith(".js", ignoreCase = true) -> jsBuilder.append("\n").append(content)
                        path.endsWith(".css", ignoreCase = true) -> cssBuilder.append("\n").append(content)
                    }
                }
            }

            // Or inline jsCode / cssCode in custom manifest
            if (jsBuilder.isEmpty() && manifest.has("jsCode")) {
                jsBuilder.append(manifest.optString("jsCode"))
            }
            if (cssBuilder.isEmpty() && manifest.has("cssCode")) {
                cssBuilder.append(manifest.optString("cssCode"))
            }

            val finalJs = jsBuilder.toString().trim()
            val finalCss = cssBuilder.toString().trim()
            if (finalJs.isEmpty() && finalCss.isEmpty()) return null

            if (matches.isEmpty()) matches.add("*")

            BrowserExtension(
                id = "ext_crx_" + UUID.randomUUID().toString().take(8),
                name = name.take(45),
                version = version.take(16),
                description = description.take(180),
                author = author.take(40),
                iconName = "Extension",
                jsCode = finalJs,
                cssCode = finalCss,
                matches = matches,
                runAt = runAt,
                isEnabled = false,
                isPinnedToToolbar = true,
                allowInPrivate = false,
                sourceType = ExtensionSourceType.EXTERNAL_FILE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WebExtension manifest.json: ${e.message}")
            null
        }
    }

    private const val MAX_ZIP_ENTRIES = 64
    private const val MAX_SINGLE_ENTRY_BYTES = 1_500_000
    private const val MAX_TOTAL_ARCHIVE_BYTES = 4_500_000
    private const val MAX_RAW_EXTENSION_BYTES = 2_000_000

    internal fun sanitizeZipEntryName(rawName: String): String? {
        if (rawName.isBlank() || rawName.contains('\u0000')) return null
        val normalized = rawName.replace('\\', '/').trim()
            .removePrefix("./")
            .trimStart('/')
        if (normalized.isEmpty()) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any { ch -> ch.isISOControl() } }) {
            return null
        }
        return segments.joinToString("/")
    }

    internal fun isSafeRemoteExtensionUrl(url: String, resolveDns: Boolean = false): Boolean {
        val trimmed = url.trim()
        return try {
            val parsed = URL(trimmed)
            if (!parsed.protocol.equals("https", ignoreCase = true)) return false
            val host = parsed.host.lowercase(Locale.US).removeSurrounding("[", "]").trimEnd('.')
            if (host.isBlank()) return false

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

    internal fun readBoundedUtf8Text(stream: java.io.InputStream, maxBytes: Int = MAX_RAW_EXTENSION_BYTES): String {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var totalRead = 0
        var count: Int
        while (stream.read(buffer).also { count = it } != -1) {
            totalRead += count
            if (totalRead > maxBytes) {
                throw SecurityException("Ukuran file ekstensi melebihi batas keamanan maksimum (${maxBytes / 1024} KB)")
            }
            baos.write(buffer, 0, count)
        }
        return baos.toString("UTF-8")
    }

    private fun findBundledFile(bundledFiles: Map<String, String>, targetPath: String): String? {
        val cleanTarget = sanitizeZipEntryName(targetPath) ?: return null
        bundledFiles[cleanTarget]?.let { return it }
        return bundledFiles.entries.firstOrNull { (k, _) ->
            k.equals(cleanTarget, ignoreCase = true) || k.endsWith("/$cleanTarget", ignoreCase = true)
        }?.value
    }

    suspend fun importExtensionFromUri(context: Context, uri: Uri, displayName: String): List<BrowserExtension> =
        withContext(Dispatchers.IO) {
            val lowerName = displayName.lowercase(Locale.US)
            try {
                if (lowerName.endsWith(".zip") || lowerName.endsWith(".crx") || lowerName.endsWith(".xpi")) {
                    val filesMap = mutableMapOf<String, String>()
                    context.contentResolver.openInputStream(uri)?.use { rawStream ->
                        ZipInputStream(rawStream).use { zis ->
                            var entryCount = 0
                            var cumulativeBytes = 0
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (++entryCount > MAX_ZIP_ENTRIES) {
                                    throw SecurityException("Arsip ZIP memiliki terlalu banyak entri (maks $MAX_ZIP_ENTRIES)")
                                }
                                if (!entry.isDirectory) {
                                    val entryName = sanitizeZipEntryName(entry.name)
                                    if (entryName != null && (
                                            entryName.endsWith(".json", true) ||
                                                entryName.endsWith(".js", true) ||
                                                entryName.endsWith(".css", true)
                                            )
                                    ) {
                                        val baos = ByteArrayOutputStream()
                                        val buffer = ByteArray(4096)
                                        var count: Int
                                        var entryBytes = 0
                                        while (zis.read(buffer).also { count = it } != -1) {
                                            entryBytes += count
                                            cumulativeBytes += count
                                            if (entryBytes > MAX_SINGLE_ENTRY_BYTES || cumulativeBytes > MAX_TOTAL_ARCHIVE_BYTES) {
                                                throw SecurityException("Ukuran ekstraksi ZIP melebihi batas memori aman")
                                            }
                                            baos.write(buffer, 0, count)
                                        }
                                        filesMap[entryName] = baos.toString("UTF-8")
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                    val manifestContent = findBundledFile(filesMap, "manifest.json")
                    if (manifestContent != null) {
                        val ext = parseWebExtensionManifestJson(manifestContent, filesMap)
                        if (ext != null) return@withContext listOf(ext)
                    }
                    // Fallback: combine any .js / .css inside the zip
                    val combinedJs = filesMap.filterKeys { it.endsWith(".js", true) }.values.joinToString("\n")
                    val combinedCss = filesMap.filterKeys { it.endsWith(".css", true) }.values.joinToString("\n")
                    if (combinedJs.isNotBlank() || combinedCss.isNotBlank()) {
                        return@withContext listOf(
                            BrowserExtension(
                                id = "ext_zip_" + UUID.randomUUID().toString().take(8),
                                name = displayName.substringBeforeLast(".").ifBlank { "ZIP Extension" },
                                description = "Diimpor dari paket arsip ekstensi ($displayName)",
                                jsCode = combinedJs,
                                cssCode = combinedCss,
                                sourceType = ExtensionSourceType.EXTERNAL_FILE,
                                isEnabled = false,
                                isPinnedToToolbar = true,
                                allowInPrivate = false
                            )
                        )
                    }
                    emptyList()
                } else {
                    val textContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                        readBoundedUtf8Text(stream, MAX_RAW_EXTENSION_BYTES)
                    } ?: return@withContext emptyList()

                    parseExtensionFromRawText(textContent, displayName, ExtensionSourceType.EXTERNAL_FILE, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing extension from URI: ${e.message}")
                emptyList()
            }
        }

    suspend fun importExtensionFromUrl(urlStr: String): Result<List<BrowserExtension>> =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = urlStr.trim()
                if (!cleanUrl.startsWith("https://", true)) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Demi keamanan, impor ekstensi jarak jauh wajib menggunakan protokol terenkripsi https://")
                    )
                }
                if (!isSafeRemoteExtensionUrl(cleanUrl, resolveDns = true)) {
                    return@withContext Result.failure(
                        SecurityException("URL ekstensi ditolak oleh pelindung jaringan (SSRF / alamat lokal tidak diizinkan)")
                    )
                }

                var currentUrl = cleanUrl
                var redirects = 0
                val maxRedirects = 3
                var conn: HttpURLConnection

                while (true) {
                    if (!isSafeRemoteExtensionUrl(currentUrl, resolveDns = true)) {
                        return@withContext Result.failure(
                            SecurityException("Target pengalihan (redirect) ekstensi tidak aman atau mengarah ke jaringan lokal")
                        )
                    }
                    val urlObj = URL(currentUrl)
                    conn = (urlObj.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        useCaches = false
                        connectTimeout = 10000
                        readTimeout = 10000
                        requestMethod = "GET"
                        // SECURITY FIX (audit 2026-10-26): do not spoof an
                        // outdated Chrome/124 UA for extension downloads.
                        setRequestProperty("User-Agent", "AlexBrowser/2.0")
                    }
                    val status = conn.responseCode
                    if (status in intArrayOf(
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
                            return@withContext Result.failure(IllegalStateException("Pengalihan HTTP $status tanpa header Location"))
                        }
                        if (++redirects > maxRedirects) {
                            return@withContext Result.failure(SecurityException("Terlalu banyak pengalihan URL (maks $maxRedirects)"))
                        }
                        val nextUrl = URL(urlObj, location).toString()
                        if (!isSafeRemoteExtensionUrl(nextUrl, resolveDns = true)) {
                            return@withContext Result.failure(
                                SecurityException("Pengalihan ke protokol non-HTTPS atau jaringan lokal diblokir")
                            )
                        }
                        currentUrl = nextUrl
                        continue
                    }
                    if (status !in 200..299) {
                        conn.disconnect()
                        return@withContext Result.failure(IllegalStateException("Gagal mengunduh ekstensi (HTTP $status)"))
                    }
                    break
                }

                val rawText = try {
                    conn.inputStream.use { stream ->
                        readBoundedUtf8Text(stream, MAX_RAW_EXTENSION_BYTES)
                    }
                } finally {
                    conn.disconnect()
                }

                val fileName = Uri.parse(currentUrl).lastPathSegment ?: "Remote_Extension.js"
                val parsed = parseExtensionFromRawText(
                    rawText = rawText,
                    fileName = fileName,
                    sourceType = ExtensionSourceType.EXTERNAL_URL,
                    sourceUrl = currentUrl
                )
                if (parsed.isEmpty()) {
                    Result.failure(IllegalArgumentException("Konten URL tidak berisi kode ekstensi JS/CSS/JSON yang valid"))
                } else {
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun parseExtensionFromRawText(
        rawText: String,
        fileName: String,
        sourceType: ExtensionSourceType,
        sourceUrl: String
    ): List<BrowserExtension> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()
        val lowerName = fileName.lowercase(Locale.US)

        // 1. Check if JSON (Extension bundle or manifest.json)
        if (lowerName.endsWith(".json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val fromJson = parseExtensionsJson(trimmed)
            if (fromJson.isNotEmpty()) {
                return fromJson.map { ext ->
                    enforceExternalImportSafety(
                        ext.copy(sourceType = sourceType, sourceUrl = sourceUrl.ifBlank { ext.sourceUrl })
                    )
                }
            }
        }

        // 2. Check if CSS UserStyle
        if (lowerName.endsWith(".css")) {
            val cssExt = parseCssExtension(trimmed, fileName)
            if (cssExt != null) {
                return listOf(
                    enforceExternalImportSafety(
                        cssExt.copy(sourceType = sourceType, sourceUrl = sourceUrl)
                    )
                )
            }
        }

        // 3. Parse as Userscript (.user.js) or standard JavaScript (.js)
        val jsExt = parseUserScriptOrJs(trimmed, fileName)
        return if (jsExt != null) {
            listOf(
                enforceExternalImportSafety(
                    jsExt.copy(sourceType = sourceType, sourceUrl = sourceUrl)
                )
            )
        } else {
            emptyList()
        }
    }

    internal fun enforceExternalImportSafety(ext: BrowserExtension): BrowserExtension {
        if (ext.sourceType == ExtensionSourceType.BUILT_IN) return ext
        val isExternalImport = ext.sourceType == ExtensionSourceType.EXTERNAL_FILE ||
            ext.sourceType == ExtensionSourceType.EXTERNAL_URL
        val hasWildcardAllSites = ext.matches.isEmpty() || ext.matches.contains("*")
        return ext.copy(
            isEnabled = if (isExternalImport || hasWildcardAllSites) false else ext.isEnabled,
            allowInPrivate = false
        )
    }

    internal fun normalizeMatchPattern(rawPattern: String): String {
        val p = rawPattern.trim()
        if (p.isEmpty()) return ""
        if (p == "*" || p.equals("<all_urls>", ignoreCase = true) ||
            p == "*://*/*" || p == "http://*/*" || p == "https://*/*" ||
            p == "*://*" || p == "http://*" || p == "https://*"
        ) {
            return "*"
        }
        return try {
            val withoutScheme = p
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("*://")
            val hostPart = withoutScheme
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")
                .substringBefore(":")
                .removePrefix("*.")
                .removePrefix("www.")
                .trim()
                .trimEnd('.')
                .lowercase(Locale.US)
            if (hostPart.isBlank() || hostPart == "*") {
                ""
            } else if (hostPart.any { it.isWhitespace() || it in charArrayOf('<', '>', '"', '\'', ';', '\\') }) {
                ""
            } else {
                hostPart
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun doesExtensionMatchUrl(extension: BrowserExtension, url: String?): Boolean {
        if (url.isNullOrBlank() || url == "about:blank") return false
        if (!SecurityGuardEngine.isSafeWebNavigationUrl(url)) return false
        val rawHost = try {
            Uri.parse(url).host?.lowercase(Locale.US)?.removeSurrounding("[", "]")?.trimEnd('.') ?: ""
        } catch (_: Exception) {
            ""
        }
        val host = rawHost.removePrefix("www.")
        if (host.isEmpty()) return false
        if (extension.sourceType != ExtensionSourceType.BUILT_IN &&
            SecurityGuardEngine.isLocalOrPrivateAddress(rawHost)
        ) {
            return false
        }
        if (extension.matches.isEmpty()) return false
        return extension.matches.any { pattern ->
            val clean = normalizeMatchPattern(pattern)
            clean.isNotEmpty() && (clean == "*" || host == clean || host.endsWith(".$clean"))
        }
    }

    fun canAutoInjectExtensionOnUrl(
        extension: BrowserExtension,
        url: String?,
        isPrivate: Boolean = false
    ): Boolean {
        if (!extension.isEnabled || extension.runAt == ExtensionRunAt.MANUAL_ONLY) return false
        if (isPrivate && !extension.allowInPrivate) return false
        if (!doesExtensionMatchUrl(extension, url)) return false
        if (SecurityGuardEngine.isCriticalProtectedHost(url)) return false
        return true
    }

    fun injectMatchingExtensions(
        webView: WebView?,
        url: String?,
        extensions: List<BrowserExtension>,
        stage: ExtensionRunAt,
        isPrivate: Boolean = false
    ) {
        if (webView == null || url.isNullOrBlank() || url == "about:blank") return
        if (!SecurityGuardEngine.isSafeWebNavigationUrl(url) || SecurityGuardEngine.isCriticalProtectedHost(url)) {
            return
        }
        extensions.forEach { ext ->
            if (ext.runAt == stage && canAutoInjectExtensionOnUrl(ext, url, isPrivate = isPrivate)) {
                executeExtensionInWebView(webView, ext, isManualTrigger = false, onResult = null)
            }
        }
    }

    fun executeExtensionInWebView(
        webView: WebView?,
        extension: BrowserExtension,
        isManualTrigger: Boolean = true,
        onResult: ((String) -> Unit)? = null
    ) {
        if (webView == null) {
            onResult?.invoke("Buka halaman web terlebih dahulu untuk menjalankan ekstensi")
            return
        }

        try {
            // 1. Inject CSS if present
            if (extension.cssCode.isNotBlank()) {
                val encodedCss = Base64.encodeToString(
                    extension.cssCode.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val styleId = "__ext_css_${extension.id.replace(Regex("[^a-zA-Z0-9_]"), "_")}"
                val cssInjector = """
                    (function() {
                        try {
                            var id = '$styleId';
                            var existing = document.getElementById(id);
                            if (existing && $isManualTrigger && ${extension.jsCode.isBlank()}) {
                                existing.remove();
                                return 'Gaya CSS ' + ${JSONObject.quote(extension.name)} + ' dinonaktifkan';
                            }
                            if (!existing) {
                                var st = document.createElement('style');
                                st.id = id;
                                st.textContent = decodeURIComponent(escape(window.atob('$encodedCss')));
                                (document.head || document.documentElement).appendChild(st);
                            }
                            return 'Gaya CSS ' + ${JSONObject.quote(extension.name)} + ' diterapkan';
                        } catch (e) {
                            return 'CSS Error: ' + e.message;
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(cssInjector) { res ->
                    if (extension.jsCode.isBlank()) {
                        val cleaned = res?.trim()?.removeSurrounding("\"") ?: "Aktif"
                        onResult?.invoke(cleaned)
                    }
                }
            }

            // 2. Inject JS if present
            if (extension.jsCode.isNotBlank()) {
                val encodedJs = Base64.encodeToString(
                    extension.jsCode.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val quotedExtErrorPrefix = JSONObject.quote("Extension error [${extension.name}]:")
                val jsWrapper = """
                    (function() {
                        try {
                            window.__extManualTrigger = $isManualTrigger;
                            var src = decodeURIComponent(escape(window.atob('$encodedJs')));
                            var fn = new Function(src);
                            var out = fn();
                            return out !== undefined && out !== null ? String(out) : 'Ekstensi dijalankan';
                        } catch (err) {
                            console.error($quotedExtErrorPrefix, err);
                            return 'Error: ' + (err && err.message ? err.message : String(err));
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsWrapper) { rawResult ->
                    val cleaned = rawResult
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\n", " ")
                        ?: "Ekstensi dijalankan"
                    onResult?.invoke(cleaned)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute extension ${extension.name}: ${e.message}")
            onResult?.invoke("Gagal menjalankan ekstensi: ${e.message}")
        }
    }
}

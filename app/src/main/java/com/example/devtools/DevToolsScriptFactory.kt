package com.example.devtools

import org.json.JSONObject

object DevToolsScriptFactory {

    fun installElementPickerScript(): String {
        return """
            (function() {
                if (window.__ADB_DEVTOOLS_PICKER_INSTALLED__) return;
                window.__ADB_DEVTOOLS_PICKER_INSTALLED__ = true;

                const overlay = document.createElement('div');
                overlay.id = '__adb_devtools_overlay__';
                overlay.style.position = 'fixed';
                overlay.style.pointerEvents = 'none';
                overlay.style.zIndex = '2147483647';
                overlay.style.border = '2px solid #4da3ff';
                overlay.style.background = 'rgba(77, 163, 255, 0.12)';
                overlay.style.display = 'none';
                overlay.style.transition = 'all 0.05s ease-out';
                document.documentElement.appendChild(overlay);

                function cssPath(el) {
                    if (!el || !el.tagName) return '';
                    const parts = [];
                    let depth = 0;
                    while (el && el.nodeType === Node.ELEMENT_NODE && depth < 8) {
                        let part = el.tagName.toLowerCase();
                        if (el.id) {
                            part += '#' + el.id;
                            parts.unshift(part);
                            break;
                        }
                        const classes = Array.from(el.classList || [])
                            .filter(c => !c.startsWith('__adb')) // skip our picker class
                            .slice(0, 3)
                            .map(c => '.' + c)
                            .join('');
                        part += classes;
                        parts.unshift(part);
                        el = el.parentElement;
                        depth++;
                    }
                    return parts.join(' > ');
                }

                function serializeElement(el) {
                    const rect = el.getBoundingClientRect();
                    const styles = window.getComputedStyle(el);
                    const selectedStyles = {};
                    [
                        'display', 'position', 'width', 'height', 'margin', 'padding',
                        'color', 'background-color', 'font-size', 'font-family',
                        'z-index', 'overflow', 'opacity'
                    ].forEach(k => selectedStyles[k] = styles.getPropertyValue(k));

                    const attrs = {};
                    for (const attr of Array.from(el.attributes || [])) {
                        attrs[attr.name] = attr.value.length > 300 ? attr.value.slice(0, 300) + '…' : attr.value;
                    }

                    return {
                        tagName: el.tagName.toLowerCase(),
                        id: el.id || null,
                        classes: Array.from(el.classList || []).filter(c => !c.startsWith('__adb')),
                        textPreview: (el.innerText || el.textContent || '').trim().slice(0, 300),
                        attributes: attrs,
                        computedStyles: selectedStyles,
                        selector: cssPath(el),
                        rect: {
                            x: rect.x + window.scrollX,
                            y: rect.y + window.scrollY,
                            width: rect.width,
                            height: rect.height
                        }
                    };
                }

                function moveOverlay(el) {
                    if (!el) return;
                    const r = el.getBoundingClientRect();
                    overlay.style.left = r.left + 'px';
                    overlay.style.top = r.top + 'px';
                    overlay.style.width = r.width + 'px';
                    overlay.style.height = r.height + 'px';
                    overlay.style.display = 'block';
                }

                document.addEventListener('mousemove', function(e) {
                    if (!window.__ADB_DEVTOOLS_PICKER_ACTIVE__) return;
                    const el = document.elementFromPoint(e.clientX, e.clientY);
                    if (el && el !== overlay && el !== document.documentElement && el !== document.body) {
                        moveOverlay(el);
                    }
                }, true);

                // Supporting touch screen devices
                document.addEventListener('touchstart', function(e) {
                    if (!window.__ADB_DEVTOOLS_PICKER_ACTIVE__) return;
                    const touch = e.touches[0];
                    if (!touch) return;
                    const el = document.elementFromPoint(touch.clientX, touch.clientY);
                    if (el && el !== overlay && el !== document.documentElement && el !== document.body) {
                        moveOverlay(el);
                    }
                }, { capture: true, passive: true });

                document.addEventListener('touchmove', function(e) {
                    if (!window.__ADB_DEVTOOLS_PICKER_ACTIVE__) return;
                    const touch = e.touches[0];
                    if (!touch) return;
                    const el = document.elementFromPoint(touch.clientX, touch.clientY);
                    if (el && el !== overlay && el !== document.documentElement && el !== document.body) {
                        moveOverlay(el);
                    }
                }, { capture: true, passive: true });

                document.addEventListener('click', function(e) {
                    if (!window.__ADB_DEVTOOLS_PICKER_ACTIVE__) return;
                    e.preventDefault();
                    e.stopPropagation();

                    const el = document.elementFromPoint(e.clientX, e.clientY);
                    if (!el || el === overlay || el === document.documentElement) return;

                    const payload = serializeElement(el);

                    try {
                        window.AdBlockDevTools.onElementSelected(JSON.stringify(payload));
                    } catch (_) {}

                    window.__ADB_DEVTOOLS_PICKER_ACTIVE__ = false;
                    overlay.style.display = 'none';
                }, true);
            })();
        """.trimIndent()
    }

    fun getStorageRefreshScript(): String {
        return """
            (function() {
                try {
                    const ls = {};
                    for (let i = 0; i < localStorage.length; i++) {
                        const k = localStorage.key(i);
                        if (k) ls[k] = localStorage.getItem(k) || "";
                    }

                    const ss = {};
                    for (let i = 0; i < sessionStorage.length; i++) {
                        const k = sessionStorage.key(i);
                        if (k) ss[k] = sessionStorage.getItem(k) || "";
                    }

                    return JSON.stringify({
                        cookies: document.cookie || "",
                        localStorage: ls,
                        sessionStorage: ss,
                        indexedDbAvailable: (typeof window.indexedDB !== 'undefined')
                    });
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })();
        """.trimIndent()
    }

    fun applyStyleScript(selector: String, property: String, value: String): String {
        val safeSelector = JSONObject.quote(selector)
        val safeProperty = JSONObject.quote(property)
        val safeValue = JSONObject.quote(value)

        return """
            (function() {
                try {
                    const el = document.querySelector($safeSelector);
                    if (!el) return "Element not found";
                    el.style.setProperty($safeProperty, $safeValue);
                    return "Success";
                } catch(e) {
                    return "Error: " + e.message;
                }
            })();
        """.trimIndent()
    }

    fun getConsoleEvalScript(command: String): String {
        return """
            (function() {
                try {
                    const result = eval(${JSONObject.quote(command)});
                    if (result === undefined) return "undefined";
                    if (result === null) return "null";
                    if (typeof result === "object") {
                        try {
                            return JSON.stringify(result, null, 2);
                        } catch(e) {
                            return String(result);
                        }
                    }
                    return String(result);
                } catch(e) {
                    return "Error: " + e.message;
                }
            })();
        """.trimIndent()
    }
}

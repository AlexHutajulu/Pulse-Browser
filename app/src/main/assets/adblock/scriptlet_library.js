/**
 * Browser — Pustaka scriptlet (subset aman).
 *
 * Diinjeksikan di awal halaman (early script) oleh AdBlockWebViewClient,
 * SEBELUM pemanggilan per-aturan dari ScriptletEngine.kt
 * (window.__browserScriptlets['nama'](args...)).
 *
 * Subset yang diimplementasikan, mengikuti semantik uBlock/AdGuard:
 *  - set-constant: kunci properti window ke nilai konstan.
 *  - abort-on-property-read / abort-on-property-write: lempar saat
 *    properti dibaca/ditulis (cara standar memutus skrip anti-adblock
 *    yang mengecek properti iklan).
 *  - prevent-setTimeout / prevent-setInterval: cegah timer yang
 *    callback-nya mengandung kata kunci (needle), atau yang delay-nya
 *    di bawah ambang — sesuai argumen aturan.
 *
 * Scriptlet di luar subset ini TIDAK dieksekusi oleh ScriptletEngine.kt,
 * jadi file ini tidak perlu mengimplementasikannya.
 *
 * Aman: tidak memakai eval, tidak membaca cookie/password, tidak
 * mengirim data ke mana pun. Guard ganda supaya injeksi berulang
 * tidak menumpuk wrapper timer.
 */
(function() {
    'use strict';
    if (window.__browserScriptlets) return;

    function parseValue(raw) {
        if (raw === undefined) return undefined;
        var s = String(raw).trim();
        if (s === 'true') return true;
        if (s === 'false') return false;
        if (s === 'null') return null;
        if (s === 'undefined') return undefined;
        if (s !== '' && !isNaN(Number(s))) return Number(s);
        return s;
    }

    function defineLocked(obj, prop, descriptor) {
        try {
            Object.defineProperty(obj, prop, descriptor);
            return true;
        } catch (e) {
            return false;
        }
    }

    function abortError() {
        var id = Math.random().toString(36).slice(2);
        var err = new ReferenceError(id);
        err.name = 'ReferenceError';
        return err;
    }

    // set-constant: set-constant, <prop>, <value>
    // Contoh: example.com##+js(set-constant, adsbygoogle, true)
    function setConstant(prop, value) {
        if (!prop) return;
        var val = parseValue(value);
        defineLocked(window, prop, {
            get: function() { return val; },
            set: function() {},
            configurable: false,
            enumerable: true
        });
    }

    // abort-on-property-read: abort-on-property-read, <prop>
    function abortOnPropertyRead(prop) {
        if (!prop) return;
        defineLocked(window, prop, {
            get: function() { throw abortError(); },
            set: function() {},
            configurable: false,
            enumerable: true
        });
    }

    // abort-on-property-write: abort-on-property-write, <prop>
    function abortOnPropertyWrite(prop) {
        if (!prop) return;
        var stored;
        defineLocked(window, prop, {
            get: function() { return stored; },
            set: function() { throw abortError(); },
            configurable: false,
            enumerable: true
        });
    }

    // prevent-setTimeout / prevent-setInterval: (needle, delay?)
    // - Kalau needle kosong: tidak memblokir apa pun.
    // - Kalau callback berupa string dan mengandung needle -> cegah.
    // - Kalau callback berupa fungsi dan source-nya mengandung needle -> cegah.
    // - Kalau argumen delay berupa angka dan delay < ambang -> cegah.
    // Implementasi prevent-* yang benar membutuhkan needle per pemanggilan.
    // Karena ScriptletEngine memanggil L['prevent-setTimeout'](needle, delay),
    // fungsi di bawah ini langsung memasang wrapper sekali, dengan needle
    // yang diberikan pada pemanggilan pertama untuk needle itu.
    var installedTimers = {};
    function preventTimer(kind, needle, delayArg) {
        if (!needle) return;
        var key = kind + '|' + needle + '|' + (delayArg || '');
        if (installedTimers[key]) return;
        installedTimers[key] = true;
        var original = kind === 'setTimeout' ? window.setTimeout : window.setInterval;
        if (typeof original !== 'function') return;
        var threshold = delayArg !== undefined && delayArg !== '' && !isNaN(Number(delayArg))
            ? Number(delayArg) : null;
        var wrapped = function(callback, delay) {
            try {
                var src = '';
                if (typeof callback === 'string') src = callback;
                else if (typeof callback === 'function') src = Function.prototype.toString.call(callback);
                if (needle && src.indexOf(needle) !== -1) {
                    return kind === 'setTimeout' ? 0 : 0;
                }
                if (threshold !== null && typeof delay === 'number' && delay < threshold) {
                    return 0;
                }
            } catch (e) {}
            return original.apply(this, arguments);
        };
        try {
            if (kind === 'setTimeout') window.setTimeout = wrapped;
            else window.setInterval = wrapped;
        } catch (e) {}
    }

    window.__browserScriptlets = {
        'set-constant': setConstant,
        'abort-on-property-read': abortOnPropertyRead,
        'abort-on-property-write': abortOnPropertyWrite,
        'prevent-setTimeout': function(needle, delay) { preventTimer('setTimeout', needle, delay); },
        'prevent-setInterval': function(needle, delay) { preventTimer('setInterval', needle, delay); }
    };
})();

package com.example.manager

import okhttp3.CertificatePinner

/**
 * Certificate pinning for background downloads (filter lists).
 *
 * SECURITY NOTE (audit 2026-10-26): pins are deliberately NOT hard-coded
 * from a sandbox / proxy environment. A sandbox egress proxy re-signs TLS
 * with its own CA, so a pin extracted there would be wrong on real devices
 * and would break every filter-list update in production.
 *
 * How to activate pinning before a public release, from a real device /
 * network (not behind a MITM proxy):
 *   1. Run for each host:
 *      echo | openssl s_client -servername <host> -connect <host>:443 2>/dev/null \
 *        | openssl x509 -pubkey -noout \
 *        | openssl pkey -pubin -outform der \
 *        | openssl dgst -sha256 -binary | openssl base64
 *   2. Put the resulting base64 value (without the "sha256/" prefix) in
 *      FILTER_LIST_PINS below, with at least two pins per host (leaf +
 *      intermediate/backup) so a normal certificate rotation does not
 *      brick updates.
 *   3. Re-run the filter-list update test and confirm updates still succeed.
 *
 * Until pins are configured, buildPinner() returns null and OkHttp keeps
 * system-trust validation plus the existing HTTPS-only, safe-DNS and
 * redirect checks in FilterListManager. That is the pre-existing behaviour,
 * now made explicit instead of silently claiming pinning exists.
 */
object CertificatePinning {
    /**
     * Host -> list of base64 SPKI SHA-256 pins (without "sha256/" prefix).
     * Fill this from a real network before release. Empty = pinning off.
     */
    val FILTER_LIST_PINS: Map<String, List<String>> = mapOf(
        // Example format (NOT a real pin, do not ship this as-is):
        // "easylist.to" to listOf("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
    )

    fun buildPinner(): CertificatePinner? {
        if (FILTER_LIST_PINS.isEmpty()) return null
        val builder = CertificatePinner.Builder()
        for ((host, pins) in FILTER_LIST_PINS) {
            for (pin in pins) {
                if (pin.isNotBlank()) {
                    builder.add(host, "sha256/$pin")
                }
            }
        }
        return builder.build()
    }

    /**
     * Reject downloaded filter-list text that looks like active content.
     * Filter lists are plain-text Adblock rules; they must never contain
     * HTML script tags or javascript: URLs. A compromised list server
     * serving such content is discarded instead of cached.
     */
    fun isSafeFilterListContent(body: String): Boolean {
        val lower = body.lowercase()
        return !lower.contains("<script") &&
            !lower.contains("javascript:") &&
            !lower.contains("data:text/html")
    }
}

package com.example.manager

import okhttp3.CertificatePinner

object CertificatePinning {
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

    fun isSafeFilterListContent(body: String): Boolean {
        val lower = body.lowercase()
        return !lower.contains("<script") &&
            !lower.contains("javascript:") &&
            !lower.contains("data:text/html")
    }
}

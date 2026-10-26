package com.example.manager

class HostPairPolicy(
    blockedHosts: Collection<String> = emptySet(),
    private val blockedPairs: Map<String, Set<String>> = emptyMap(),
    private val allowedPairs: Map<String, Set<String>> = emptyMap(),
) {
    private val blocked: Set<String> = blockedHosts
        .map { it.lowercase().trim('.') }
        .filter { it.isNotEmpty() }
        .toSet()

    
    fun isAllowedByPair(requestHost: String?, pageHost: String?): Boolean {
        val request = requestHost?.lowercase()?.trim('.')?.takeIf { it.isNotEmpty() }
            ?: return false
        val page = pageHost?.lowercase()?.trim('.')?.takeIf { it.isNotEmpty() }
        return pairMatches(allowedPairs, request, page)
    }

    fun shouldBlock(requestHost: String?, pageHost: String?): Boolean {
        val request = requestHost?.lowercase()?.trim('.')?.takeIf { it.isNotEmpty() }
            ?: return false
        val page = pageHost?.lowercase()?.trim('.')?.takeIf { it.isNotEmpty() }

        // 1. First-party escape.
        if (page != null && isSameOrSubdomain(request, page)) return false

        // 2. Allow pairs beat everything else below.
        if (pairMatches(allowedPairs, request, page)) return false

        // 3. Block pairs.
        if (pairMatches(blockedPairs, request, page)) return true

        // 4. Plain host block, walking up subdomains.
        var candidate = request
        while (true) {
            if (candidate in blocked) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun pairMatches(
        pairs: Map<String, Set<String>>,
        request: String,
        page: String?,
    ): Boolean {
        val pages = pairs[request] ?: return false
        return "*" in pages || (page != null && page in pages)
    }

    private fun isSameOrSubdomain(first: String, second: String): Boolean =
        first == second || first.endsWith(".$second") || second.endsWith(".$first")
}

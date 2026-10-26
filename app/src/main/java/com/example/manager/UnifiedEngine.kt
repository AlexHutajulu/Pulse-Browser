package com.example.manager

class UnifiedEngine private constructor(
    private val networkMatcher: NetworkMatcher,
    private val cosmeticProvider: CosmeticProvider?,
    private val hostPairPolicy: HostPairPolicy?,
    private val resources: ResourceRegistry?,
    private val removeParams: Set<String>,
    private val redirectFor: (NetworkRequest) -> String?,
    private val cspFor: (NetworkRequest) -> List<String>,
    val genericCosmetics: TokenCosmeticPayload?,
    val engineVersion: Int,
    val listFingerprint: String,
) {
    fun checkNetwork(request: NetworkRequest): UnifiedBlockResult {
        // 1. Host-pair layer. First-party escape applies only
        // to this layer: a request to the page's own host is
        // never blocked by a host rule, but it still reaches
        // the ABP matcher below, so ABP rules that explicitly
        // target first-party requests can still apply.
        val page = request.pageHost?.lowercase()?.trim('.')
        val req = request.requestHost.lowercase().trim('.')
        val isFirstParty = page != null && (req == page || req.endsWith(".$page"))
        if (hostPairPolicy != null && !isFirstParty) {
            if (hostPairPolicy.isAllowedByPair(request.requestHost, request.pageHost)) {
                return UnifiedBlockResult.allow("host-pair-allow")
            }
            if (hostPairPolicy.shouldBlock(request.requestHost, request.pageHost)) {
                val redirect = redirectFor(request)?.let { resources?.redirectBody(it) }
                return UnifiedBlockResult(
                    shouldBlock = true,
                    redirectBody = redirect,
                    reason = "host-pair",
                )
            }
        }

        // 2-5. ABP matcher.
        val matched = networkMatcher.check(request)
        if (matched.important) return matched
        if (matched.exceptionMatched) return UnifiedBlockResult.allow("exception")

        val abpBlock = matched.shouldBlock
        if (!abpBlock) {
            // 7. Parameter stripping only for allowed requests.
            val rewritten = ParameterStripper.strip(request.url, removeParams)
            return if (rewritten != null) {
                matched.copy(rewrittenUrl = rewritten, reason = "removeparam")
            } else {
                matched
            }
        }

        // 6. Blocked: try a redirect resource.
        val redirectName = redirectFor(request)
        val redirectBody = redirectName?.let { resources?.redirectBody(it) }
        return matched.copy(
            redirectBody = redirectBody,
            cspDirectives = if (request.kind == RequestKind.DOCUMENT) {
                cspFor(request)
            } else {
                matched.cspDirectives
            },
        )
    }

    
    fun urlCosmeticResources(pageHost: String): PageCosmeticResources {
        val base = cosmeticProvider?.resourcesFor(pageHost)
            ?: PageCosmeticResources()
        if (base.generichide) return base
        // Generic token payload is handled by the page-side scanner
        // in 02-TokenCosmetic; this method only reports that the
        // generic layer is active for this host.
        return base
    }

    
    fun hiddenClassIdSelectors(
        pageHost: String,
        newClasses: Collection<String>,
        newIds: Collection<String>,
        deniedSelectors: Set<String> = emptySet(),
    ): Set<String> {
        val payload = genericCosmetics ?: return emptySet()
        val base = cosmeticProvider?.resourcesFor(pageHost)
        if (base?.generichide == true) return emptySet()
        val out = mutableSetOf<String>()
        for (name in newClasses) {
            if (name in payload.classes) {
                val selector = ".$name"
                if (selector !in deniedSelectors) out += selector
            }
        }
        for (name in newIds) {
            if (name in payload.ids) {
                val selector = "#$name"
                if (selector !in deniedSelectors) out += selector
            }
        }
        return out
    }

    
    fun snapshotForCache(): SerializedEngineCache.Entry =
        SerializedEngineCache.Entry(
            engineVersion = engineVersion,
            listFingerprint = listFingerprint,
            genericSelectorCount = genericCosmetics?.selectorCount ?: 0,
        )

    class Builder {
        private var networkMatcher: NetworkMatcher? = null
        private var cosmeticProvider: CosmeticProvider? = null
        private var hostPairPolicy: HostPairPolicy? = null
        private var resources: ResourceRegistry? = null
        private var removeParams: Set<String> = emptySet()
        private var redirectFor: (NetworkRequest) -> String? = { null }
        private var cspFor: (NetworkRequest) -> List<String> = { emptyList() }
        private var genericCosmetics: TokenCosmeticPayload? = null
        private var engineVersion: Int = 1
        private var listFingerprint: String = ""

        fun networkMatcher(m: NetworkMatcher) = apply { networkMatcher = m }
        fun cosmeticProvider(p: CosmeticProvider?) = apply { cosmeticProvider = p }
        fun hostPairPolicy(p: HostPairPolicy?) = apply { hostPairPolicy = p }
        fun resources(r: ResourceRegistry?) = apply { resources = r }
        fun removeParams(params: Set<String>) = apply { removeParams = params }
        fun redirectFor(f: (NetworkRequest) -> String?) = apply { redirectFor = f }
        fun cspFor(f: (NetworkRequest) -> List<String>) = apply { cspFor = f }
        fun genericCosmetics(p: TokenCosmeticPayload?) = apply { genericCosmetics = p }
        fun engineVersion(v: Int) = apply { engineVersion = v }
        fun listFingerprint(f: String) = apply { listFingerprint = f }

        fun build(): UnifiedEngine {
            val matcher = requireNotNull(networkMatcher) { "networkMatcher is required" }
            return UnifiedEngine(
                networkMatcher = matcher,
                cosmeticProvider = cosmeticProvider,
                hostPairPolicy = hostPairPolicy,
                resources = resources,
                removeParams = removeParams,
                redirectFor = redirectFor,
                cspFor = cspFor,
                genericCosmetics = genericCosmetics,
                engineVersion = engineVersion,
                listFingerprint = listFingerprint,
            )
        }
    }
}

object SerializedEngineCache {
    data class Entry(
        val engineVersion: Int,
        val listFingerprint: String,
        val genericSelectorCount: Int,
    )

    fun isUsable(
        cached: Entry?,
        currentEngineVersion: Int,
        currentListFingerprint: String,
    ): Boolean =
        cached != null &&
            cached.engineVersion == currentEngineVersion &&
            cached.listFingerprint == currentListFingerprint
}

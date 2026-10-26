package com.example.manager

import com.yourbrowser.adblock.HeuristicsEngine

class IntegratedEngine(
    private val ruleSet: AbpRuleEngine.RuleSet,
    private val hostPairPolicy: HostPairPolicy? = null,
    private val popupGuard: PopupGuard = PopupGuard(),
    private val resources: ResourceRegistry? = null,
    private val removeParams: Set<String> = emptySet(),
    private val redirectFor: (NetworkRequest) -> String? = { null },
    genericCosmetics: TokenCosmeticPayload? = null,
    engineVersion: Int = 1,
    listFingerprint: String = "",
) {
    private val unified = UnifiedEngine.Builder()
        .networkMatcher { request ->
            val type = mapKind(request.kind)
            val result = ruleSet.shouldBlock(
                request.url,
                request.pageHost ?: "",
                type,
            ) ?: return@networkMatcher UnifiedBlockResult.allow()
            if (result.isAllow) {
                UnifiedBlockResult(
                    shouldBlock = false,
                    exceptionMatched = true,
                    reason = result.reason,
                )
            } else {
                UnifiedBlockResult.block(result.reason)
            }
        }
        .cosmeticProvider { pageHost ->
            PageCosmeticResources(
                hideSelectors = ruleSet.cosmetic.selectorsFor(pageHost).toSet(),
                injectedScript = ScriptletEngine.buildInvocationJs(
                    pageHost,
                    ruleSet.scriptlets,
                ),
            )
        }
        .hostPairPolicy(hostPairPolicy)
        .resources(resources)
        .removeParams(removeParams)
        .redirectFor(redirectFor)
        .genericCosmetics(genericCosmetics)
        .engineVersion(engineVersion)
        .listFingerprint(listFingerprint)
        .build()

    fun checkNetwork(
        url: String,
        pageHost: String?,
        kind: RequestKind = RequestKind.OTHER,
    ): UnifiedBlockResult {
        val requestHost = AbpRuleEngine.cleanHost(AbpRuleEngine.extractHost(url))
        val cleanPage = pageHost?.let { AbpRuleEngine.cleanHost(it) }
        val isThirdParty = if (cleanPage.isNullOrEmpty()) {
            true
        } else {
            AbpRuleEngine.isThirdParty(requestHost, cleanPage)
        }
        val request = NetworkRequest(
            url = url,
            requestHost = requestHost,
            pageHost = cleanPage,
            kind = kind,
            isThirdParty = isThirdParty,
        )
        val unifiedResult = unified.checkNetwork(request)
        if (unifiedResult.shouldBlock || unifiedResult.exceptionMatched) {
            return unifiedResult
        }
        if (!isThirdParty) return unifiedResult
        val heuristic = HeuristicsEngine.heuristicReason(
            requestHost,
            url.lowercase(),
            false,
        ) ?: return unifiedResult
        return UnifiedBlockResult.block(heuristic)
    }

    fun cosmeticResources(pageHost: String): PageCosmeticResources =
        unified.urlCosmeticResources(AbpRuleEngine.cleanHost(pageHost))

    fun scriptletJs(pageHost: String): String =
        ScriptletEngine.buildInvocationJs(
            AbpRuleEngine.cleanHost(pageHost),
            ruleSet.scriptlets,
        )

    fun hiddenClassIdSelectors(
        pageHost: String,
        newClasses: Collection<String>,
        newIds: Collection<String>,
        deniedSelectors: Set<String> = emptySet(),
    ): Set<String> = unified.hiddenClassIdSelectors(
        AbpRuleEngine.cleanHost(pageHost),
        newClasses,
        newIds,
        deniedSelectors,
    )

    fun popupDecision(targetHost: String?, openerHost: String?): PopupDecision =
        popupGuard.decidePopup(targetHost, openerHost)

    fun shouldCloseOpenerAsPopunder(
        openerHost: String,
        openerTargetHost: String?,
        nowMillis: Long,
    ): Boolean = popupGuard.shouldCloseOpenerAsPopunder(
        openerHost,
        openerTargetHost,
        nowMillis,
    )

    fun noteSurvivingChild(openerHost: String, childHost: String, nowMillis: Long) {
        popupGuard.noteSurvivingChild(openerHost, childHost, nowMillis)
    }

    fun cancelPopupOpener(openerHost: String) {
        popupGuard.cancelOpener(openerHost)
    }

    fun cacheEntry(): SerializedEngineCache.Entry = unified.snapshotForCache()

    fun isCacheUsable(
        cached: SerializedEngineCache.Entry?,
        currentListFingerprint: String,
    ): Boolean = SerializedEngineCache.isUsable(
        cached,
        unified.engineVersion,
        currentListFingerprint,
    )

    private fun mapKind(kind: RequestKind): AbpRuleEngine.ResourceType =
        when (kind) {
            RequestKind.SCRIPT -> AbpRuleEngine.ResourceType.SCRIPT
            RequestKind.IMAGE -> AbpRuleEngine.ResourceType.IMAGE
            RequestKind.STYLESHEET -> AbpRuleEngine.ResourceType.STYLESHEET
            RequestKind.SUBDOCUMENT -> AbpRuleEngine.ResourceType.SUBDOCUMENT
            RequestKind.XMLHTTPREQUEST -> AbpRuleEngine.ResourceType.XMLHTTPREQUEST
            RequestKind.FONT -> AbpRuleEngine.ResourceType.FONT
            RequestKind.MEDIA -> AbpRuleEngine.ResourceType.MEDIA
            RequestKind.WEBSOCKET -> AbpRuleEngine.ResourceType.WEBSOCKET
            RequestKind.PING -> AbpRuleEngine.ResourceType.PING
            RequestKind.DOCUMENT, RequestKind.OTHER -> AbpRuleEngine.ResourceType.OTHER
        }
}

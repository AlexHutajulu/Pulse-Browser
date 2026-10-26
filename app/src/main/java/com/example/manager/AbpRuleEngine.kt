package com.example.manager

import java.util.Locale

/**
 * Pure Kotlin Adblock Plus / uBlock Origin compatible rule matching engine.
 * No Android framework dependencies — fully executable & testable on local JVM.
 */
object AbpRuleEngine {

    const val MAX_RULE_LINE_LENGTH = 2048
    const val MAX_BODY_LENGTH = 300
    const val MAX_WILDCARDS = 8
    const val MAX_SELECTOR_LENGTH = 300
    const val MAX_COSMETIC_SELECTORS_PER_HOST = 1500

    enum class ResourceType {
        SCRIPT,
        IMAGE,
        STYLESHEET,
        XMLHTTPREQUEST,
        SUBDOCUMENT,
        MEDIA,
        FONT,
        PING,
        WEBSOCKET,
        OTHER
    }

    data class MatchResult(
        val isAllow: Boolean,
        val ruleText: String,
        val reason: String
    )

    class NetworkRule(
        val rawLine: String,
        val isAllow: Boolean,
        val isDomainOnly: Boolean,
        val domainTarget: String?, // e.g. "doubleclick.net" for ||doubleclick.net^
        val pattern: CompiledPattern?,
        val thirdPartyOnly: Boolean?, // true: $third-party, false: $~third-party, null: any
        val includedDomains: Set<String>?, // $domain=a.com|b.com
        val excludedDomains: Set<String>?, // $domain=~a.com|~b.com
        val resourceTypes: Int // bitmask of 1 shl ResourceType.ordinal
    ) {
        fun matchesType(type: ResourceType): Boolean {
            if (resourceTypes == 0) return true
            return (resourceTypes and (1 shl type.ordinal)) != 0
        }

        fun matchesParty(isThirdParty: Boolean): Boolean {
            return when (thirdPartyOnly) {
                true -> isThirdParty
                false -> !isThirdParty
                null -> true
            }
        }

        fun matchesSourceDomain(sourceHost: String): Boolean {
            if (includedDomains == null && excludedDomains == null) return true
            val cleanSource = cleanHost(sourceHost)
            if (excludedDomains != null && matchesAnyDomain(cleanSource, excludedDomains)) {
                return false
            }
            if (includedDomains != null) {
                return matchesAnyDomain(cleanSource, includedDomains)
            }
            return true
        }

        fun matchesUrl(url: String, reqHost: String): Boolean {
            if (isDomainOnly && domainTarget != null) {
                return isHostOrSubdomain(reqHost, domainTarget)
            }
            return pattern?.matches(url, reqHost) ?: false
        }
    }

    class CompiledPattern(
        val startsWithPipePipe: Boolean,
        val anchorStart: Boolean,
        val anchorEnd: Boolean,
        val domainPrefix: String?,
        val segments: List<List<String>>
    ) {
        fun matches(url: String, host: String): Boolean {
            val lowerUrl = url.lowercase(Locale.US)
            var target = lowerUrl
            if (startsWithPipePipe) {
                if (domainPrefix != null) {
                    if (!isHostOrSubdomain(host, domainPrefix)) return false
                    val schemeIdx = lowerUrl.indexOf("://")
                    val hostStart = if (schemeIdx != -1) schemeIdx + 3 else 0
                    val pathStart = lowerUrl.indexOf('/', hostStart).takeIf { it != -1 } ?: lowerUrl.length
                    target = lowerUrl.substring(pathStart)
                }
            }

            var currentIdx = 0
            for ((index, segTokens) in segments.withIndex()) {
                val isFirst = (index == 0)
                val isLast = (index == segments.size - 1)
                val mustAnchorStart = isFirst && (anchorStart || (startsWithPipePipe && domainPrefix != null))
                val mustAnchorEnd = isLast && anchorEnd

                val matchEnd = matchSegment(target, segTokens, currentIdx, mustAnchorStart, mustAnchorEnd)
                if (matchEnd == -1) return false
                currentIdx = matchEnd
            }
            return true
        }
    }

    class CosmeticRuleSet(
        val globalSelectors: List<String>,
        val domainSelectors: Map<String, List<String>>,
        val domainExceptions: Map<String, Set<String>>
    ) {
        fun selectorsFor(host: String): List<String> {
            val clean = cleanHost(host)
            val result = LinkedHashSet<String>(64)
            val activeExceptions = HashSet<String>()

            // Gather exceptions for this host and parent domains
            var h = clean
            while (h.isNotEmpty()) {
                domainExceptions[h]?.let { activeExceptions.addAll(it) }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            // Domain-specific rules
            h = clean
            while (h.isNotEmpty()) {
                domainSelectors[h]?.forEach { sel ->
                    if (!activeExceptions.contains(sel)) {
                        result.add(sel)
                        if (result.size >= MAX_COSMETIC_SELECTORS_PER_HOST) return result.toList()
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            // Global rules
            for (sel in globalSelectors) {
                if (!activeExceptions.contains(sel)) {
                    result.add(sel)
                    if (result.size >= MAX_COSMETIC_SELECTORS_PER_HOST) break
                }
            }
            return result.toList()
        }
    }

    class RuleSet(
        val domainOnlyBlock: Map<String, List<NetworkRule>>,
        val domainOnlyAllow: Map<String, List<NetworkRule>>,
        val allowRules: List<NetworkRule>,
        val blockRules: List<NetworkRule>,
        val cosmetic: CosmeticRuleSet,
        val totalRulesParsed: Int,
        val totalRulesSkipped: Int,
        val scriptlets: ScriptletEngine.ScriptletRuleSet = ScriptletEngine.ScriptletRuleSet(emptyList())
    ) {
        fun isDomainBlocked(host: String): Boolean {
            val clean = cleanHost(host)
            if (clean.isEmpty()) return false

            // Check domain-only exceptions first
            var h = clean
            while (h.isNotEmpty()) {
                domainOnlyAllow[h]?.forEach { rule ->
                    if (rule.matchesSourceDomain("") && rule.matchesParty(false)) return false
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            // Check domain-only blocks
            h = clean
            while (h.isNotEmpty()) {
                val rules = domainOnlyBlock[h]
                if (!rules.isNullOrEmpty()) {
                    for (rule in rules) {
                        if (rule.includedDomains == null && rule.excludedDomains == null && rule.thirdPartyOnly == null) {
                            return true
                        }
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }
            return false
        }

        fun shouldBlock(
            url: String,
            sourceHost: String,
            resourceType: ResourceType = ResourceType.OTHER
        ): MatchResult? {
            val reqHost = cleanHost(extractHost(url))
            val srcHost = cleanHost(sourceHost)
            val isThirdParty = isThirdParty(reqHost, srcHost)

            // 1. Exception rules (allow) check first
            // Check domain-only allow rules
            var h = reqHost
            while (h.isNotEmpty()) {
                domainOnlyAllow[h]?.forEach { rule ->
                    if (rule.matchesType(resourceType) &&
                        rule.matchesParty(isThirdParty) &&
                        rule.matchesSourceDomain(srcHost)
                    ) {
                        return null
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            for (rule in allowRules) {
                if (rule.matchesType(resourceType) &&
                    rule.matchesParty(isThirdParty) &&
                    rule.matchesSourceDomain(srcHost) &&
                    rule.matchesUrl(url, reqHost)
                ) {
                    return null
                }
            }

            // 2. Block rules check
            // Domain-only block fast map
            h = reqHost
            while (h.isNotEmpty()) {
                domainOnlyBlock[h]?.forEach { rule ->
                    if (rule.matchesType(resourceType) &&
                        rule.matchesParty(isThirdParty) &&
                        rule.matchesSourceDomain(srcHost)
                    ) {
                        return MatchResult(false, rule.rawLine, "domain-rule")
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }

            // General & path block rules
            for (rule in blockRules) {
                if (rule.matchesType(resourceType) &&
                    rule.matchesParty(isThirdParty) &&
                    rule.matchesSourceDomain(srcHost) &&
                    rule.matchesUrl(url, reqHost)
                ) {
                    return MatchResult(false, rule.rawLine, "filter-rule")
                }
            }

            return null
        }

        /**
         * Apakah URL ini secara eksplisit diizinkan oleh aturan exception
         * (@@) ABP? Berbeda dari shouldBlock()==null, yang juga berarti
         * "tidak ada aturan yang cocok". Dibutuhkan oleh lapisan heuristik
         *  di AdBlockWebViewClient: exception ABP HARUS menang atas
         * heuristik, kalau tidak domain yang sengaja di-exception (mis.
         * untuk login/pembayaran) bisa ikut terblok heuristik.
         */
        fun matchesAllowRule(
            url: String,
            sourceHost: String,
            resourceType: ResourceType = ResourceType.OTHER
        ): Boolean {
            val reqHost = cleanHost(extractHost(url))
            val srcHost = cleanHost(sourceHost)
            val isThirdParty = isThirdParty(reqHost, srcHost)
            var h = reqHost
            while (h.isNotEmpty()) {
                domainOnlyAllow[h]?.forEach { rule ->
                    if (rule.matchesType(resourceType) &&
                        rule.matchesParty(isThirdParty) &&
                        rule.matchesSourceDomain(srcHost)
                    ) {
                        return true
                    }
                }
                val dot = h.indexOf('.')
                h = if (dot != -1) h.substring(dot + 1) else ""
            }
            for (rule in allowRules) {
                if (rule.matchesType(resourceType) &&
                    rule.matchesParty(isThirdParty) &&
                    rule.matchesSourceDomain(srcHost) &&
                    rule.matchesUrl(url, reqHost)
                ) {
                    return true
                }
            }
            return false
        }
    }

    fun build(lines: Sequence<String>): RuleSet {
        val domainBlockMap = mutableMapOf<String, MutableList<NetworkRule>>()
        val domainAllowMap = mutableMapOf<String, MutableList<NetworkRule>>()
        val generalAllowList = mutableListOf<NetworkRule>()
        val generalBlockList = mutableListOf<NetworkRule>()

        val globalCosmetics = mutableListOf<String>()
        val domainCosmetics = mutableMapOf<String, MutableList<String>>()
        val domainExceptions = mutableMapOf<String, MutableSet<String>>()
        val scriptletRules = mutableListOf<ScriptletEngine.ScriptletRule>()

        var parsedCount = 0
        var skippedCount = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() ||
                trimmed.length > MAX_RULE_LINE_LENGTH ||
                trimmed.startsWith("!") ||
                trimmed.startsWith("[")
            ) {
                skippedCount++
                continue
            }

            // Scriptlet (lapisan ): diparsing, BUKAN dibuang lagi.
            // Aturan ##+js / #@#+js / #%#//scriptlet masuk ke ScriptletEngine,
            // lalu dieksekusi per-domain lewat scriptlet_library.js di early script.
            if (trimmed.contains("##+js") || trimmed.contains("#@#+js") ||
                trimmed.contains("#%#//scriptlet") || trimmed.contains("#@$#//scriptlet")
            ) {
                val scriptlet = ScriptletEngine.parseScriptletRule(trimmed)
                if (scriptlet != null && scriptletRules.size < ScriptletEngine.MAX_SCRIPTLET_RULES) {
                    scriptletRules.add(scriptlet)
                    parsedCount++
                } else {
                    skippedCount++
                }
                continue
            }

            // Skip extended and unsupported filters (scriptlet sudah ditangani di atas)
            if (trimmed.startsWith("#?#") || trimmed.startsWith("#\$#") || trimmed.startsWith("#%#") ||
                trimmed.contains("##+js") || trimmed.contains("#@#+js") ||
                trimmed.contains("##^") || trimmed.startsWith("/") && trimmed.endsWith("/")
            ) {
                skippedCount++
                continue
            }

            // Cosmetic exception: domain#@#selector
            if (trimmed.contains("#@#")) {
                val parts = trimmed.split("#@#", limit = 2)
                if (parts.size == 2) {
                    val rawDomains = parts[0].trim()
                    val selector = parts[1].trim()
                    if (selector.isNotEmpty() && selector.length <= MAX_SELECTOR_LENGTH && isSafeCosmeticSelector(selector)) {
                        for (d in rawDomains.split(',')) {
                            val cleanD = cleanHost(d)
                            if (cleanD.isNotEmpty()) {
                                domainExceptions.getOrPut(cleanD) { mutableSetOf() }.add(selector)
                            }
                        }
                        parsedCount++
                        continue
                    }
                }
                skippedCount++
                continue
            }

            // Cosmetic filter: ##selector or domain##selector
            if (trimmed.contains("##")) {
                val parts = trimmed.split("##", limit = 2)
                if (parts.size == 2) {
                    val rawDomains = parts[0].trim()
                    val selector = parts[1].trim()
                    if (selector.isNotEmpty() && selector.length <= MAX_SELECTOR_LENGTH && isSafeCosmeticSelector(selector)) {
                        if (rawDomains.isEmpty()) {
                            globalCosmetics.add(selector)
                        } else {
                            for (d in rawDomains.split(',')) {
                                val cleanD = cleanHost(d)
                                if (cleanD.isNotEmpty()) {
                                    if (cleanD.startsWith("~")) {
                                        // Domain exclusion: treat as exception for this domain
                                        val positiveDomain = cleanD.removePrefix("~")
                                        domainExceptions.getOrPut(positiveDomain) { mutableSetOf() }.add(selector)
                                    } else {
                                        domainCosmetics.getOrPut(cleanD) { mutableListOf() }.add(selector)
                                    }
                                }
                            }
                        }
                        parsedCount++
                        continue
                    }
                }
                skippedCount++
                continue
            }

            // Network rule parsing
            val isAllow = trimmed.startsWith("@@")
            val ruleBodyAndOpts = if (isAllow) trimmed.substring(2) else trimmed

            val (bodyRaw, optionsRaw) = if (ruleBodyAndOpts.contains('$')) {
                val idx = ruleBodyAndOpts.indexOf('$')
                Pair(ruleBodyAndOpts.substring(0, idx), ruleBodyAndOpts.substring(idx + 1))
            } else {
                Pair(ruleBodyAndOpts, null)
            }

            val body = bodyRaw.trim()
            if (body.isEmpty() || body.length > MAX_BODY_LENGTH) {
                skippedCount++
                continue
            }

            // Check options for unsupported features: redirect, csp, removeparam, badfilter
            var thirdPartyOnly: Boolean? = null
            var includedDomains: MutableSet<String>? = null
            var excludedDomains: MutableSet<String>? = null
            var typeBitmask = 0
            var hasUnsupportedOption = false

            if (!optionsRaw.isNullOrBlank()) {
                for (opt in optionsRaw.split(',')) {
                    val o = opt.trim().lowercase(Locale.US)
                    when {
                        o == "third-party" || o == "3p" -> thirdPartyOnly = true
                        o == "~third-party" || o == "~3p" -> thirdPartyOnly = false
                        o.startsWith("domain=") -> {
                            val domainsStr = o.removePrefix("domain=")
                            for (d in domainsStr.split('|')) {
                                val clean = cleanHost(d)
                                if (clean.startsWith("~")) {
                                    if (excludedDomains == null) excludedDomains = mutableSetOf()
                                    excludedDomains.add(clean.removePrefix("~"))
                                } else if (clean.isNotEmpty()) {
                                    if (includedDomains == null) includedDomains = mutableSetOf()
                                    includedDomains.add(clean)
                                }
                            }
                        }
                        o == "script" -> typeBitmask = typeBitmask or (1 shl ResourceType.SCRIPT.ordinal)
                        o == "image" -> typeBitmask = typeBitmask or (1 shl ResourceType.IMAGE.ordinal)
                        o == "stylesheet" || o == "css" -> typeBitmask = typeBitmask or (1 shl ResourceType.STYLESHEET.ordinal)
                        o == "xmlhttprequest" || o == "xhr" -> typeBitmask = typeBitmask or (1 shl ResourceType.XMLHTTPREQUEST.ordinal)
                        o == "subdocument" || o == "frame" -> typeBitmask = typeBitmask or (1 shl ResourceType.SUBDOCUMENT.ordinal)
                        o == "media" -> typeBitmask = typeBitmask or (1 shl ResourceType.MEDIA.ordinal)
                        o == "font" -> typeBitmask = typeBitmask or (1 shl ResourceType.FONT.ordinal)
                        o == "ping" || o == "beacon" -> typeBitmask = typeBitmask or (1 shl ResourceType.PING.ordinal)
                        o == "websocket" -> typeBitmask = typeBitmask or (1 shl ResourceType.WEBSOCKET.ordinal)
                        o == "other" -> typeBitmask = typeBitmask or (1 shl ResourceType.OTHER.ordinal)
                        // Unsupported options: skip rule safely
                        o.startsWith("redirect=") || o.startsWith("csp=") || o.startsWith("removeparam=") ||
                            o == "badfilter" || o.startsWith("~") && (o.endsWith("script") || o.endsWith("image")) -> {
                            hasUnsupportedOption = true
                            break
                        }
                        else -> {
                            // ignore unknown option safely
                        }
                    }
                }
            }

            if (hasUnsupportedOption) {
                skippedCount++
                continue
            }

            // Parse pattern
            val isDomainOnly = body.startsWith("||") &&
                (body.endsWith("^") || !body.contains("^")) &&
                !body.drop(2).substringBefore("^").contains("/") &&
                !body.contains("*")

            if (isDomainOnly) {
                val domain = body.removePrefix("||").substringBefore("^").trim().lowercase(Locale.US)
                if (domain.isNotEmpty() && domain.contains('.')) {
                    val rule = NetworkRule(
                        rawLine = trimmed,
                        isAllow = isAllow,
                        isDomainOnly = true,
                        domainTarget = domain,
                        pattern = null,
                        thirdPartyOnly = thirdPartyOnly,
                        includedDomains = includedDomains,
                        excludedDomains = excludedDomains,
                        resourceTypes = typeBitmask
                    )
                    if (isAllow) {
                        domainAllowMap.getOrPut(domain) { mutableListOf() }.add(rule)
                    } else {
                        domainBlockMap.getOrPut(domain) { mutableListOf() }.add(rule)
                    }
                    parsedCount++
                    continue
                }
            }

            // General rule pattern
            if (body.count { it == '*' } > MAX_WILDCARDS) {
                skippedCount++
                continue
            }

            val compiled = compilePattern(body)
            if (compiled == null) {
                skippedCount++
                continue
            }

            val rule = NetworkRule(
                rawLine = trimmed,
                isAllow = isAllow,
                isDomainOnly = false,
                domainTarget = compiled.domainPrefix,
                pattern = compiled,
                thirdPartyOnly = thirdPartyOnly,
                includedDomains = includedDomains,
                excludedDomains = excludedDomains,
                resourceTypes = typeBitmask
            )

            if (isAllow) {
                generalAllowList.add(rule)
            } else {
                generalBlockList.add(rule)
            }
            parsedCount++
        }

        return RuleSet(
            domainOnlyBlock = domainBlockMap,
            domainOnlyAllow = domainAllowMap,
            allowRules = generalAllowList,
            blockRules = generalBlockList,
            cosmetic = CosmeticRuleSet(
                globalSelectors = globalCosmetics,
                domainSelectors = domainCosmetics,
                domainExceptions = domainExceptions
            ),
            totalRulesParsed = parsedCount,
            totalRulesSkipped = skippedCount,
            scriptlets = ScriptletEngine.ScriptletRuleSet(scriptletRules)
        )
    }

    private fun compilePattern(rawBody: String): CompiledPattern? {
        var body = rawBody
        val startsWithPipePipe = body.startsWith("||")
        if (startsWithPipePipe) {
            body = body.removePrefix("||")
        }

        val anchorStart = if (!startsWithPipePipe && body.startsWith("|")) {
            body = body.removePrefix("|")
            true
        } else false

        val anchorEnd = if (body.endsWith("|")) {
            body = body.removeSuffix("|")
            true
        } else false

        var domainPrefix: String? = null
        if (startsWithPipePipe) {
            val sepIdx = body.indexOfAny(charArrayOf('/', '^'))
            if (sepIdx != -1) {
                domainPrefix = body.substring(0, sepIdx).trim().lowercase(Locale.US)
                body = body.substring(sepIdx)
            } else {
                domainPrefix = body.trim().lowercase(Locale.US)
                body = ""
            }
        }

        val segments = body.split('*').map { seg ->
            seg.split('^').map { it.lowercase(Locale.US) }
        }

        return CompiledPattern(
            startsWithPipePipe = startsWithPipePipe,
            anchorStart = anchorStart,
            anchorEnd = anchorEnd,
            domainPrefix = domainPrefix?.takeIf { it.isNotEmpty() },
            segments = segments
        )
    }

    private fun isSeparatorChar(c: Char): Boolean {
        return (c !in 'a'..'z') && (c !in 'A'..'Z') && (c !in '0'..'9') &&
            c != '_' && c != '-' && c != '.' && c != '%'
    }

    private fun matchSegment(
        target: String,
        tokens: List<String>,
        startPos: Int,
        mustAnchorStart: Boolean,
        mustAnchorEnd: Boolean
    ): Int {
        if (tokens.isEmpty()) {
            if (mustAnchorEnd && startPos != target.length) return -1
            return startPos
        }
        val first = tokens[0]
        var searchFrom = startPos

        while (searchFrom <= target.length) {
            val idx = if (first.isEmpty()) searchFrom else target.indexOf(first, searchFrom)
            if (idx == -1) return -1
            if (mustAnchorStart && idx != startPos) return -1

            var cur = idx + first.length
            var matched = true
            for (i in 1 until tokens.size) {
                if (cur == target.length) {
                    if (tokens[i].isNotEmpty()) {
                        matched = false
                        break
                    }
                } else if (isSeparatorChar(target[cur])) {
                    cur++
                    val nextTok = tokens[i]
                    if (nextTok.isNotEmpty()) {
                        if (target.startsWith(nextTok, cur)) {
                            cur += nextTok.length
                        } else {
                            matched = false
                            break
                        }
                    }
                } else {
                    matched = false
                    break
                }
            }

            if (matched) {
                if (mustAnchorEnd && cur != target.length) {
                    if (mustAnchorStart) return -1
                    searchFrom = idx + 1
                    continue
                }
                return cur
            }

            if (mustAnchorStart) return -1
            searchFrom = idx + 1
        }
        return -1
    }

    fun isSafeCosmeticSelector(selector: String): Boolean {
        val s = selector.trim()
        if (s.isEmpty() || s.length > MAX_SELECTOR_LENGTH) return false
        val lower = s.lowercase(Locale.US)
        if (lower.contains("{") || lower.contains("}") || lower.contains("/*") ||
            lower.contains("*/") || lower.contains("<") || lower.contains("url(") ||
            lower.contains("expression(") || lower.contains("javascript:")
        ) {
            return false
        }
        if (s.count { it == '\'' } % 2 != 0 || s.count { it == '"' } % 2 != 0) return false
        if (s.count { it == '[' } != s.count { it == ']' }) return false
        return true
    }

    fun inferType(url: String, acceptHeader: String? = null): ResourceType {
        val lowerUrl = url.lowercase(Locale.US)
        val path = lowerUrl.substringBefore('?').substringBefore('#')
        val accept = acceptHeader?.lowercase(Locale.US) ?: ""

        return when {
            path.endsWith(".js") || lowerUrl.contains("/pagead/js/") || accept.contains("javascript") || accept.contains("ecmascript") ->
                ResourceType.SCRIPT
            path.endsWith(".css") || accept.contains("text/css") ->
                ResourceType.STYLESHEET
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") || accept.startsWith("image/") ->
                ResourceType.IMAGE
            path.endsWith(".mp4") || path.endsWith(".mp3") || path.endsWith(".m4a") ||
                path.endsWith(".m4s") || path.endsWith(".m3u8") || path.endsWith(".webm") ||
                path.endsWith(".ts") || path.endsWith(".mpd") || lowerUrl.contains(".dash") ||
                accept.contains("video/") || accept.contains("audio/") ->
                ResourceType.MEDIA
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") || accept.contains("font/") ->
                ResourceType.FONT
            lowerUrl.startsWith("ws:") || lowerUrl.startsWith("wss:") || lowerUrl.contains("/websocket/") ->
                ResourceType.WEBSOCKET
            lowerUrl.contains("/beacon") || lowerUrl.contains("/ping") || lowerUrl.contains("/log_event") ||
                lowerUrl.contains("google-analytics.com/collect") ->
                ResourceType.PING
            accept.contains("application/json") || accept.contains("application/xml") ||
                lowerUrl.contains("/api/") || path.endsWith(".json") || accept.contains("xmlhttprequest") ->
                ResourceType.XMLHTTPREQUEST
            else ->
                ResourceType.OTHER
        }
    }

    fun isThirdParty(requestHost: String, sourceHost: String): Boolean {
        val reqClean = cleanHost(requestHost)
        val srcClean = cleanHost(sourceHost)
        if (srcClean.isEmpty() || reqClean.isEmpty()) return false
        val reqReg = getRegistrableDomain(reqClean)
        val srcReg = getRegistrableDomain(srcClean)
        return reqReg != srcReg
    }

    fun getRegistrableDomain(host: String): String {
        val clean = cleanHost(host)
        if (clean.isEmpty()) return ""
        val parts = clean.split('.')
        if (parts.size <= 2) return clean

        // Multi-part country code TLDs (e.g. .co.id, .ac.id, .co.uk, .com.au)
        val last = parts.last()
        val secondLast = parts[parts.size - 2]
        val isTwoPartCctld = (last == "id" || last == "uk" || last == "au" || last == "jp" || last == "nz" || last == "br") &&
            (secondLast == "co" || secondLast == "ac" || secondLast == "go" || secondLast == "or" || secondLast == "sch" || secondLast == "com" || secondLast == "net" || secondLast == "org")

        return if (isTwoPartCctld && parts.size >= 3) {
            "${parts[parts.size - 3]}.${secondLast}.${last}"
        } else {
            "${parts[parts.size - 2]}.${last}"
        }
    }

    private fun isHostOrSubdomain(host: String, target: String): Boolean {
        if (host == target) return true
        return host.endsWith(".$target")
    }

    private fun matchesAnyDomain(host: String, domains: Set<String>): Boolean {
        for (d in domains) {
            if (isHostOrSubdomain(host, d)) return true
        }
        return false
    }

    fun cleanHost(raw: String): String {
        return raw.trim()
            .lowercase(Locale.US)
            .removeSurrounding("[", "]")
            .trimEnd('.')
            .removePrefix("www.")
    }

    fun extractHost(url: String): String {
        val u = url.trim()
        val schemeIdx = u.indexOf("://")
        val hostStart = if (schemeIdx != -1) schemeIdx + 3 else 0
        var hostEnd = u.indexOf('/', hostStart)
        if (hostEnd == -1) hostEnd = u.indexOf('?', hostStart)
        if (hostEnd == -1) hostEnd = u.indexOf('#', hostStart)
        if (hostEnd == -1) hostEnd = u.length

        val hostPort = u.substring(hostStart, hostEnd)
        return hostPort.substringBefore(':')
    }
}

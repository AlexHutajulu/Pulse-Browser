package com.example.manager

class ResourceRegistry(
    private val redirectBodies: Map<String, String> = emptyMap(),
    private val scriptletTemplates: Map<String, String> = emptyMap(),
    private val allowedScriptlets: Set<String> = setOf(
        "set-constant",
        "abort-on-property-read",
        "abort-on-property-write",
        "prevent-setTimeout",
        "prevent-setInterval",
    ),
) {
    fun redirectBody(name: String): String? = redirectBodies[name]

    
    fun scriptlet(name: String, args: List<String>): String? {
        if (name !in allowedScriptlets) return null
        val template = scriptletTemplates[name] ?: return null
        var result = template
        for (arg in args) {
            if (!result.contains("%s")) return null
            result = result.replaceFirst("%s", arg)
        }
        if (result.contains("%s")) return null
        return result
    }

    fun isAllowedScriptlet(name: String): Boolean = name in allowedScriptlets
}

object ParameterStripper {
    fun strip(url: String, paramsToRemove: Set<String>): String? {
        if (paramsToRemove.isEmpty()) return null
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = url.indexOf('#', queryStart)
        val base = url.substring(0, queryStart)
        val query = if (fragmentStart >= 0) {
            url.substring(queryStart + 1, fragmentStart)
        } else {
            url.substring(queryStart + 1)
        }
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val kept = query.split('&').filter { part ->
            val name = part.substringBefore('=')
            name !in paramsToRemove
        }
        if (kept.size == query.split('&').size) return null
        val newQuery = kept.joinToString("&")
        return if (newQuery.isEmpty()) base + fragment else "$base?$newQuery$fragment"
    }
}

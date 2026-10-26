package com.example.manager

object EngineWiring {
    fun intercept(
        engine: IntegratedEngine,
        url: String,
        pageHost: String?,
        kind: RequestKind = RequestKind.OTHER,
    ): UnifiedBlockResult = engine.checkNetwork(url, pageHost, kind)

    fun popup(
        engine: IntegratedEngine,
        targetHost: String?,
        openerHost: String?,
    ): PopupDecision = engine.popupDecision(targetHost, openerHost)

    fun documentStartScripts(
        engine: IntegratedEngine,
        pageHost: String,
    ): List<String> {
        val resources = engine.cosmeticResources(pageHost)
        val scripts = mutableListOf<String>()
        if (resources.hideSelectors.isNotEmpty()) {
            val css = resources.hideSelectors.joinToString(",") { "$it{display:none!important}" }
            scripts += "(function(){var s=document.createElement('style');s.textContent=" +
                css.replace("'", "\\'").let { "'$it'" } +
                ";(document.head||document.documentElement).appendChild(s);})();"
        }
        val scriptletJs = engine.scriptletJs(pageHost)
        if (scriptletJs.isNotBlank()) {
            scripts += scriptletJs
        }
        return scripts
    }

    fun gate(): EngineSnapshotGate<IntegratedEngine> = EngineSnapshotGate()
}

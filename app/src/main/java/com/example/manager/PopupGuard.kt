package com.example.manager

enum class PopupDecision { Block, Allow, Undecided }

data class PopupRule(
    val targetHostPattern: String,
    val openerHostPattern: String = "*",
    val block: Boolean = true,
)

class PopupGuard(
    private val alwaysBlockOpenerDomains: Set<String> = emptySet(),
    private val rules: List<PopupRule> = emptyList(),
    private val popunderWindowMillis: Long = 5_000L,
) {
    // opener host -> (child host, time the child was first seen)
    private val survivingChildren = mutableMapOf<String, Pair<String, Long>>()

    fun decidePopup(targetHost: String?, openerHost: String?): PopupDecision {
        val opener = openerHost?.lowercase()?.trim('.') ?: return PopupDecision.Undecided
        if (isInAlwaysBlock(opener)) return PopupDecision.Block
        val target = targetHost?.lowercase()?.trim('.')
        var allowMatch = false
        var blockMatch = false
        for (rule in rules) {
            if (!hostMatches(opener, rule.openerHostPattern)) continue
            if (target != null && !hostMatches(target, rule.targetHostPattern)) continue
            if (rule.block) blockMatch = true else allowMatch = true
        }
        return when {
            allowMatch -> PopupDecision.Allow
            blockMatch -> PopupDecision.Block
            else -> PopupDecision.Undecided
        }
    }

    
    fun noteSurvivingChild(openerHost: String, childHost: String, nowMillis: Long) {
        survivingChildren[openerHost.lowercase().trim('.')] =
            childHost.lowercase().trim('.') to nowMillis
    }

    
    fun shouldCloseOpenerAsPopunder(
        openerHost: String,
        openerTargetHost: String?,
        nowMillis: Long,
    ): Boolean {
        prune(nowMillis)
        val opener = openerHost.lowercase().trim('.')
        val child = survivingChildren[opener] ?: return false
        if (nowMillis - child.second > popunderWindowMillis) return false
        if (openerTargetHost == null) return false
        val target = openerTargetHost.lowercase().trim('.')
        return rules.any { rule ->
            rule.block &&
                hostMatches(opener, rule.openerHostPattern) &&
                hostMatches(target, rule.targetHostPattern)
        }
    }

    fun cancelOpener(openerHost: String) {
        survivingChildren.remove(openerHost.lowercase().trim('.'))
    }

    private fun prune(nowMillis: Long) {
        val expired = survivingChildren.filter { (_, value) ->
            nowMillis - value.second > popunderWindowMillis
        }.keys
        expired.forEach { survivingChildren.remove(it) }
    }

    private fun isInAlwaysBlock(openerHost: String): Boolean {
        var candidate = openerHost
        while (true) {
            if (candidate in alwaysBlockOpenerDomains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun hostMatches(host: String, pattern: String): Boolean {
        val p = pattern.lowercase().trim('.')
        if (p == "*") return true
        if (p.startsWith("*.")) {
            val suffix = p.drop(2)
            return host == suffix || host.endsWith(".$suffix")
        }
        return host == p || host.endsWith(".$p")
    }
}

fun shouldDefuseWindowOpen(openerHost: String?, defusedHosts: Set<String>): Boolean {
    if (openerHost.isNullOrBlank()) return false
    var candidate = openerHost.lowercase().trim('.')
    while (true) {
        if (candidate in defusedHosts) return true
        val dot = candidate.indexOf('.')
        if (dot < 0) return false
        candidate = candidate.substring(dot + 1)
    }
}

val defuserScript: String = """
(function () {
  if (window.__windowOpenDefused) return;
  window.__windowOpenDefused = true;
  var originalOpen = window.open;
  window.open = function () {
    try {
      var result = null;
      if (originalOpen) {
        // Do not actually open; returning null signals a blocked popup
        // to well-behaved callers without navigating anywhere.
        result = null;
      }
      return result;
    } catch (e) { return null; }
  };
})();
""".trimIndent()

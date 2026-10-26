package com.example.manager

import java.util.Locale

object ScriptletEngine {

    const val MAX_SCRIPTLET_RULES = 5_000
    const val MAX_SCRIPTLET_ARGS = 5
    const val MAX_SCRIPTLET_NAME_LENGTH = 64
    const val MAX_SCRIPTLET_ARG_LENGTH = 300

    
    val SUPPORTED_SCRIPTLETS = setOf(
        "set-constant",
        "abort-on-property-read",
        "abort-on-property-write",
        "prevent-setTimeout",
        "prevent-setInterval"
    )

    data class ScriptletRule(
        
        val domains: Set<String>,
        val name: String,
        val args: List<String>,
        val isException: Boolean
    ) {
        fun matchesHost(host: String): Boolean {
            if (domains.isEmpty()) return true
            val clean = AbpRuleEngine.cleanHost(host)
            if (clean.isEmpty()) return false
            for (d in domains) {
                if (clean == d || clean.endsWith(".$d")) return true
            }
            return false
        }
    }

    class ScriptletRuleSet(val rules: List<ScriptletRule>) {
        
        fun scriptletsFor(host: String): List<ScriptletRule> {
            if (rules.isEmpty()) return emptyList()
            val blocked = HashSet<String>()
            for (r in rules) {
                if (r.isException && r.matchesHost(host)) {
                    blocked.add(r.name + "|" + r.args.joinToString(","))
                }
            }
            val out = ArrayList<ScriptletRule>()
            for (r in rules) {
                if (r.isException) continue
                if (!SUPPORTED_SCRIPTLETS.contains(r.name)) continue
                if (!r.matchesHost(host)) continue
                if (blocked.contains(r.name + "|" + r.args.joinToString(","))) continue
                out.add(r)
            }
            return out
        }
    }

    
    fun parseScriptletRule(line: String): ScriptletRule? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > AbpRuleEngine.MAX_RULE_LINE_LENGTH) return null

        // AdGuard: domain#%#//scriptlet('name', 'arg', ...)
        // Exception AdGuard: domain#@$#//scriptlet('name', ...)
        if (trimmed.contains("#%#") || trimmed.contains("#@$#//scriptlet")) {
            val marker = if (trimmed.contains("#@$#//scriptlet")) "#@$#" else "#%#"
            val isAdGuardException = marker == "#@$#"
            val parts = trimmed.split(marker, limit = 2)
            if (parts.size != 2) return null
            val body = parts[1].trim()
            if (!body.startsWith("//scriptlet(") || !body.endsWith(")")) return null
            val inner = body.removePrefix("//scriptlet(").removeSuffix(")")
            val tokens = splitArgs(inner) ?: return null
            if (tokens.isEmpty()) return null
            val name = tokens[0].trim().trim('\'', '"')
            if (!isValidName(name)) return null
            val args = tokens.drop(1).map { it.trim().trim('\'', '"') }
            if (args.size > MAX_SCRIPTLET_ARGS || args.any { it.length > MAX_SCRIPTLET_ARG_LENGTH }) return null
            return ScriptletRule(parseDomains(parts[0]), name, args, isAdGuardException)
        }

        val isException = trimmed.contains("#@#+js")
        val marker = if (isException) "#@#+js" else "##+js"
        if (!trimmed.contains(marker)) return null
        val parts = trimmed.split(marker, limit = 2)
        if (parts.size != 2) return null
        val body = parts[1].trim()
        // Bentuk uBO: +js(name, args) — kadang ditulis tanpa tanda + di depan js di beberapa daftar.
        val inner = when {
            body.startsWith("(") && body.endsWith(")") -> body.substring(1, body.length - 1)
            else -> return null
        }
        val tokens = splitArgs(inner) ?: return null
        if (tokens.isEmpty()) return null
        val name = tokens[0].trim().trim('\'', '"')
        if (!isValidName(name)) return null
        val args = tokens.drop(1).map { it.trim().trim('\'', '"') }
        if (args.size > MAX_SCRIPTLET_ARGS || args.any { it.length > MAX_SCRIPTLET_ARG_LENGTH }) return null
        return ScriptletRule(parseDomains(parts[0]), name, args, isException)
    }

    private fun parseDomains(raw: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (part in raw.split(',')) {
            val clean = AbpRuleEngine.cleanHost(part)
            if (clean.isNotEmpty() && clean != "~") {
                // Domain dengan prefix ~ berarti "kecuali domain ini" — untuk
                // kesederhanaan v1, domain itu diabaikan (aturan
                // tetap berlaku global/domain lain), bukan dibalik artinya.
                if (!part.trim().startsWith("~")) out.add(clean)
            }
        }
        return out
    }

    private fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_SCRIPTLET_NAME_LENGTH) return false
        return name.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    
    fun splitArgs(input: String): List<String>? {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (ch in input) {
            if (escaped) {
                current.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null else current.append(ch)
                continue
            }
            when (ch) {
                '\'', '"' -> quote = ch
                ',' -> {
                    out.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (quote != null) return null
        out.add(current.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() || out.size == 1 }
            .let { list ->
                // Pertahankan argumen kosong di tengah (mis. set-constant, foo, )
                // dengan cara yang sederhana: kembalikan apa adanya, kecuali
                // baris yang hanya berisi spasi.
                if (input.isBlank()) emptyList() else list
            }
    }

    
    fun jsonEscape(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                    else sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    
    fun buildInvocationJs(host: String, ruleSet: ScriptletRuleSet): String {
        val matched = ruleSet.scriptletsFor(host)
        if (matched.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("(function(){try{var L=window.__browserScriptlets;if(!L)return;")
        for (rule in matched) {
            val argsJs = rule.args.joinToString(",") { "\"${jsonEscape(it)}\"" }
            sb.append("try{if(typeof L['${rule.name}']==='function'){L['${rule.name}']($argsJs);}}catch(e){}")
        }
        sb.append("}catch(e){}})();")
        return sb.toString()
    }
}

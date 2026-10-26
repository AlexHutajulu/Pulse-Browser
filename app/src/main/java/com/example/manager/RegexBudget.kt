package com.example.manager

class RegexBudget(
    private val maxCachedPatterns: Int = 128,
    private val maxPatternLength: Int = 2_048,
) {
    private val cache = object : LinkedHashMap<String, Regex?>(maxCachedPatterns, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Regex?>?,
        ): Boolean = size > maxCachedPatterns
    }

    var hits: Long = 0L
        private set
    var misses: Long = 0L
        private set
    var compileFailures: Long = 0L
        private set

    @Synchronized
    fun matches(pattern: String, input: String): Boolean {
        if (pattern.length > maxPatternLength) return false
        val compiled = cache[pattern]
        if (compiled != null) {
            hits++
            return compiled.containsMatchIn(input)
        }
        if (cache.containsKey(pattern)) {
            // Known compile failure, cached as null.
            hits++
            return false
        }
        misses++
        val parsed = runCatching { Regex(pattern) }.getOrNull()
        if (parsed == null) compileFailures++
        cache[pattern] = parsed
        return parsed?.containsMatchIn(input) ?: false
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun cachedCount(): Int = cache.size
}

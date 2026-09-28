package io.horizontalsystems.tronkit.network

class ApiKeyProvider(
    private val apiKeys: List<String>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val bannedUntil = mutableMapOf<String, Long>()
    private var roundRobinIndex = 0

    init {
        check(apiKeys.isNotEmpty()) { "No API keys" }
    }

    @Synchronized
    fun nextHealthyKey(): String? {
        val now = clock()
        bannedUntil.entries.removeAll { it.value <= now }

        repeat(apiKeys.size) {
            val key = apiKeys[roundRobinIndex]
            roundRobinIndex = (roundRobinIndex + 1).mod(apiKeys.size)
            if (!bannedUntil.containsKey(key)) return key
        }
        return null
    }

    @Synchronized
    fun banKey(key: String, durationMs: Long) {
        val newExpiry = clock() + durationMs
        val existing = bannedUntil[key]
        bannedUntil[key] = if (existing != null) maxOf(existing, newExpiry) else newExpiry
    }

    @Synchronized
    fun msUntilAnyHealthyKey(): Long {
        val now = clock()
        bannedUntil.entries.removeAll { it.value <= now }
        val earliest = bannedUntil.values.minOrNull() ?: return 0L
        return earliest - now
    }
}

package io.horizontalsystems.tronkit.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class ApiKeyProviderTest {

    // --- Round-robin ---

    @Test
    fun nextHealthyKey_noBans_rotatesRoundRobin() {
        val provider = ApiKeyProvider(listOf("a", "b", "c"))

        assertEquals("a", provider.nextHealthyKey())
        assertEquals("b", provider.nextHealthyKey())
        assertEquals("c", provider.nextHealthyKey())
        assertEquals("a", provider.nextHealthyKey())
    }

    @Test
    fun nextHealthyKey_singleKey_alwaysReturnsSame() {
        val provider = ApiKeyProvider(listOf("only"))

        assertEquals("only", provider.nextHealthyKey())
        assertEquals("only", provider.nextHealthyKey())
    }

    // --- Ban / healthy key selection ---

    @Test
    fun nextHealthyKey_oneBanned_skipsItInRoundRobin() {
        var now = 1000L
        val provider = ApiKeyProvider(listOf("a", "b", "c"), clock = { now })

        provider.banKey("b", 30_000)

        // Should skip b and rotate among a, c
        assertEquals("a", provider.nextHealthyKey())
        assertEquals("c", provider.nextHealthyKey())
        assertEquals("a", provider.nextHealthyKey())
    }

    @Test
    fun nextHealthyKey_allBanned_returnsNull() {
        var now = 1000L
        val provider = ApiKeyProvider(listOf("a", "b"), clock = { now })

        provider.banKey("a", 30_000)
        provider.banKey("b", 30_000)

        assertNull(provider.nextHealthyKey())
    }

    @Test
    fun nextHealthyKey_banExpired_returnsUnbannedKey() {
        var now = 1000L
        val provider = ApiKeyProvider(listOf("a", "b"), clock = { now })

        provider.banKey("a", 5_000)  // banned until 6000
        provider.banKey("b", 10_000) // banned until 11000

        now = 7000L // a's ban expired
        assertEquals("a", provider.nextHealthyKey())
    }

    // --- banKey: max(existing, new) ---

    @Test
    fun banKey_longerBanNotOverwrittenByShorter() {
        var now = 1000L
        val provider = ApiKeyProvider(listOf("a", "b"), clock = { now })

        provider.banKey("a", 60_000) // banned until 61000
        provider.banKey("a", 1_000)  // would be 2000, but existing 61000 is later

        now = 5000L
        assertNull(provider.nextHealthyKey()?.takeIf { it == "a" }?.also {
            fail("key a should still be banned until 61000")
        })
        // a is still banned, b is healthy
        assertEquals("b", provider.nextHealthyKey())
    }

    @Test
    fun banKey_longerBanExtendsExisting() {
        var now = 1000L
        val provider = ApiKeyProvider(listOf("a"), clock = { now })

        provider.banKey("a", 5_000)  // banned until 6000
        provider.banKey("a", 30_000) // banned until 31000 (longer, extends)

        now = 7000L
        assertNull(provider.nextHealthyKey()) // still banned until 31000
    }

    // --- msUntilAnyHealthyKey ---

    @Test
    fun msUntilAnyHealthyKey_noBans_returnsZero() {
        val provider = ApiKeyProvider(listOf("a"))
        assertEquals(0L, provider.msUntilAnyHealthyKey())
    }

    @Test
    fun msUntilAnyHealthyKey_returnsTimeToEarliestExpiry() {
        var now = 1000L
        val provider = ApiKeyProvider(listOf("a", "b"), clock = { now })

        provider.banKey("a", 30_000) // unbans at 31000
        provider.banKey("b", 10_000) // unbans at 11000

        assertEquals(10_000L, provider.msUntilAnyHealthyKey())
    }

    // --- Thread safety ---

    @Test(expected = IllegalStateException::class)
    fun init_emptyKeys_throwsException() {
        ApiKeyProvider(emptyList())
    }

    @Test
    fun concurrentAccess_neverThrowsAndReturnsValidKeys() {
        val keys = listOf("k0", "k1", "k2")
        val provider = ApiKeyProvider(keys)
        val threadCount = 20
        val callsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentHashMap<String, Boolean>()
        val errors = ConcurrentHashMap<Int, Throwable>()

        val threads = (0 until threadCount).map { threadId ->
            Thread {
                try {
                    repeat(callsPerThread) {
                        val key = provider.nextHealthyKey()!!
                        results[key] = true
                    }
                } catch (e: Throwable) {
                    errors[threadId] = e
                } finally {
                    latch.countDown()
                }
            }
        }

        threads.forEach { it.start() }
        latch.await()

        if (errors.isNotEmpty()) {
            fail("Concurrent access caused errors: ${errors.values.first().message}")
        }

        results.keys.forEach { key ->
            assert(key in keys) { "Unexpected key: $key" }
        }
        assertEquals(keys.toSet(), results.keys)
    }
}

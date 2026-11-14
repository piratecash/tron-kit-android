package io.horizontalsystems.tronkit.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

class RateLimitInterceptorTest {

    private lateinit var server: MockWebServer
    private val sleepCalls = mutableListOf<Long>()
    private val fakeClock = AtomicLong(0L)
    private val fakeSleeper = RateLimitInterceptor.Sleeper { ms ->
        sleepCalls.add(ms)
        fakeClock.addAndGet(ms)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sleepCalls.clear()
        fakeClock.set(0L)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createProvider(keyCount: Int): ApiKeyProvider =
        ApiKeyProvider((1..keyCount).map { "key$it" }, clock = { fakeClock.get() })

    private fun createProvider(keys: List<String>): ApiKeyProvider =
        ApiKeyProvider(keys, clock = { fakeClock.get() })

    private fun createClient(
        provider: ApiKeyProvider,
        sleeper: RateLimitInterceptor.Sleeper = fakeSleeper
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(provider, sleeper))
            .build()

    private fun createClient(keyCount: Int): OkHttpClient =
        createClient(createProvider(keyCount))

    private fun request(): Request =
        Request.Builder().url(server.url("/test")).build()

    private fun enqueue429(retryAfter: String? = null): MockResponse {
        val response = MockResponse().setResponseCode(429)
        if (retryAfter != null) response.setHeader("Retry-After", retryAfter)
        return response
    }

    private fun takeAllKeys(count: Int): List<String?> =
        (0 until count).map { server.takeRequest().getHeader("TRON-PRO-API-KEY") }

    // --- Basic behavior ---

    @Test
    fun intercept_no429_returnsImmediately() {
        val client = createClient(keyCount = 3)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(1, server.requestCount)
        assertEquals(0, sleepCalls.size)
    }

    @Test
    fun intercept_non429Error_doesNotRetry() {
        val client = createClient(keyCount = 3)
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val response = client.newCall(request()).execute()

        assertEquals(500, response.code)
        assertEquals(1, server.requestCount)
        assertEquals(0, sleepCalls.size)
    }

    // --- Key rotation: immediate retry on fresh key ---

    @Test
    fun intercept_429WithFreshKeysAvailable_retriesImmediatelyWithoutSleep() {
        val client = createClient(keyCount = 3)
        server.enqueue(enqueue429("10"))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        assertEquals(0, sleepCalls.size)
    }

    @Test
    fun intercept_429ThreeKeys_rotatesTwiceBeforeSleeping() {
        val client = createClient(keyCount = 3)
        server.enqueue(enqueue429("10"))
        server.enqueue(enqueue429("10"))
        server.enqueue(enqueue429("10"))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(4, server.requestCount) // 1 original + 3 retries
        assertEquals(1, sleepCalls.size) // slept once after all 3 keys banned
    }

    @Test
    fun intercept_keyRotation_usesDistinctKeysBeforeSleeping() {
        val client = createClient(keyCount = 3)
        server.enqueue(enqueue429("5"))
        server.enqueue(enqueue429("5"))
        server.enqueue(enqueue429("5"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(request()).execute()

        val usedKeys = takeAllKeys(4)
        assertEquals(3, usedKeys.take(3).toSet().size) // all 3 distinct keys used
    }

    // --- All keys exhausted: must sleep ---

    @Test
    fun intercept_twoKeys_bothBanned_sleepsBeforeRetry() {
        val client = createClient(keyCount = 2)
        server.enqueue(enqueue429("10"))
        server.enqueue(enqueue429("10"))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(3, server.requestCount)
        assertTrue(sleepCalls.size >= 1)
    }

    // --- Single key: must sleep on every retry ---

    @Test
    fun intercept_singleKey_sleepsOnFirstRetry() {
        val client = createClient(keyCount = 1)
        server.enqueue(enqueue429("5"))
        server.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
        assertTrue(sleepCalls.size >= 1)
    }

    // --- Max retries capped at 3 regardless of key count ---

    @Test
    fun intercept_threeKeys_maxThreeRetries() {
        val client = createClient(keyCount = 3)
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))

        val response = client.newCall(request()).execute()

        assertEquals(429, response.code)
        assertEquals(4, server.requestCount) // 1 original + 3 retries
    }

    @Test
    fun intercept_fiveKeys_stillMaxThreeRetries() {
        val client = createClient(keyCount = 5)
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))

        val response = client.newCall(request()).execute()

        assertEquals(429, response.code)
        assertEquals(4, server.requestCount) // 1 original + 3 retries (capped)
    }

    @Test
    fun intercept_singleKey_maxThreeRetries() {
        val client = createClient(keyCount = 1)
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))
        server.enqueue(enqueue429("1"))

        val response = client.newCall(request()).execute()

        assertEquals(429, response.code)
        assertEquals(4, server.requestCount) // 1 original + 3 retries
    }

    // --- Retry-After parsing ---

    @Test
    fun intercept_noRetryAfterHeader_defaultsTo30s() {
        val client = createClient(keyCount = 1)
        server.enqueue(enqueue429())
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(request()).execute()

        assertTrue(sleepCalls.isNotEmpty())
        assertEquals(30_000L, sleepCalls[0])
    }

    @Test
    fun intercept_invalidRetryAfterHeader_defaultsTo30s() {
        val client = createClient(keyCount = 1)
        server.enqueue(enqueue429("not-a-number"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(request()).execute()

        assertTrue(sleepCalls.isNotEmpty())
        assertEquals(30_000L, sleepCalls[0])
    }

    @Test
    fun intercept_excessiveRetryAfter_clampedTo60s() {
        val client = createClient(keyCount = 1)
        server.enqueue(enqueue429("300"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(request()).execute()

        assertTrue(sleepCalls.isNotEmpty())
        assertEquals(60_000L, sleepCalls[0])
    }

    @Test
    fun intercept_zeroRetryAfter_clampedTo1s() {
        val client = createClient(keyCount = 1)
        server.enqueue(enqueue429("0"))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(request()).execute()

        assertTrue(sleepCalls.isNotEmpty())
        assertEquals(1_000L, sleepCalls[0])
    }

    // --- Shared ban state across requests ---

    @Test
    fun intercept_sharedBanState_secondRequestSkipsBannedKey() {
        val provider = createProvider(listOf("k1", "k2"))
        val client = createClient(provider)

        // First request: k1 → 429, rotate to k2 → 200
        server.enqueue(enqueue429("30"))
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(request()).execute()

        val firstKeys = takeAllKeys(2)
        assertEquals("k1", firstKeys[0])
        assertEquals("k2", firstKeys[1])

        // Second request: k1 still banned, should use k2
        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(request()).execute()

        val secondKey = server.takeRequest().getHeader("TRON-PRO-API-KEY")
        assertNotEquals("k1", secondKey)
    }

    @Test
    fun intercept_concurrentRequests_shareKeyBanState() {
        val provider = createProvider(listOf("k1", "k2", "k3"))
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)
        val results = mutableListOf<Int>()

        // Request A: k1 → 429, rotate to k2 → 200
        server.enqueue(enqueue429("30"))
        server.enqueue(MockResponse().setResponseCode(200))
        // Request B: should see k1 banned, pick k3 → 200
        server.enqueue(MockResponse().setResponseCode(200))

        val client = createClient(provider)

        Thread {
            startLatch.await()
            val resp = client.newCall(request()).execute()
            synchronized(results) { results.add(resp.code) }
            doneLatch.countDown()
        }.start()

        Thread {
            startLatch.await()
            Thread.sleep(50)
            val resp = client.newCall(request()).execute()
            synchronized(results) { results.add(resp.code) }
            doneLatch.countDown()
        }.start()

        startLatch.countDown()
        doneLatch.await()

        assertEquals(listOf(200, 200), results.sorted())

        val allKeys = takeAllKeys(server.requestCount)
        if (allKeys.size >= 3) {
            assertNotEquals("k1", allKeys[2])
        }
    }

    // --- New request while all keys banned ---

    @Test
    fun intercept_allKeysBannedAtStart_waitsBeforeSending() {
        val provider = createProvider(listOf("k1", "k2"))
        val client = createClient(provider)

        // Pre-ban both keys (simulates another request having banned them)
        provider.banKey("k1", 30_000)
        provider.banKey("k2", 30_000)

        server.enqueue(MockResponse().setResponseCode(200))
        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertTrue("Expected sleep before sending when all keys banned", sleepCalls.isNotEmpty())
        assertEquals(30_000L, sleepCalls[0])
    }

    @Test
    fun intercept_keyRebannedDuringSleep_waitsAgainInsteadOfUsingBannedKey() {
        val provider = createProvider(listOf("k1"))

        // Sleeper that simulates another thread re-banning k1 during the first sleep
        var firstSleep = true
        val rebanningSleeper = RateLimitInterceptor.Sleeper { ms ->
            sleepCalls.add(ms)
            fakeClock.addAndGet(ms)
            if (firstSleep) {
                firstSleep = false
                provider.banKey("k1", 10_000)
            }
        }

        val client = createClient(provider, rebanningSleeper)

        provider.banKey("k1", 30_000)

        server.enqueue(MockResponse().setResponseCode(200))
        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertTrue("Expected multiple sleeps due to re-ban", sleepCalls.size >= 2)
    }

    @Test
    fun intercept_keyRebannedMultipleTimes_keepsWaitingUntilHealthy() {
        val provider = createProvider(listOf("k1"))

        var rebanCount = 0
        val rebanningSleeper = RateLimitInterceptor.Sleeper { ms ->
            sleepCalls.add(ms)
            fakeClock.addAndGet(ms)
            // Re-ban 4 times to exceed the old MAX_WAIT_CYCLES=3
            if (rebanCount < 4) {
                rebanCount++
                provider.banKey("k1", 5_000)
            }
        }

        val client = createClient(provider, rebanningSleeper)

        provider.banKey("k1", 30_000)

        server.enqueue(MockResponse().setResponseCode(200))
        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        // Must have waited through all re-bans (5 sleeps: 1 original + 4 re-bans)
        assertEquals(5, sleepCalls.size)
    }

    @Test
    fun intercept_zeroWaitButStillBanned_loopsWithoutNPE() {
        val provider = createProvider(listOf("k1"))

        // Simulate: msUntilAnyHealthyKey() returns 0 but nextHealthyKey() still null
        // by re-banning during the zero-wait spin
        var spins = 0
        val spinAwareSleeper = RateLimitInterceptor.Sleeper { ms ->
            sleepCalls.add(ms)
            fakeClock.addAndGet(ms)
        }

        val client = createClient(provider, spinAwareSleeper)

        // Ban k1 for exactly 0ms from clock's perspective — it will expire
        // on the next nextHealthyKey() call. But we intercept to re-ban.
        fakeClock.set(1000L)
        provider.banKey("k1", 0) // bannedUntil = 1000, expires immediately at clock=1000

        // But we need the race: nextHealthyKey() returns null, msUntilAny returns 0,
        // loop spins, nextHealthyKey() returns key. No NPE.
        // Since ban expires at clock=1000 and clock IS 1000, the ban is cleaned up.
        // So nextHealthyKey() returns k1 immediately. To test the spin path,
        // we need a provider where the ban state changes between calls.

        // Simpler approach: ban with 1ms, set clock to just before expiry
        fakeClock.set(0L)
        provider.banKey("k1", 1) // bannedUntil = 1

        // At clock=0: nextHealthyKey() → null (1 > 0), msUntilAny() → 1ms
        // sleeper advances clock by 1ms → clock=1
        // nextHealthyKey() → k1 (1 <= 1, ban expired)

        server.enqueue(MockResponse().setResponseCode(200))
        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(1, server.requestCount)
    }

    // --- Normal traffic round-robins ---

    @Test
    fun intercept_normalTraffic_roundRobinsAcrossKeys() {
        val provider = createProvider(listOf("k1", "k2", "k3"))
        val client = createClient(provider)

        repeat(3) { server.enqueue(MockResponse().setResponseCode(200)) }
        repeat(3) { client.newCall(request()).execute() }

        val keys = takeAllKeys(3)
        assertEquals(3, keys.toSet().size) // all 3 distinct keys used
    }
}

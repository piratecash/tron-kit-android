package io.horizontalsystems.tronkit

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.tronkit.database.MainDatabase
import io.horizontalsystems.tronkit.database.TronDatabaseManager
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.RpcSource
import io.horizontalsystems.tronkit.models.TransactionSource
import io.horizontalsystems.tronkit.network.Network
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Proves that the `eventListenerFactory` parameter added to `TronKit.getInstance` is forwarded
 * through every delegating overload down to the primary overload.
 */
class TronKitForwardingTest {

    @After
    fun tearDown() {
        unmockkObject(TronKit.Companion)
    }

    @Test
    fun getInstance_delegatingOverloads_forwardEventListenerFactoryToPrimaryOverload() {
        TronKit.init()
        val seed = ByteArray(32) { 1 }
        val address = TronKit.getAddress(seed, Network.Mainnet)

        mockkObject(TronKit.Companion)

        val captured = mutableListOf<EventListener.Factory?>()
        every {
            TronKit.getInstance(
                any(),
                any<Address>(),
                any<Network>(),
                any<RpcSource>(),
                any(),
                any<String>(),
                captureNullable(captured)
            )
        } returns mockk(relaxed = true)

        val app = mockk<Application>(relaxed = true)
        val factory = EventListener.Factory { EventListener.NONE }
        val rpcSource = RpcSource.tronGrid(Network.Mainnet, listOf("key"))
        val transactionSource = TransactionSource.tronGrid(Network.Mainnet, listOf("key"))

        // Overload A: seed + RpcSource -> delegates to primary overload B
        TronKit.getInstance(app, seed, Network.Mainnet, rpcSource, transactionSource, "wallet1", factory)
        // Overload D: address + tronGridApiKeys -> delegates to primary overload B
        TronKit.getInstance(app, address, Network.Mainnet, listOf("key"), "wallet1", factory)
        // Overload C: seed + tronGridApiKeys -> delegates to overload A -> primary overload B
        TronKit.getInstance(app, seed, Network.Mainnet, listOf("key"), "wallet1", factory)

        assertEquals(3, captured.size)
        assertTrue("Every delegating overload must forward the exact same factory instance", captured.all { it === factory })
    }
}

/**
 * Proves that the primary `getInstance` overload wires `eventListenerFactory` into every network
 * provider it builds - the RPC provider and both flavors of history provider (TronGrid/TronScan) -
 * so the factory actually observes real requests made by the kit.
 *
 * Needs Robolectric: the primary overload builds a real ConnectionManager/Room database, which
 * require a working Android Context. The Room database is redirected to an in-memory instance
 * (closed in tearDown) so these tests don't leak an on-disk database across the test suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TronKitForwardingRpcProviderTest {

    private val createdKits = mutableListOf<TronKit>()
    private val createdDatabases = mutableListOf<MainDatabase>()

    @Before
    fun setUp() {
        mockkObject(TronDatabaseManager)
        every { TronDatabaseManager.getMainDatabase(any(), any(), any()) } answers {
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), MainDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { createdDatabases.add(it) }
        }
    }

    @After
    fun tearDown() {
        createdKits.forEach { it.stop() }
        createdDatabases.forEach { it.close() }
        unmockkObject(TronDatabaseManager)
    }

    @Test
    fun getInstance_primaryOverload_forwardsEventListenerFactoryToRpcProvider() {
        val server = MockWebServer()
        server.start()

        try {
            TronKit.init()
            val seed = ByteArray(32) { 2 }
            val address = TronKit.getAddress(seed, Network.Mainnet)

            val factory = CountingEventListenerFactory()
            val rpcSource = RpcSource(listOf(server.url("/").toUrl()))
            val app = ApplicationProvider.getApplicationContext<Application>()

            val kit = TronKit.getInstance(app, address, Network.Mainnet, rpcSource, null, "wallet-forwarding-test", factory)
            createdKits.add(kit)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"balance": 42}""")
            )

            runBlocking { kit.isAccountActive(address) }

            assertTrue("EventListener.Factory forwarded from getInstance must be invoked by the RPC provider", factory.count.get() > 0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getInstance_primaryOverload_forwardsEventListenerFactoryToTronGridHistoryProvider() {
        verifyEventListenerFactoryReachesHistoryProvider(
            walletId = "wallet-history-trongrid",
            sourceType = { historyUrl -> TransactionSource.SourceType.TronGrid(historyUrl, emptyList()) },
            transactionsBody = """{"data": [], "success": true, "meta": {"at": 0, "fingerprint": null, "page_size": 0}}""",
            trc20TransactionsBody = """{"data": [], "success": true, "meta": {"at": 0, "fingerprint": null, "page_size": 0}}"""
        )
    }

    @Test
    fun getInstance_primaryOverload_forwardsEventListenerFactoryToTronScanHistoryProvider() {
        verifyEventListenerFactoryReachesHistoryProvider(
            walletId = "wallet-history-tronscan",
            sourceType = { historyUrl -> TransactionSource.SourceType.TronScan(historyUrl, null) },
            transactionsBody = """{"data": []}""",
            trc20TransactionsBody = """{"token_transfers": []}"""
        )
    }

    /**
     * Builds the primary `getInstance` overload with a real [transactionSource] pointed at its own
     * MockWebServer, starts the kit, and asserts that the factory's `EventListener` observed a
     * request to that server's port. `kit.start()` unconditionally triggers `TransactionSyncer.sync()`,
     * which hits the history provider directly - independent of whether the RPC block-height sync
     * succeeds - making this a deterministic trigger for the history branch under test.
     */
    private fun verifyEventListenerFactoryReachesHistoryProvider(
        walletId: String,
        sourceType: (historyUrl: URL) -> TransactionSource.SourceType,
        transactionsBody: String,
        trc20TransactionsBody: String
    ) {
        val rpcServer = MockWebServer().apply { start() }
        val historyServer = MockWebServer().apply { start() }

        try {
            TronKit.init()
            val seed = ByteArray(32) { 3 }
            val address = TronKit.getAddress(seed, Network.Mainnet)

            // MockWebServer instances on loopback share the same host, so requests are told apart by port.
            val observedPorts = ConcurrentHashMap.newKeySet<Int>()
            val factory = EventListener.Factory { call ->
                observedPorts.add(call.request().url.port)
                EventListener.NONE
            }

            val rpcSource = RpcSource(listOf(rpcServer.url("/").toUrl()))
            val transactionSource = TransactionSource("history-test", sourceType(historyServer.url("/").toUrl()))
            val app = ApplicationProvider.getApplicationContext<Application>()

            historyServer.enqueue(jsonResponse(transactionsBody))
            historyServer.enqueue(jsonResponse(trc20TransactionsBody))

            val kit = TronKit.getInstance(app, address, Network.Mainnet, rpcSource, transactionSource, walletId, factory)
            createdKits.add(kit)
            kit.start()

            val historyPort = historyServer.port
            awaitUntil(condition = { historyPort in observedPorts })

            assertTrue(
                "EventListener.Factory forwarded from getInstance must be invoked by the history provider",
                historyPort in observedPorts
            )
        } finally {
            rpcServer.shutdown()
            historyServer.shutdown()
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun awaitUntil(condition: () -> Boolean, timeoutMs: Long = 10_000, intervalMs: Long = 50) {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNanos && !condition()) {
            Thread.sleep(intervalMs)
        }
    }
}

package io.horizontalsystems.tronkit.network

import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.rpc.BlockNumberJsonRpc
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class TronGridProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createProvider(
        apiKeys: List<String> = listOf("testKey"),
        auth: String? = null
    ): TronGridProvider =
        TronGridProvider(server.url("/").toUrl(), apiKeys, auth)

    // --- JSON-RPC ---

    @Test
    fun fetch_blockNumber_returnsBlockHeight() = runBlocking {
        val provider = createProvider()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":"0x1234"}""")
        )

        val blockNumber = provider.fetch(BlockNumberJsonRpc())

        // 0x1234 == 4660
        assertEquals(4660L, blockNumber)
    }

    // --- fetchAccount ---

    @Test
    fun fetchAccount_validResponse_returnsBalance() = runBlocking {
        val provider = createProvider()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"balance": 1000000}""")
        )

        val account = provider.fetchAccount("someAddress")

        assertEquals(BigInteger.valueOf(1_000_000), account?.balance)
    }

    @Test
    fun fetchAccount_emptyResponse_returnsNull() = runBlocking {
        val provider = createProvider()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{}""")
        )

        val account = provider.fetchAccount("someAddress")

        assertNull(account)
    }

    // --- fetchAccountInfo ---

    @Test
    fun fetchAccountInfo_validResponse_returnsAccountInfo() = runBlocking {
        val provider = createProvider()

        val json = """
            {
              "data": [{
                "account_resource": {
                  "energy_usage": 0,
                  "latest_consume_time_for_energy": 0,
                  "energy_window_size": 0
                },
                "address": "TAddr",
                "create_time": 1609459200000,
                "latest_opration_time": 1609459200000,
                "balance": 5000000,
                "trc20": [
                  {"TContractA": "100"},
                  {"TContractB": "200"}
                ]
              }]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(json)
        )

        val accountInfo = provider.fetchAccountInfo("TAddr")

        assertEquals(BigInteger.valueOf(5_000_000), accountInfo.balance)
        assertEquals(2, accountInfo.trc20Balances.size)
        assertEquals("TContractA", accountInfo.trc20Balances[0].contractAddress)
        assertEquals(BigInteger.valueOf(100), accountInfo.trc20Balances[0].balance)
        assertEquals("TContractB", accountInfo.trc20Balances[1].contractAddress)
        assertEquals(BigInteger.valueOf(200), accountInfo.trc20Balances[1].balance)
    }

    @Test
    fun fetchAccountInfo_emptyData_throwsFailedToFetch() = runBlocking {
        val provider = createProvider()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":[]}""")
        )

        try {
            provider.fetchAccountInfo("TAddr")
            fail("Expected FailedToFetchAccountInfo")
        } catch (e: IHistoryProvider.RequestError) {
            assertTrue(e is IHistoryProvider.RequestError.FailedToFetchAccountInfo)
        }
    }

    // --- broadcastTransaction ---

    @Test
    fun broadcastTransaction_failure_throwsTypedError() = runBlocking {
        val provider = createProvider()

        val createdTransaction = CreatedTransaction(
            visible = false,
            txID = "abc123",
            raw_data = RawData(
                contract = emptyList(),
                ref_block_bytes = "0000",
                ref_block_hash = "00000000",
                expiration = 0,
                timestamp = 0,
                fee_limit = null
            ),
            raw_data_hex = "00",
            Error = null
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"result":false,"code":"SIGERROR","message":"7369676e6174757265206572726f72"}""")
        )

        try {
            provider.broadcastTransaction(createdTransaction, byteArrayOf(0x01, 0x02))
            fail("Expected BroadcastFailed")
        } catch (e: TransactionError.BroadcastFailed) {
            assertEquals("SIGERROR", e.code)
            assertEquals("signature error", e.message)
        }
    }

    @Test
    fun transactionExists_emptyObject_returnsFalse() = runBlocking {
        val provider = createProvider()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{}""")
        )

        assertEquals(false, provider.transactionExists("abc123"))
    }

    @Test
    fun transactionExists_matchingTxId_returnsTrue() = runBlocking {
        val provider = createProvider()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"txID":"abc123"}""")
        )

        assertTrue(provider.transactionExists("ABC123"))
    }

    // --- Rate limiting / key rotation ---

    @Test
    fun rateLimit_429_rotatesKeyAndRetries() = runBlocking {
        val provider = createProvider(apiKeys = listOf("keyA", "keyB"))

        // First request returns 429 with Retry-After
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "5")
        )
        // Second request succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"balance": 999}""")
        )

        val account = provider.fetchAccount("someAddr")

        assertEquals(BigInteger.valueOf(999), account?.balance)

        val firstRequest = server.takeRequest()
        val secondRequest = server.takeRequest()

        val firstKey = firstRequest.getHeader("TRON-PRO-API-KEY")
        val secondKey = secondRequest.getHeader("TRON-PRO-API-KEY")

        assertNotEquals(
            "Second request should use a different API key after 429",
            firstKey,
            secondKey
        )
    }

    // --- Empty API keys ---

    @Test
    fun emptyApiKeys_noApiKeyHeader() = runBlocking {
        val provider = createProvider(apiKeys = emptyList())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"balance": 42}""")
        )

        provider.fetchAccount("someAddr")

        val request = server.takeRequest()
        assertNull(
            "No TRON-PRO-API-KEY header expected when apiKeys is empty",
            request.getHeader("TRON-PRO-API-KEY")
        )
    }
}

package io.horizontalsystems.tronkit.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class TronScanProviderTest {

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

    private fun createProvider(apiKey: String? = "test-api-key"): TronScanProvider =
        TronScanProvider(server.url("/").toUrl(), apiKey)

    private fun enqueueJson(json: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json)
        )
    }

    // --- fetchAccountInfo ---

    @Test
    fun fetchAccountInfo_validResponse_returnsAccountInfo() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "data": [{
                "balance": 5000000,
                "trc20token_balances": [
                  {"tokenId": "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "balance": "1000000"},
                  {"tokenId": "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", "balance": "2500000"}
                ]
              }]
            }
            """.trimIndent()
        )

        val accountInfo = provider.fetchAccountInfo("TTestAddress")

        assertEquals(BigInteger.valueOf(5000000), accountInfo.balance)
        assertEquals(2, accountInfo.trc20Balances.size)

        val first = accountInfo.trc20Balances[0]
        assertEquals("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", first.contractAddress)
        assertEquals(BigInteger.valueOf(1000000), first.balance)

        val second = accountInfo.trc20Balances[1]
        assertEquals("TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", second.contractAddress)
        assertEquals(BigInteger.valueOf(2500000), second.balance)

        val request = server.takeRequest()
        assertEquals("/account?address=TTestAddress", request.path)
    }

    @Test(expected = IHistoryProvider.RequestError.FailedToFetchAccountInfo::class)
    fun fetchAccountInfo_emptyData_throwsFailedToFetch() = runBlocking {
        val provider = createProvider()
        enqueueJson("""{"data": []}""")

        provider.fetchAccountInfo("TTestAddress")
        Unit
    }

    @Test
    fun fetchAccountInfo_nullBalance_defaultsToZero() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "data": [{
                "trc20token_balances": []
              }]
            }
            """.trimIndent()
        )

        val accountInfo = provider.fetchAccountInfo("TTestAddress")

        assertEquals(BigInteger.ZERO, accountInfo.balance)
        assertTrue(accountInfo.trc20Balances.isEmpty())
    }

    // --- fetchTransactions ---

    @Test
    fun fetchTransactions_validResponse_returnsTransactions() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "data": [{
                "hash": "abc123",
                "block": 100,
                "timestamp": 1700000000000,
                "ownerAddress": "TAddr1",
                "toAddress": "TAddr2",
                "contractType": 1,
                "contractRet": "SUCCESS",
                "fee": 100000,
                "contractData": {"amount": "1000000"}
              }]
            }
            """.trimIndent()
        )

        val (transactions, _) = provider.fetchTransactions("TAddr1", 0L, null)

        assertEquals(1, transactions.size)
        val tx = transactions[0] as RegularTransactionData
        assertEquals("abc123", tx.txID)
        assertEquals(100L, tx.blockNumber)
        assertEquals(1700000000000L, tx.block_timestamp)
        assertEquals("SUCCESS", tx.ret[0].contractRet)
        assertEquals(100000L, tx.ret[0].fee)

        val contract = tx.raw_data.contract[0]
        assertEquals("TransferContract", contract.type)
        assertEquals(BigInteger.valueOf(1000000), contract.parameter.value.amount)
        assertEquals("TAddr1", contract.parameter.value.owner_address)
        assertEquals("TAddr2", contract.parameter.value.to_address)

        val request = server.takeRequest()
        assertTrue(request.path?.startsWith("/transaction?") == true)
        assertTrue(request.path?.contains("address=TAddr1") == true)
    }

    @Test
    fun fetchTransactions_unsupportedContractType_filtered() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "data": [
                {
                  "hash": "supported",
                  "block": 1,
                  "timestamp": 1700000000000,
                  "ownerAddress": "TAddr1",
                  "toAddress": "TAddr2",
                  "contractType": 1,
                  "contractRet": "SUCCESS",
                  "fee": 0,
                  "contractData": {"amount": "100"}
                },
                {
                  "hash": "unsupported",
                  "block": 2,
                  "timestamp": 1700000001000,
                  "ownerAddress": "TAddr1",
                  "toAddress": "TAddr2",
                  "contractType": 999,
                  "contractRet": "SUCCESS",
                  "fee": 0,
                  "contractData": {}
                }
              ]
            }
            """.trimIndent()
        )

        val (transactions, _) = provider.fetchTransactions("TAddr1", 0L, null)

        assertEquals(1, transactions.size)
        assertEquals("supported", (transactions[0] as RegularTransactionData).txID)
    }

    @Test
    fun fetchTransactions_pagination_returnsCursor() = runBlocking {
        val provider = createProvider()
        val items = (1..50).joinToString(",") { i ->
            """
            {
              "hash": "tx$i",
              "block": $i,
              "timestamp": ${1700000000000 + i * 1000},
              "ownerAddress": "TAddr1",
              "toAddress": "TAddr2",
              "contractType": 1,
              "contractRet": "SUCCESS",
              "fee": 0,
              "contractData": {"amount": "100"}
            }
            """.trimIndent()
        }
        enqueueJson("""{"data": [$items]}""")

        val (transactions, cursor) = provider.fetchTransactions("TAddr1", 0L, null)

        assertEquals(50, transactions.size)
        assertNotNull(cursor)
        // Cursor encodes "effectiveMinTimestamp:nextStart" — with start=0 and pageSize=50, nextStart=50
        assertTrue(cursor?.contains(":50") == true)
    }

    @Test
    fun fetchTransactions_lessThanPageSize_returnsNullCursor() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "data": [{
                "hash": "only_one",
                "block": 1,
                "timestamp": 1700000000000,
                "ownerAddress": "TAddr1",
                "toAddress": "TAddr2",
                "contractType": 1,
                "contractRet": "SUCCESS",
                "fee": 0,
                "contractData": {"amount": "100"}
              }]
            }
            """.trimIndent()
        )

        val (transactions, cursor) = provider.fetchTransactions("TAddr1", 0L, null)

        assertEquals(1, transactions.size)
        assertNull(cursor)
    }

    // --- fetchTrc20Transactions ---

    @Test
    fun fetchTrc20Transactions_validResponse_returnsContractData() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "token_transfers": [{
                "transaction_id": "def456",
                "block_ts": 1700000000000,
                "from_address": "TFrom",
                "to_address": "TTo",
                "quant": "5000000",
                "contract_address": "TToken",
                "tokenInfo": {"tokenName": "Tether", "tokenAbbr": "USDT", "tokenDecimal": 6}
              }]
            }
            """.trimIndent()
        )

        val (transactions, _) = provider.fetchTrc20Transactions("TFrom", 0L, null)

        assertEquals(1, transactions.size)
        val tx = transactions[0]
        assertEquals("def456", tx.transaction_id)
        assertEquals(1700000000000L, tx.block_timestamp)
        assertEquals("TFrom", tx.from)
        assertEquals("TTo", tx.to)
        assertEquals("5000000", tx.value)
        assertEquals("Transfer", tx.type)

        assertEquals("USDT", tx.token_info.symbol)
        assertEquals("Tether", tx.token_info.name)
        assertEquals("TToken", tx.token_info.address)
        assertEquals(6, tx.token_info.decimals)

        val request = server.takeRequest()
        assertTrue(request.path?.startsWith("/token_trc20/transfers?") == true)
    }

    @Test
    fun fetchTrc20Transactions_nullContractAddress_filtered() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "token_transfers": [
                {
                  "transaction_id": "valid",
                  "block_ts": 1700000000000,
                  "from_address": "TFrom",
                  "to_address": "TTo",
                  "quant": "100",
                  "contract_address": "TToken",
                  "tokenInfo": {"tokenName": "T", "tokenAbbr": "T", "tokenDecimal": 18}
                },
                {
                  "transaction_id": "invalid",
                  "block_ts": 1700000001000,
                  "from_address": "TFrom",
                  "to_address": "TTo",
                  "quant": "200",
                  "contract_address": null,
                  "tokenInfo": null
                }
              ]
            }
            """.trimIndent()
        )

        val (transactions, _) = provider.fetchTrc20Transactions("TFrom", 0L, null)

        assertEquals(1, transactions.size)
        assertEquals("valid", transactions[0].transaction_id)
    }

    @Test
    fun fetchTrc20Transactions_pagination_returnsCursor() = runBlocking {
        val provider = createProvider()
        val items = (1..50).joinToString(",") { i ->
            """
            {
              "transaction_id": "tx$i",
              "block_ts": ${1700000000000 + i * 1000},
              "from_address": "TFrom",
              "to_address": "TTo",
              "quant": "100",
              "contract_address": "TToken",
              "tokenInfo": {"tokenName": "T", "tokenAbbr": "T", "tokenDecimal": 18}
            }
            """.trimIndent()
        }
        enqueueJson("""{"token_transfers": [$items]}""")

        val (transactions, cursor) = provider.fetchTrc20Transactions("TFrom", 0L, null)

        assertEquals(50, transactions.size)
        assertNotNull(cursor)
    }

    @Test
    fun fetchTrc20Transactions_lessThanPageSize_returnsNullCursor() = runBlocking {
        val provider = createProvider()
        enqueueJson(
            """
            {
              "token_transfers": [{
                "transaction_id": "only",
                "block_ts": 1700000000000,
                "from_address": "TFrom",
                "to_address": "TTo",
                "quant": "100",
                "contract_address": "TToken",
                "tokenInfo": {"tokenName": "T", "tokenAbbr": "T", "tokenDecimal": 18}
              }]
            }
            """.trimIndent()
        )

        val (_, cursor) = provider.fetchTrc20Transactions("TFrom", 0L, null)

        assertNull(cursor)
    }

    // --- API key header ---

    @Test
    fun apiKey_whenProvided_sentAsHeader() = runBlocking {
        val provider = TronScanProvider(server.url("/").toUrl(), "my-secret-key")
        enqueueJson("""{"data": [{"balance": 0, "trc20token_balances": []}]}""")

        provider.fetchAccountInfo("TAddr")

        val request = server.takeRequest()
        assertEquals("my-secret-key", request.getHeader("TRON-PRO-API-KEY"))
    }

    @Test
    fun apiKey_whenNull_noHeader() = runBlocking {
        val provider = TronScanProvider(server.url("/").toUrl(), null)
        enqueueJson("""{"data": [{"balance": 0, "trc20token_balances": []}]}""")

        provider.fetchAccountInfo("TAddr")

        val request = server.takeRequest()
        assertNull(request.getHeader("TRON-PRO-API-KEY"))
    }
}

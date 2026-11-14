package io.horizontalsystems.tronkit.transaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.horizontalsystems.tronkit.database.MainDatabase
import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.decoration.DecorationManager
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.Transaction
import io.horizontalsystems.tronkit.network.TronGridProvider
import io.horizontalsystems.tronkit.network.TronScanProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionManagerReceiptTest {

    private val server = MockWebServer()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MainDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val storage = Storage(database)
    private val owner = Address.fromHex("41ce7ba9c4618bc93f00c6673f73042fc0a33f1b62")
    private val transactionManager = TransactionManager(
        owner,
        storage,
        DecorationManager(storage),
        Gson(),
        TronGridProvider(server.url("/").toUrl(), emptyList())
    )

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    @Test
    fun saveTransactionData_tronScanHistory_storesUnknownResourcesAsNull() = runBlocking {
        syncFromTronScan()

        val row = storedRow()
        assertNull(row.netUsage)
        assertNull(row.netFee)
        assertNull(row.energyUsage)
        assertNull(row.energyFee)
        assertNull(row.energyUsageTotal)
        assertEquals(100_000L, row.fee)
    }

    @Test
    fun fetchReceipt_notInBlockYet_returnsFalseWithoutWrite() = runBlocking {
        syncFromTronScan()
        enqueueJson("{}")

        val fetched = transactionManager.fetchReceipt(HASH)

        assertFalse(fetched)
        assertNull(storedRow().netUsage)
    }

    @Test
    fun fetchReceipt_partialReceipt_storesMissingKeysAsZeroAndReemits() = runBlocking {
        syncFromTronScan()
        enqueueJson("""{"id": "$HASH", "fee": 345000, "blockNumber": 77, "receipt": {"net_fee": 345000}}""")

        val fetched = transactionManager.fetchReceipt(HASH)

        assertTrue(fetched)
        val row = storedRow()
        assertEquals(345_000L, row.fee)
        assertEquals(0L, row.netUsage)
        assertEquals(345_000L, row.netFee)
        assertEquals(0L, row.energyUsage)
        assertEquals(0L, row.energyFee)
        assertEquals(0L, row.originEnergyUsage)
        assertEquals(0L, row.energyUsageTotal)
        val emitted = transactionManager.transactionsFlow.value.first.single().transaction
        assertEquals(HASH, emitted.hashString)
        assertEquals(345_000L, emitted.netFee)
    }

    @Test
    fun saveTransactionData_resyncWithoutReceipt_keepsStoredReceipt() = runBlocking {
        syncFromTronScan()
        enqueueJson(
            """
            {"id": "$HASH", "fee": 0, "blockNumber": 77,
             "receipt": {"net_usage": 345, "energy_usage": 1000, "origin_energy_usage": 2000, "energy_usage_total": 3000}}
            """.trimIndent()
        )
        transactionManager.fetchReceipt(HASH)

        syncFromTronScan()

        val row = storedRow()
        assertEquals(345L, row.netUsage)
        assertEquals(0L, row.netFee)
        assertEquals(1_000L, row.energyUsage)
        assertEquals(0L, row.energyFee)
        assertEquals(2_000L, row.originEnergyUsage)
        assertEquals(3_000L, row.energyUsageTotal)
    }

    private suspend fun syncFromTronScan() {
        enqueueJson(
            """
            {
              "data": [{
                "hash": "$HASH",
                "block": 77,
                "timestamp": 1700000000000,
                "ownerAddress": "${owner.hex}",
                "toAddress": "410a38028ed6146aa29c687c052b233131468b6635",
                "contractType": 1,
                "contractRet": "SUCCESS",
                "fee": 100000,
                "contractData": {"amount": "1000000"}
              }]
            }
            """.trimIndent()
        )
        val (transactions, _) = TronScanProvider(server.url("/").toUrl(), null).fetchTransactions(owner.base58, 0L, null)
        transactionManager.saveTransactionData(transactions, confirmed = true)
    }

    private fun storedRow(): Transaction = storage.getTransactions(listOf(HASH.hexStringToByteArray())).single()

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private companion object {
        const val HASH = "aa00000000000000000000000000000000000000000000000000000000000001"
    }
}

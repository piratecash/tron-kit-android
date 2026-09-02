package io.horizontalsystems.tronkit.sync

import io.horizontalsystems.tronkit.TronKit.SyncError
import io.horizontalsystems.tronkit.TronKit.SyncState
import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.network.ContractTransactionData
import io.horizontalsystems.tronkit.network.IHistoryProvider
import io.horizontalsystems.tronkit.network.TokenInfo
import io.horizontalsystems.tronkit.network.TransactionData
import io.horizontalsystems.tronkit.transaction.TransactionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionSyncerTest {

    private val historyProvider = mockk<IHistoryProvider>()
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val storage = mockk<Storage>(relaxed = true)
    private val address = mockk<Address> {
        every { base58 } returns "TTestAddress123456789"
    }

    private lateinit var syncer: TransactionSyncer

    @Before
    fun setup() {
        syncer = TransactionSyncer(historyProvider, transactionManager, storage, address)
    }

    // -- Helpers --

    private fun stubTimestamps(native: Long?, contract: Long?) {
        every { storage.getTransactionSyncBlockTimestamp() } returns native
        every { storage.getContractTransactionSyncBlockTimestamp() } returns contract
    }

    private fun fakeTransactionData(timestamp: Long): TransactionData {
        return mockk<TransactionData> {
            every { block_timestamp } returns timestamp
        }
    }

    private fun fakeContractTransactionData(timestamp: Long) = ContractTransactionData(
        transaction_id = "abc123",
        token_info = TokenInfo(symbol = "USDT", address = "TTokenAddr", decimals = 6, name = "Tether"),
        block_timestamp = timestamp,
        from = "TFrom",
        to = "TTo",
        type = "Transfer",
        value = "1000000"
    )

    private fun stubEmptyResponses() {
        coEvery {
            historyProvider.fetchTransactions(any(), any(), any())
        } returns Pair(emptyList(), null)

        coEvery {
            historyProvider.fetchTrc20Transactions(any(), any(), any())
        } returns Pair(emptyList(), null)
    }

    private fun startAndSync(scope: TestScope) {
        syncer.start(scope)
        syncer.sync()
    }

    // -- Tests --

    @Test
    fun syncState_initially_isNotSynced() {
        val state = syncer.syncState
        assertTrue(state is SyncState.NotSynced)
        assertTrue((state as SyncState.NotSynced).error is SyncError.NotStarted)
    }

    @Test
    fun sync_initialTimestamps_fetchesFromZeroPlusOffset() = runTest {
        stubTimestamps(native = null, contract = null)
        stubEmptyResponses()

        startAndSync(this)
        advanceUntilIdle()

        coVerify {
            historyProvider.fetchTransactions(
                address = "TTestAddress123456789",
                minTimestamp = 1000L,
                cursor = null
            )
        }
        coVerify {
            historyProvider.fetchTrc20Transactions(
                address = "TTestAddress123456789",
                minTimestamp = 1000L,
                cursor = null
            )
        }
    }

    @Test
    fun sync_existingTimestamp_fetchesFromTimestampPlusOffset() = runTest {
        stubTimestamps(native = 5000L, contract = 8000L)
        stubEmptyResponses()

        startAndSync(this)
        advanceUntilIdle()

        coVerify {
            historyProvider.fetchTransactions(
                address = "TTestAddress123456789",
                minTimestamp = 6000L,
                cursor = null
            )
        }
        coVerify {
            historyProvider.fetchTrc20Transactions(
                address = "TTestAddress123456789",
                minTimestamp = 9000L,
                cursor = null
            )
        }
    }

    @Test
    fun sync_paginatedResponse_fetchesAllPages() = runTest {
        stubTimestamps(native = 0L, contract = 0L)

        val page1Tx = listOf(fakeTransactionData(2000L))
        val page2Tx = listOf(fakeTransactionData(3000L))

        coEvery {
            historyProvider.fetchTransactions("TTestAddress123456789", 1000L, null)
        } returns Pair(page1Tx, "cursor_page2")

        coEvery {
            historyProvider.fetchTransactions("TTestAddress123456789", 1000L, "cursor_page2")
        } returns Pair(page2Tx, null)

        coEvery {
            historyProvider.fetchTrc20Transactions(any(), any(), any())
        } returns Pair(emptyList(), null)

        startAndSync(this)
        advanceUntilIdle()

        coVerify(exactly = 2) {
            historyProvider.fetchTransactions(any(), any(), any())
        }
        verify {
            transactionManager.saveTransactionData(page1Tx, confirmed = true)
            transactionManager.saveTransactionData(page2Tx, confirmed = true)
        }
    }

    @Test
    fun sync_totalExceedsMax_stopsAfterLimit() = runTest {
        stubTimestamps(native = 0L, contract = 0L)

        // Each page returns 600 items; after 2 pages (1200) we exceed MAX_TRANSACTION_COUNT (1000)
        val largePage = (1..600).map { fakeTransactionData(it.toLong()) }

        coEvery {
            historyProvider.fetchTransactions("TTestAddress123456789", 1000L, null)
        } returns Pair(largePage, "next_cursor")

        coEvery {
            historyProvider.fetchTransactions("TTestAddress123456789", 1000L, "next_cursor")
        } returns Pair(largePage, "yet_another_cursor")

        coEvery {
            historyProvider.fetchTrc20Transactions(any(), any(), any())
        } returns Pair(emptyList(), null)

        startAndSync(this)
        advanceUntilIdle()

        // Should stop after 2 pages (1200 >= 1000), not fetch a third
        coVerify(exactly = 2) {
            historyProvider.fetchTransactions(any(), any(), any())
        }
    }

    @Test
    fun sync_emptyResponse_doesNotSaveTimestamp() = runTest {
        stubTimestamps(native = 5000L, contract = 5000L)
        stubEmptyResponses()

        startAndSync(this)
        advanceUntilIdle()

        verify(exactly = 0) { storage.saveTransactionSyncTimestamp(any()) }
        verify(exactly = 0) { storage.saveContractTransactionSyncTimestamp(any()) }
    }

    @Test
    fun sync_success_callsProcessWithInitialTrue() = runTest {
        // Both timestamps null means they default to 0, so initial = true
        stubTimestamps(native = null, contract = null)
        stubEmptyResponses()

        startAndSync(this)
        advanceUntilIdle()

        verify { transactionManager.process(initial = true) }
    }

    @Test
    fun sync_success_callsProcessWithInitialFalse() = runTest {
        // Both timestamps are non-zero, so initial = false
        stubTimestamps(native = 5000L, contract = 8000L)
        stubEmptyResponses()

        startAndSync(this)
        advanceUntilIdle()

        verify { transactionManager.process(initial = false) }
    }

    @Test
    fun sync_failure_setsSyncStateNotSynced() = runTest {
        stubTimestamps(native = null, contract = null)

        val error = RuntimeException("network failure")
        coEvery {
            historyProvider.fetchTransactions(any(), any(), any())
        } throws error

        startAndSync(this)
        advanceUntilIdle()

        val state = syncer.syncState
        assertTrue("Expected NotSynced but was $state", state is SyncState.NotSynced)
        assertTrue(
            "Expected original error",
            (state as SyncState.NotSynced).error === error
        )
    }
}

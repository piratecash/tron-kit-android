package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.tronkit.models.RawTransactionRetryMetadata
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.network.RawData
import io.horizontalsystems.tronkit.network.SignedTransaction
import io.horizontalsystems.tronkit.toRawHexString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RawTransactionBroadcasterTest {
    private interface TestNodeApiProvider : INodeApiProvider

    private val nodeApiProvider = mockk<TestNodeApiProvider>()
    private val storage = mockk<Storage>(relaxed = true)
    private var now = NOW
    private val broadcaster = RawTransactionBroadcaster(
        nodeApiProvider = nodeApiProvider,
        storage = storage,
        currentTimeProvider = { now },
        retryIntervalMs = 1_000,
        networkTimeoutMs = 1_000
    )

    @Test
    fun broadcast_success_returnsSubmittedWithoutQueue() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } returns TX_ID

        val result = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(TX_ID, result.txId)
        assertEquals(RawTransactionBroadcastStatus.Submitted, result.status)
        coVerify(exactly = 0) { nodeApiProvider.transactionExists(any()) }
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
    }

    @Test
    fun broadcast_knownSubmitted_returnsAlreadyKnownWithoutQueue() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws TransactionError.BroadcastFailed(
            code = "DUP_TRANSACTION_ERROR",
            message = "duplicate",
            txId = null
        )

        val result = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(RawTransactionBroadcastStatus.AlreadyKnown, result.status)
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
    }

    @Test
    fun broadcast_matchingTxIdError_returnsAlreadyKnownWithoutQueue() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws TransactionError.BroadcastFailed(
            code = "UNKNOWN_ERROR",
            message = "already known",
            txId = TX_ID
        )

        val result = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(RawTransactionBroadcastStatus.AlreadyKnown, result.status)
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
    }

    @Test
    fun broadcast_transientWithMetadata_queues() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws IOException("network")

        val result = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(RawTransactionBroadcastStatus.Queued, result.status)
        verify {
            storage.insertRawTransactionBroadcastRecord(
                match { it.txId == TX_ID && it.expiration == EXPIRATION && it.retriesCount == 0 }
            )
        }
    }

    @Test
    fun broadcast_transientWithoutMetadata_throwsWithoutQueue() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws IOException("network")

        val error = try {
            broadcaster.broadcast(RAW_TRANSACTION, null)
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is IOException)
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
    }

    @Test
    fun broadcast_expired_throwsWithoutQueue() = runTest {
        now = EXPIRATION

        val error = try {
            broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.RawTransactionExpired)
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
        coVerify(exactly = 0) { nodeApiProvider.broadcastTransaction(any()) }
    }

    @Test
    fun broadcast_signatureCode_isPermanent() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws TransactionError.BroadcastFailed(
            code = "SIGERROR",
            message = "bad signature",
            txId = null
        )

        val error = try {
            broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.BroadcastFailed)
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
    }

    @Test
    fun broadcast_expirationCode_isPermanent() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws TransactionError.BroadcastFailed(
            code = "TRANSACTION_EXPIRATION_ERROR",
            message = "expired",
            txId = null
        )

        val error = try {
            broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.BroadcastFailed)
        verify(exactly = 0) { storage.insertRawTransactionBroadcastRecord(any()) }
    }

    @Test
    fun broadcast_serverBusyCode_queues() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws TransactionError.BroadcastFailed(
            code = "SERVER_BUSY",
            message = "busy",
            txId = null
        )

        val result = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(RawTransactionBroadcastStatus.Queued, result.status)
        verify { storage.insertRawTransactionBroadcastRecord(match { it.txId == TX_ID }) }
    }

    @Test
    fun broadcast_hangingRequest_timesOutQueuesAndAllowsNextAttempt() = runTest {
        coEvery { nodeApiProvider.broadcastTransaction(any()) } coAnswers {
            delay(2_000)
            TX_ID
        }

        val queued = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(RawTransactionBroadcastStatus.Queued, queued.status)
        verify { storage.insertRawTransactionBroadcastRecord(match { it.txId == TX_ID }) }

        coEvery { nodeApiProvider.broadcastTransaction(any()) } returns TX_ID

        val submitted = broadcaster.broadcast(RAW_TRANSACTION, RawTransactionRetryMetadata(EXPIRATION))

        assertEquals(RawTransactionBroadcastStatus.Submitted, submitted.status)
    }

    @Test
    fun retry_existingOnChain_deletesWithoutBroadcast() = runTest {
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD)
        coEvery { nodeApiProvider.transactionExists(TX_ID) } returns true

        broadcaster.retryQueued()

        verify { storage.deleteRawTransactionBroadcastRecord(TX_ID) }
        coVerify(exactly = 0) { nodeApiProvider.broadcastTransaction(any()) }
    }

    @Test
    fun retry_success_deletesRecord() = runTest {
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD)
        coEvery { nodeApiProvider.transactionExists(TX_ID) } returns false
        coEvery { nodeApiProvider.broadcastTransaction(any()) } returns TX_ID

        broadcaster.retryQueued()

        verify { storage.deleteRawTransactionBroadcastRecord(TX_ID) }
    }

    @Test
    fun retry_expired_deletesWithoutBroadcast() = runTest {
        now = EXPIRATION
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD)

        broadcaster.retryQueued()

        verify { storage.deleteExpiredRawTransactionBroadcastRecords(EXPIRATION) }
        coVerify(exactly = 0) { nodeApiProvider.broadcastTransaction(any()) }
    }

    @Test
    fun retry_transient_updatesRetryState() = runTest {
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD)
        every { storage.updateRawTransactionBroadcastRecord(any()) } just runs
        coEvery { nodeApiProvider.transactionExists(TX_ID) } returns false
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws IOException("network")

        broadcaster.retryQueued()

        verify {
            storage.updateRawTransactionBroadcastRecord(
                match { it.txId == TX_ID && it.retriesCount == 1 && it.lastAttemptAt == NOW }
            )
        }
    }

    @Test
    fun retry_permanentCode_deletesRecord() = runTest {
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD)
        coEvery { nodeApiProvider.transactionExists(TX_ID) } returns false
        coEvery { nodeApiProvider.broadcastTransaction(any()) } throws TransactionError.BroadcastFailed(
            code = "CONTRACT_VALIDATE_ERROR",
            message = "invalid contract",
            txId = null
        )

        broadcaster.retryQueued()

        verify { storage.deleteRawTransactionBroadcastRecord(TX_ID) }
    }

    @Test
    fun retry_hangingRequest_updatesRetryStateAndContinuesBatch() = runTest {
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD, RECORD_2)
        every { storage.updateRawTransactionBroadcastRecord(any()) } just runs
        coEvery { nodeApiProvider.transactionExists(any()) } returns false
        coEvery { nodeApiProvider.broadcastTransaction(match { it.txID == TX_ID }) } coAnswers {
            delay(2_000)
            TX_ID
        }
        coEvery { nodeApiProvider.broadcastTransaction(match { it.txID == TX_ID_2 }) } returns TX_ID_2

        broadcaster.retryQueued()

        verify { storage.updateRawTransactionBroadcastRecord(match { it.txId == TX_ID && it.retriesCount == 1 }) }
        verify { storage.deleteRawTransactionBroadcastRecord(TX_ID_2) }
    }

    @Test
    fun retryQueued_alreadyRunning_skipsConcurrentCall() = runTest {
        every { storage.rawTransactionBroadcastRecordsDue(any(), any()) } returns listOf(RECORD)
        every { storage.updateRawTransactionBroadcastRecord(any()) } just runs
        coEvery { nodeApiProvider.transactionExists(TX_ID) } coAnswers {
            delay(2_000)
            false
        }

        val retryJob = launch { broadcaster.retryQueued() }
        runCurrent()
        broadcaster.retryQueued()
        advanceUntilIdle()
        retryJob.join()

        verify(exactly = 1) { storage.rawTransactionBroadcastRecordsDue(any(), any()) }
        coVerify(exactly = 1) { nodeApiProvider.transactionExists(TX_ID) }
    }

    companion object {
        private const val RAW_DATA_HEX = "0a020001"
        private const val RAW_DATA_HEX_2 = "0a020002"
        private val TX_ID = Utils.sha256(RAW_DATA_HEX.hexStringToByteArray()).toRawHexString()
        private val TX_ID_2 = Utils.sha256(RAW_DATA_HEX_2.hexStringToByteArray()).toRawHexString()
        private const val EXPIRATION = 1_700_000_300_000L
        private const val NOW = 1_700_000_000_000L
        private val SIGNATURE = "01".repeat(65)
        private val RAW_TRANSACTION = rawTransaction(TX_ID, RAW_DATA_HEX)
        private val RAW_TRANSACTION_2 = rawTransaction(TX_ID_2, RAW_DATA_HEX_2)
        private val RECORD = RawTransactionBroadcastRecord(
            txId = TX_ID,
            rawTransaction = RAW_TRANSACTION,
            expiration = EXPIRATION,
            createdAt = NOW,
            lastAttemptAt = NOW - 2_000,
            retriesCount = 0
        )
        private val RECORD_2 = RawTransactionBroadcastRecord(
            txId = TX_ID_2,
            rawTransaction = RAW_TRANSACTION_2,
            expiration = EXPIRATION,
            createdAt = NOW,
            lastAttemptAt = NOW - 2_000,
            retriesCount = 0
        )

        private fun rawTransaction(txId: String, rawDataHex: String) = RawTransactionUtils.encode(
            SignedTransaction(
                visible = false,
                txID = txId,
                raw_data = RawData(
                    contract = emptyList(),
                    ref_block_bytes = "0000",
                    ref_block_hash = "00000000",
                    expiration = EXPIRATION,
                    timestamp = NOW,
                    fee_limit = null
                ),
                raw_data_hex = rawDataHex,
                signature = listOf(SIGNATURE)
            )
        ).raw
    }
}

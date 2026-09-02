package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastResult
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.tronkit.models.RawTransactionRetryMetadata
import io.horizontalsystems.tronkit.network.INodeApiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RawTransactionBroadcaster(
    private val nodeApiProvider: INodeApiProvider,
    private val storage: Storage,
    private val currentTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val retryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS,
    private val networkTimeoutMs: Long = DEFAULT_NETWORK_TIMEOUT_MS
) {
    private val inFlightTxIds = ConcurrentHashMap.newKeySet<String>()
    private val retryRunning = AtomicBoolean(false)

    suspend fun broadcast(
        rawTransaction: ByteArray,
        retryMetadata: RawTransactionRetryMetadata?
    ): RawTransactionBroadcastResult {
        val decoded = RawTransactionUtils.decode(rawTransaction)
        validateRetryMetadata(decoded, retryMetadata)

        if (!inFlightTxIds.add(decoded.txId)) {
            return RawTransactionBroadcastResult(decoded.txId, RawTransactionBroadcastStatus.Queued)
        }

        return try {
            broadcastDecoded(rawTransaction, decoded, retryMetadata)
        } finally {
            inFlightTxIds.remove(decoded.txId)
        }
    }

    suspend fun retryQueued() {
        if (!retryRunning.compareAndSet(false, true)) return

        try {
            val now = currentTimeProvider()
            storage.deleteExpiredRawTransactionBroadcastRecords(now)
            val records = storage.rawTransactionBroadcastRecordsDue(
                lastAttemptBefore = now - retryIntervalMs,
                now = now
            )

            records.forEach { retry(it) }
        } finally {
            retryRunning.set(false)
        }
    }

    private suspend fun broadcastDecoded(
        rawTransaction: ByteArray,
        decoded: DecodedRawTransaction,
        retryMetadata: RawTransactionRetryMetadata?
    ): RawTransactionBroadcastResult {
        val now = currentTimeProvider()
        if (now >= decoded.expiration) throw TransactionError.RawTransactionExpired(decoded.txId, decoded.expiration)

        return try {
            send(decoded)
            RawTransactionBroadcastResult(decoded.txId, RawTransactionBroadcastStatus.Submitted)
        } catch (error: TimeoutCancellationException) {
            handleBroadcastError(error, decoded, rawTransaction, retryMetadata, now)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleBroadcastError(error, decoded, rawTransaction, retryMetadata, now)
        }
    }

    private suspend fun retry(record: RawTransactionBroadcastRecord) {
        if (!inFlightTxIds.add(record.txId)) return

        try {
            val decoded = try {
                RawTransactionUtils.decode(record.rawTransaction)
            } catch (error: Throwable) {
                storage.deleteRawTransactionBroadcastRecord(record.txId)
                return
            }

            val now = currentTimeProvider()
            if (now >= decoded.expiration) {
                storage.deleteRawTransactionBroadcastRecord(record.txId)
                return
            }

            try {
                if (transactionExists(decoded.txId)) {
                    storage.deleteRawTransactionBroadcastRecord(record.txId)
                } else {
                    send(decoded)
                    storage.deleteRawTransactionBroadcastRecord(record.txId)
                }
            } catch (error: TimeoutCancellationException) {
                storage.updateRawTransactionBroadcastRecord(record.retried(now))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                when {
                    error.isKnownSubmitted(decoded.txId) || error.isPermanent() ->
                        storage.deleteRawTransactionBroadcastRecord(record.txId)
                    else -> storage.updateRawTransactionBroadcastRecord(record.retried(now))
                }
            }
        } finally {
            inFlightTxIds.remove(record.txId)
        }
    }

    private suspend fun handleBroadcastError(
        error: Throwable,
        decoded: DecodedRawTransaction,
        rawTransaction: ByteArray,
        retryMetadata: RawTransactionRetryMetadata?,
        now: Long
    ): RawTransactionBroadcastResult {
        if (error.isKnownSubmitted(decoded.txId)) {
            return RawTransactionBroadcastResult(decoded.txId, RawTransactionBroadcastStatus.AlreadyKnown)
        }

        if (error.isPermanent() || retryMetadata == null) throw error

        storage.insertRawTransactionBroadcastRecord(
            RawTransactionBroadcastRecord(
                txId = decoded.txId,
                rawTransaction = rawTransaction.copyOf(),
                expiration = decoded.expiration,
                createdAt = now,
                lastAttemptAt = now,
                retriesCount = 0
            )
        )

        return RawTransactionBroadcastResult(decoded.txId, RawTransactionBroadcastStatus.Queued)
    }

    private suspend fun send(decoded: DecodedRawTransaction) {
        val rpcTxId = withTimeout(networkTimeoutMs) {
            nodeApiProvider.broadcastTransaction(decoded.signedTransaction)
        }

        if (rpcTxId.isNotBlank() && !rpcTxId.equals(decoded.txId, ignoreCase = true)) {
            throw TransactionError.InvalidRawTransaction("Broadcast response txid does not match raw transaction")
        }
    }

    private suspend fun transactionExists(txId: String): Boolean {
        return withTimeout(networkTimeoutMs) { nodeApiProvider.transactionExists(txId) }
    }

    private fun validateRetryMetadata(
        decoded: DecodedRawTransaction,
        retryMetadata: RawTransactionRetryMetadata?
    ) {
        if (retryMetadata != null && retryMetadata.expiration != decoded.expiration) {
            throw TransactionError.InvalidRawTransaction("Retry metadata expiration does not match raw transaction")
        }
    }

    private fun Throwable.isKnownSubmitted(txId: String): Boolean {
        return this is TransactionError.BroadcastFailed &&
                (code == CODE_DUP_TRANSACTION || this.txId?.equals(txId, ignoreCase = true) == true)
    }

    private fun Throwable.isPermanent(): Boolean {
        return this is TransactionError.InvalidRawTransaction ||
                this is TransactionError.RawTransactionExpired ||
                this is TransactionError.BroadcastFailed && code in PERMANENT_CODES
    }

    companion object {
        private const val DEFAULT_RETRY_INTERVAL_MS = 60_000L
        private const val DEFAULT_NETWORK_TIMEOUT_MS = 30_000L

        private const val CODE_DUP_TRANSACTION = "DUP_TRANSACTION_ERROR"

        private val PERMANENT_CODES = setOf(
            "TRANSACTION_EXPIRATION_ERROR",
            "SIGERROR",
            "CONTRACT_VALIDATE_ERROR"
        )
    }
}

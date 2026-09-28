package io.horizontalsystems.tronkit.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
class RawTransactionBroadcastRecord(
    @PrimaryKey val txId: String,
    val rawTransaction: ByteArray,
    val expiration: Long,
    val createdAt: Long,
    val lastAttemptAt: Long?,
    val retriesCount: Int
) {
    fun retried(now: Long) = RawTransactionBroadcastRecord(
        txId = txId,
        rawTransaction = rawTransaction,
        expiration = expiration,
        createdAt = createdAt,
        lastAttemptAt = now,
        retriesCount = retriesCount + 1
    )

    override fun equals(other: Any?): Boolean {
        return this === other ||
                other is RawTransactionBroadcastRecord &&
                txId == other.txId &&
                rawTransaction.contentEquals(other.rawTransaction) &&
                expiration == other.expiration &&
                createdAt == other.createdAt &&
                lastAttemptAt == other.lastAttemptAt &&
                retriesCount == other.retriesCount
    }

    override fun hashCode(): Int {
        var result = txId.hashCode()
        result = 31 * result + rawTransaction.contentHashCode()
        result = 31 * result + expiration.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (lastAttemptAt?.hashCode() ?: 0)
        result = 31 * result + retriesCount
        return result
    }
}

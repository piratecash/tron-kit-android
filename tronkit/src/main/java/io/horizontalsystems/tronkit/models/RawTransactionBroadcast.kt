package io.horizontalsystems.tronkit.models

class SignedRawTronTransaction(
    val raw: ByteArray,
    val txId: String,
    val expiration: Long
) {
    override fun equals(other: Any?): Boolean {
        return this === other ||
                other is SignedRawTronTransaction &&
                raw.contentEquals(other.raw) &&
                txId == other.txId &&
                expiration == other.expiration
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + txId.hashCode()
        result = 31 * result + expiration.hashCode()
        return result
    }
}

data class RawTransactionRetryMetadata(
    val expiration: Long
)

data class RawTransactionBroadcastResult(
    val txId: String,
    val status: RawTransactionBroadcastStatus
)

enum class RawTransactionBroadcastStatus {
    Submitted,
    Queued,
    AlreadyKnown
}

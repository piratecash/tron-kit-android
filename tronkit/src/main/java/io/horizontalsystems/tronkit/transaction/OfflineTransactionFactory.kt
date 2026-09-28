package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.toRawHexString
import org.tron.protos.Protocol.Transaction

/**
 * Re-stamps a node-built [CreatedTransaction] with a later expiration so it can be signed offline
 * and broadcast within a wider window than the node's short default. Only the expiration changes:
 * the ref block, contract, timestamp and fee limit are preserved. The protobuf `raw_data` is
 * re-serialized and `txID` is recomputed as `sha256(raw_data)` so the result stays internally
 * consistent and valid to sign. Pure — no network, no wall clock (the new expiration is derived
 * from the transaction's own [Transaction.raw.timestamp], set by the node when it built the tx).
 */
internal fun CreatedTransaction.withExtendedExpiration(expirationDurationMs: Long): CreatedTransaction {
    require(expirationDurationMs > 0) { "expirationDurationMs must be positive" }

    val raw = Transaction.raw.parseFrom(raw_data_hex.hexStringToByteArray())
    require(raw.timestamp > 0) { "Created transaction has no timestamp" }

    val newExpiration = raw.timestamp + expirationDurationMs
    require(newExpiration > raw.expiration) {
        "expirationDurationMs must extend the transaction beyond its current expiration"
    }
    val newBytes = raw.toBuilder().setExpiration(newExpiration).build().toByteArray()

    return copy(
        txID = Utils.sha256(newBytes).toRawHexString(),
        raw_data = raw_data.copy(expiration = newExpiration),
        raw_data_hex = newBytes.toRawHexString(),
    )
}

package io.horizontalsystems.tronkit.network

import io.horizontalsystems.tronkit.toRawHexString

internal fun CreatedTransaction.signedTransaction(signature: ByteArray) = SignedTransaction(
    visible = visible,
    txID = txID,
    raw_data = raw_data,
    raw_data_hex = raw_data_hex,
    signature = listOf(signature.toRawHexString())
)

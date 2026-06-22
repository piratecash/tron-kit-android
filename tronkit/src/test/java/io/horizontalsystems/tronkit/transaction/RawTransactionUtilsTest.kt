package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.network.RawData
import io.horizontalsystems.tronkit.network.SignedTransaction
import io.horizontalsystems.tronkit.toRawHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RawTransactionUtilsTest {
    @Test
    fun encode_validSignedTransaction_returnsRawTxIdAndExpiration() {
        val signedTransaction = signedTransaction()

        val raw = RawTransactionUtils.encode(signedTransaction)

        assertEquals(signedTransaction.txID, raw.txId)
        assertEquals(EXPIRATION, raw.expiration)
        assertTrue(String(raw.raw).contains("\"visible\":false"))
    }

    @Test
    fun decode_validRaw_returnsSignedTransactionMetadata() {
        val signedTransaction = signedTransaction()
        val encoded = RawTransactionUtils.encode(signedTransaction)

        val decoded = RawTransactionUtils.decode(encoded.raw)

        assertEquals(signedTransaction.txID, decoded.txId)
        assertEquals(EXPIRATION, decoded.expiration)
        assertEquals(signedTransaction.signature, decoded.signedTransaction.signature)
    }

    @Test
    fun decode_txIdMismatch_throwsInvalidRawTransaction() {
        val signedTransaction = signedTransaction(txId = "00".repeat(32))

        val error = try {
            RawTransactionUtils.decode(json(signedTransaction).toByteArray())
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.InvalidRawTransaction)
    }

    @Test
    fun decode_emptyBytes_throwsInvalidRawTransaction() {
        val error = try {
            RawTransactionUtils.decode(byteArrayOf())
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.InvalidRawTransaction)
    }

    @Test
    fun decode_malformedTxId_throwsInvalidRawTransaction() {
        val signedTransaction = signedTransaction(txId = "not-a-tx-id")

        val error = try {
            RawTransactionUtils.decode(json(signedTransaction).toByteArray())
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.InvalidRawTransaction)
    }

    @Test
    fun decode_missingSignature_throwsInvalidRawTransaction() {
        val raw = json(signedTransaction()).replace(",\"signature\":[\"$SIGNATURE\"]", "")

        val error = try {
            RawTransactionUtils.decode(raw.toByteArray())
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.InvalidRawTransaction)
    }

    @Test
    fun decode_malformedSignature_throwsInvalidRawTransaction() {
        val signedTransaction = signedTransaction(signature = "abcd")

        val error = try {
            RawTransactionUtils.decode(json(signedTransaction).toByteArray())
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.InvalidRawTransaction)
    }

    @Test
    fun decode_invalidJson_throwsInvalidRawTransaction() {
        val error = try {
            RawTransactionUtils.decode("not-json".toByteArray())
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(error is TransactionError.InvalidRawTransaction)
    }

    private fun signedTransaction(
        txId: String = TX_ID,
        signature: String = SIGNATURE
    ) = SignedTransaction(
        visible = false,
        txID = txId,
        raw_data = RawData(
            contract = emptyList(),
            ref_block_bytes = "0000",
            ref_block_hash = "00000000",
            expiration = EXPIRATION,
            timestamp = 1_700_000_000_000,
            fee_limit = null
        ),
        raw_data_hex = RAW_DATA_HEX,
        signature = listOf(signature)
    )

    private fun json(signedTransaction: SignedTransaction): String {
        val signature = signedTransaction.signature.singleOrNull() ?: fail("Expected one signature")
        return """
            {"visible":${signedTransaction.visible},"txID":"${signedTransaction.txID}","raw_data":{"contract":[],"ref_block_bytes":"0000","ref_block_hash":"00000000","expiration":$EXPIRATION,"timestamp":1700000000000},"raw_data_hex":"$RAW_DATA_HEX","signature":["$signature"]}
        """.trimIndent()
    }

    companion object {
        private const val RAW_DATA_HEX = "0a020001"
        private val TX_ID = Utils.sha256(RAW_DATA_HEX.hexStringToByteArray()).toRawHexString()
        private const val EXPIRATION = 1_700_000_300_000L
        private val SIGNATURE = "01".repeat(65)
    }
}

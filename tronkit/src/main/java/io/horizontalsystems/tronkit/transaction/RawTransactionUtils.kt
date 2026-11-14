package io.horizontalsystems.tronkit.transaction

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.SignedRawTronTransaction
import io.horizontalsystems.tronkit.network.SignedTransaction
import io.horizontalsystems.tronkit.toRawHexString

internal data class DecodedRawTransaction(
    val signedTransaction: SignedTransaction,
    val txId: String,
    val expiration: Long
)

internal object RawTransactionUtils {
    private const val TX_ID_HEX_LENGTH = 64
    private const val SIGNATURE_HEX_LENGTH = 130

    private val gson = Gson()

    fun encode(signedTransaction: SignedTransaction): SignedRawTronTransaction {
        val txId = validateTxId(signedTransaction.txID)
        val expiration = validateExpiration(signedTransaction.raw_data.expiration)
        validateRawDataHex(signedTransaction.raw_data_hex, txId)
        validateSignatures(signedTransaction.signature)

        return SignedRawTronTransaction(
            raw = gson.toJson(signedTransaction).toByteArray(Charsets.UTF_8),
            txId = txId,
            expiration = expiration
        )
    }

    fun decode(rawTransaction: ByteArray): DecodedRawTransaction {
        if (rawTransaction.isEmpty()) invalid("Raw transaction is empty")

        val json = try {
            JsonParser.parseString(rawTransaction.toString(Charsets.UTF_8)).asJsonObject
        } catch (error: Throwable) {
            invalid("Raw transaction is not valid signed transaction JSON")
        }

        requireVisible(json)
        val txId = validateTxId(json.requiredString("txID"))
        val rawDataHex = json.requiredString("raw_data_hex")
        val rawData = json.requiredObject("raw_data")
        val expiration = validateExpiration(rawData.requiredLong("expiration"))
        val signatures = json.requiredStringList("signature")

        validateRawDataHex(rawDataHex, txId)
        validateSignatures(signatures)

        val signedTransaction = try {
            gson.fromJson(json, SignedTransaction::class.java)
        } catch (error: Throwable) {
            invalid("Raw transaction JSON does not match signed transaction schema")
        }

        return DecodedRawTransaction(signedTransaction, txId, expiration)
    }

    private fun validateTxId(txId: String): String {
        val normalized = txId.lowercase()
        if (!normalized.isStrictHex(TX_ID_HEX_LENGTH)) invalid("Invalid transaction id")
        return normalized
    }

    private fun validateExpiration(expiration: Long): Long {
        if (expiration <= 0) invalid("Invalid transaction expiration")
        return expiration
    }

    private fun validateRawDataHex(rawDataHex: String, txId: String) {
        if (!rawDataHex.isStrictHex() || rawDataHex.length % 2 != 0) invalid("Invalid raw_data_hex")

        val calculatedTxId = Utils.sha256(rawDataHex.hexStringToByteArray()).toRawHexString()
        if (calculatedTxId != txId) invalid("Transaction id does not match raw_data_hex")
    }

    private fun validateSignatures(signatures: List<String>) {
        if (signatures.isEmpty()) invalid("Missing transaction signature")
        if (signatures.any { !it.isStrictHex(SIGNATURE_HEX_LENGTH) }) invalid("Invalid transaction signature")
    }

    private fun requireVisible(json: JsonObject) {
        val visible = json.get("visible") ?: invalid("Missing visible field")
        if (!visible.isJsonPrimitive || !visible.asJsonPrimitive.isBoolean) invalid("Invalid visible field")
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name) ?: invalid("Missing $name")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) invalid("Invalid $name")
        return value.asString
    }

    private fun JsonObject.requiredLong(name: String): Long {
        val value = get(name) ?: invalid("Missing $name")
        return try {
            value.asLong
        } catch (error: Throwable) {
            invalid("Invalid $name")
        }
    }

    private fun JsonObject.requiredObject(name: String): JsonObject {
        val value = get(name) ?: invalid("Missing $name")
        if (!value.isJsonObject) invalid("Invalid $name")
        return value.asJsonObject
    }

    private fun JsonObject.requiredStringList(name: String): List<String> {
        val value = get(name) ?: invalid("Missing $name")
        if (!value.isJsonArray) invalid("Invalid $name")

        return value.asJsonArray.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) invalid("Invalid $name")
            item.asString
        }
    }

    private fun String.isStrictHex(length: Int? = null): Boolean {
        if (length != null && this.length != length) return false
        return isNotEmpty() && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun invalid(message: String): Nothing {
        throw TransactionError.InvalidRawTransaction(message)
    }
}

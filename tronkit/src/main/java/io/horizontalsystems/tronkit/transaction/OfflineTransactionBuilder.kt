package io.horizontalsystems.tronkit.transaction

import com.google.protobuf.ByteString
import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.Contract
import io.horizontalsystems.tronkit.models.TransferContract
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.ContractRaw
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.Parameter
import io.horizontalsystems.tronkit.network.RawData
import io.horizontalsystems.tronkit.network.Value
import io.horizontalsystems.tronkit.toRawHexString
import org.tron.protos.Protocol.Transaction
import java.math.BigInteger

/**
 * Assembles a signable Tron transaction locally, with NO network, from a recent block anchor
 * (fetched earlier while online). This is what makes account-based Tron offline signing possible:
 * the only network-dependent input — the TAPOS block reference — is captured up front and reused.
 *
 * The result mirrors a node-built transaction: `raw_data_hex` is the protobuf `Transaction.raw`,
 * `raw_data` is the matching JSON model (including the contract, which the node uses to
 * reconstruct the transaction on broadcast), and `txID = sha256(raw_data)` — exactly what [Signer]
 * signs and what the node validates.
 */
internal object OfflineTransactionBuilder {

    fun build(
        contract: Contract,
        refBlockNumber: Long,
        refBlockHashHex: String,
        timestamp: Long,
        expiration: Long,
        feeLimit: Long?,
    ): CreatedTransaction {
        require(refBlockNumber >= 0) { "refBlockNumber must be non-negative" }
        require(timestamp > 0) { "timestamp must be positive" }
        require(expiration > timestamp) { "expiration must be after timestamp" }
        // Smart-contract calls consume Energy; a missing/zero fee limit is accepted by the node but
        // then fails OUT_OF_ENERGY, moving nothing. Plain TRX transfers need no fee limit.
        require(contract !is TriggerSmartContract || (feeLimit != null && feeLimit > 0)) {
            "feeLimit is required for smart contract calls"
        }
        requireInt64Amounts(contract)

        val refBlockBytes = refBlockBytes(refBlockNumber)
        val refBlockHash = refBlockHash(refBlockHashHex)

        val rawBuilder = Transaction.raw.newBuilder()
            .setRefBlockBytes(ByteString.copyFrom(refBlockBytes))
            .setRefBlockHash(ByteString.copyFrom(refBlockHash))
            .setExpiration(expiration)
            .setTimestamp(timestamp)
            .addContract(contract.proto)
        feeLimit?.let { rawBuilder.setFeeLimit(it) }
        val rawBytes = rawBuilder.build().toByteArray()

        return CreatedTransaction(
            visible = false,
            txID = Utils.sha256(rawBytes).toRawHexString(),
            raw_data = RawData(
                contract = listOf(contractRaw(contract)),
                ref_block_bytes = refBlockBytes.toRawHexString(),
                ref_block_hash = refBlockHash.toRawHexString(),
                expiration = expiration,
                timestamp = timestamp,
                fee_limit = feeLimit,
            ),
            raw_data_hex = rawBytes.toRawHexString(),
            Error = null,
        )
    }

    private fun contractRaw(contract: Contract): ContractRaw = when (contract) {
        is TransferContract -> ContractRaw(
            type = "TransferContract",
            parameter = Parameter(
                value = value(
                    amount = contract.amount,
                    owner_address = contract.ownerAddress.hex,
                    to_address = contract.toAddress.hex,
                ),
                type_url = "type.googleapis.com/protocol.TransferContract",
            ),
        )

        is TriggerSmartContract -> ContractRaw(
            type = "TriggerSmartContract",
            parameter = Parameter(
                value = value(
                    owner_address = contract.ownerAddress.hex,
                    contract_address = contract.contractAddress.hex,
                    data = contract.data,
                    call_value = contract.callValue,
                    call_token_value = contract.callTokenValue,
                    token_id = contract.tokenId,
                ),
                type_url = "type.googleapis.com/protocol.TriggerSmartContract",
            ),
        )

        else -> throw IllegalArgumentException(
            "Unsupported offline contract: ${contract.javaClass.simpleName}"
        )
    }

    @Suppress("LongParameterList")
    private fun value(
        amount: BigInteger? = null,
        owner_address: String? = null,
        to_address: String? = null,
        contract_address: String? = null,
        data: String? = null,
        call_value: BigInteger? = null,
        call_token_value: BigInteger? = null,
        token_id: Int? = null,
    ) = Value(
        amount = amount,
        owner_address = owner_address,
        to_address = to_address,
        asset_name = null,
        withdraw_amount = null,
        data = data,
        contract_address = contract_address,
        call_value = call_value,
        call_token_value = call_token_value,
        token_id = token_id,
        total_supply = null,
        precision = null,
        name = null,
        description = null,
        abbr = null,
        url = null,
        resource = null,
        unfreeze_balance = null,
        frozen_balance = null,
        votes = null,
    )

    // raw_data_hex narrows amounts through toLong() (Contract.proto), while raw_data keeps the
    // BigInteger. Guard the values that feed both so the JSON and the signed protobuf can never
    // diverge — a value >= 2^63 would (JSON keeps it; the proto int64 wraps negative).
    private fun requireInt64Amounts(contract: Contract) {
        when (contract) {
            is TransferContract -> contract.amount.requireNonNegativeInt64("amount")
            is TriggerSmartContract -> {
                contract.callValue?.requireNonNegativeInt64("callValue")
                contract.callTokenValue?.requireNonNegativeInt64("callTokenValue")
            }
            else -> Unit
        }
    }

    private fun BigInteger.requireNonNegativeInt64(name: String) {
        require(signum() >= 0 && bitLength() <= Long.SIZE_BITS - 1) {
            "$name does not fit a non-negative int64"
        }
    }

    /** TAPOS ref_block_bytes: the low 2 bytes of the 8-byte big-endian block number. */
    private fun refBlockBytes(blockNumber: Long): ByteArray {
        val full = ByteArray(Long.SIZE_BYTES)
        for (i in 0 until Long.SIZE_BYTES) {
            full[Long.SIZE_BYTES - 1 - i] = (blockNumber ushr (i * Byte.SIZE_BITS)).toByte()
        }
        return full.copyOfRange(REF_BLOCK_BYTES_START, REF_BLOCK_BYTES_END)
    }

    /** TAPOS ref_block_hash: bytes [8, 16) of the 32-byte block id. */
    private fun refBlockHash(blockHashHex: String): ByteArray {
        val bytes = blockHashHex.hexStringToByteArray()
        require(bytes.size >= REF_BLOCK_HASH_END) { "Invalid block id length: ${bytes.size}" }
        return bytes.copyOfRange(REF_BLOCK_HASH_START, REF_BLOCK_HASH_END)
    }

    private const val REF_BLOCK_BYTES_START = 6
    private const val REF_BLOCK_BYTES_END = 8
    private const val REF_BLOCK_HASH_START = 8
    private const val REF_BLOCK_HASH_END = 16
}

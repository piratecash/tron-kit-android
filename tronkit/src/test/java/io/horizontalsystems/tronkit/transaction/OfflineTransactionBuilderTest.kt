package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.Contract
import io.horizontalsystems.tronkit.models.TransferContract
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.toRawHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tron.protos.Protocol.Transaction
import java.math.BigInteger

class OfflineTransactionBuilderTest {

    @Test
    fun build_encodesRefBlockBytes_asLowTwoBytesOfBlockNumber() {
        // 0x0000000001234567 -> low 2 bytes -> 0x4567
        assertEquals("4567", parseRaw(build()).refBlockBytes.toByteArray().toRawHexString())
    }

    @Test
    fun build_encodesRefBlockHash_asBytes8To16OfBlockId() {
        assertEquals("89abcdef01234567", parseRaw(build()).refBlockHash.toByteArray().toRawHexString())
    }

    @Test
    fun build_txId_equalsSha256OfRawData() {
        val created = build()
        assertEquals(Utils.sha256(created.raw_data_hex.hexStringToByteArray()).toRawHexString(), created.txID)
    }

    @Test
    fun build_roundTrip_preservesTimeAndFeeFields() {
        val created = build(feeLimit = FEE_LIMIT)
        val raw = parseRaw(created)
        assertEquals(TIMESTAMP, raw.timestamp)
        assertEquals(EXPIRATION, raw.expiration)
        assertEquals(FEE_LIMIT, raw.feeLimit)
        assertEquals(EXPIRATION, created.raw_data.expiration)
        assertEquals(FEE_LIMIT, created.raw_data.fee_limit)
    }

    @Test
    fun build_preservesContract() {
        val contract = transferContract()
        val raw = parseRaw(build(contract = contract))
        assertEquals(1, raw.contractCount)
        assertEquals(contract.proto, raw.getContract(0))
    }

    @Test
    fun build_populatesRawDataContract_forTransfer() {
        // The node reconstructs the broadcast tx from raw_data.contract, so it must be present.
        val contract = build().raw_data.contract.single()
        assertEquals("TransferContract", contract.type)
        assertEquals("type.googleapis.com/protocol.TransferContract", contract.parameter.type_url)
        assertEquals(BigInteger.valueOf(1_000_000), contract.parameter.value.amount)
        assertEquals(OWNER, contract.parameter.value.owner_address)
        assertEquals(TO, contract.parameter.value.to_address)
    }

    @Test
    fun build_populatesRawDataContract_forTriggerSmartContract() {
        val contract = build(contract = triggerContract(), feeLimit = FEE_LIMIT).raw_data.contract.single()
        assertEquals("TriggerSmartContract", contract.type)
        assertEquals("type.googleapis.com/protocol.TriggerSmartContract", contract.parameter.type_url)
        assertEquals(OWNER, contract.parameter.value.owner_address)
        assertEquals(TO, contract.parameter.value.contract_address)
        assertEquals(DATA, contract.parameter.value.data)
    }

    @Test
    fun build_triggerSmartContract_withoutPositiveFeeLimit_throws() {
        assertIllegalArgument { build(contract = triggerContract(), feeLimit = null) }
        assertIllegalArgument { build(contract = triggerContract(), feeLimit = 0) }
    }

    @Test
    fun build_nullFeeLimit_leavesFeeLimitUnset() {
        assertEquals(0L, parseRaw(build(feeLimit = null)).feeLimit)
        assertNull(build(feeLimit = null).raw_data.fee_limit)
    }

    @Test
    fun build_expirationNotAfterTimestamp_throws() {
        assertIllegalArgument { build(expiration = TIMESTAMP) }
        assertIllegalArgument { build(expiration = TIMESTAMP - 1) }
    }

    @Test
    fun build_nonPositiveTimestamp_throws() {
        assertIllegalArgument { build(timestamp = 0) }
    }

    @Test
    fun build_amountExceedingInt64_throws() {
        // Would make raw_data (BigInteger) and raw_data_hex (int64 via toLong) diverge.
        val contract = TransferContract(
            amount = BigInteger.TWO.pow(63),
            ownerAddress = Address.fromHex(OWNER),
            toAddress = Address.fromHex(TO),
        )
        assertIllegalArgument { build(contract = contract) }
    }

    private fun build(
        contract: Contract = transferContract(),
        timestamp: Long = TIMESTAMP,
        expiration: Long = EXPIRATION,
        feeLimit: Long? = null,
    ) = OfflineTransactionBuilder.build(
        contract = contract,
        refBlockNumber = BLOCK_NUMBER,
        refBlockHashHex = BLOCK_ID,
        timestamp = timestamp,
        expiration = expiration,
        feeLimit = feeLimit,
    )

    private fun transferContract() = TransferContract(
        amount = BigInteger.valueOf(1_000_000),
        ownerAddress = Address.fromHex(OWNER),
        toAddress = Address.fromHex(TO),
    )

    private fun triggerContract() = TriggerSmartContract(
        data = DATA,
        ownerAddress = Address.fromHex(OWNER),
        contractAddress = Address.fromHex(TO),
        callValue = null,
        callTokenValue = null,
        tokenId = null,
    )

    private fun parseRaw(created: CreatedTransaction): Transaction.raw =
        Transaction.raw.parseFrom(created.raw_data_hex.hexStringToByteArray())

    private fun assertIllegalArgument(block: () -> Unit) {
        val error = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }
        assertTrue(error is IllegalArgumentException)
    }

    companion object {
        private const val BLOCK_NUMBER = 19088743L // 0x1234567
        private const val BLOCK_ID =
            "0000000001234567" + "89abcdef01234567" + "00000000000000000000000000000000"
        private const val TIMESTAMP = 1_700_000_000_000L
        private const val EXPIRATION = 1_700_000_060_000L
        private const val FEE_LIMIT = 10_000_000L
        private const val OWNER = "41ce7ba9c4618bc93f00c6673f73042fc0a33f1b62"
        private const val TO = "410a38028ed6146aa29c687c052b233131468b6635"
        private const val DATA = "a9059cbb0000000000000000000000410a38028ed6146aa29c687c052b233131468b6635"
    }
}

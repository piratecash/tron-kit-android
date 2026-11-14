package io.horizontalsystems.tronkit.transaction

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.RawData
import io.horizontalsystems.tronkit.toRawHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tron.protos.Protocol.Transaction

class OfflineTransactionFactoryTest {

    @Test
    fun withExtendedExpiration_setsTimestampPlusDuration_inProtoAndRawData() {
        val result = createdTransaction().withExtendedExpiration(ONE_HOUR_MS)

        val expected = TIMESTAMP + ONE_HOUR_MS
        assertEquals(expected, parseRaw(result).expiration)
        assertEquals(expected, result.raw_data.expiration)
    }

    @Test
    fun withExtendedExpiration_recomputesTxId_consistentWithRawDataHex() {
        val created = createdTransaction()

        val result = created.withExtendedExpiration(ONE_HOUR_MS)

        assertEquals(Utils.sha256(result.raw_data_hex.hexStringToByteArray()).toRawHexString(), result.txID)
        assertNotEquals(created.txID, result.txID)
    }

    @Test
    fun withExtendedExpiration_changesOnlyExpiration() {
        val created = createdTransaction()
        val before = parseRaw(created).toBuilder().clearExpiration().build()

        val after = parseRaw(created.withExtendedExpiration(ONE_HOUR_MS)).toBuilder().clearExpiration().build()

        // Everything except expiration (ref block, hash, timestamp, fee limit, contracts) is byte-identical.
        assertEquals(before, after)
    }

    @Test
    fun withExtendedExpiration_nonPositiveDuration_throws() {
        assertIllegalArgument { createdTransaction().withExtendedExpiration(0) }
        assertIllegalArgument { createdTransaction().withExtendedExpiration(-1) }
    }

    @Test
    fun withExtendedExpiration_durationNotExtending_throws() {
        // A duration that lands on or before the node's current expiration must be rejected.
        assertIllegalArgument { createdTransaction().withExtendedExpiration(DEFAULT_EXPIRATION_MS) }
        assertIllegalArgument { createdTransaction().withExtendedExpiration(DEFAULT_EXPIRATION_MS - 1) }
    }

    @Test
    fun withExtendedExpiration_overflowingDuration_throws() {
        assertIllegalArgument { createdTransaction().withExtendedExpiration(Long.MAX_VALUE) }
    }

    @Test
    fun withExtendedExpiration_missingTimestamp_throws() {
        assertIllegalArgument { createdTransaction(timestamp = 0).withExtendedExpiration(ONE_HOUR_MS) }
    }

    private fun parseRaw(created: CreatedTransaction): Transaction.raw =
        Transaction.raw.parseFrom(created.raw_data_hex.hexStringToByteArray())

    private fun createdTransaction(timestamp: Long = TIMESTAMP): CreatedTransaction {
        val bytes = Transaction.raw.newBuilder()
            .setRefBlockBytes(ByteString.copyFrom(REF_BLOCK_BYTES))
            .setRefBlockHash(ByteString.copyFrom(REF_BLOCK_HASH))
            .setTimestamp(timestamp)
            .setExpiration(timestamp + DEFAULT_EXPIRATION_MS)
            .setFeeLimit(FEE_LIMIT)
            .addContract(
                Transaction.Contract.newBuilder()
                    .setType(Transaction.Contract.ContractType.TransferContract)
                    .setParameter(Any.newBuilder().setValue(ByteString.copyFrom(CONTRACT_PARAMETER)).build())
                    .build()
            )
            .build()
            .toByteArray()
        return CreatedTransaction(
            visible = false,
            txID = Utils.sha256(bytes).toRawHexString(),
            raw_data = RawData(
                contract = emptyList(),
                ref_block_bytes = REF_BLOCK_BYTES.toRawHexString(),
                ref_block_hash = REF_BLOCK_HASH.toRawHexString(),
                expiration = timestamp + DEFAULT_EXPIRATION_MS,
                timestamp = timestamp,
                fee_limit = FEE_LIMIT,
            ),
            raw_data_hex = bytes.toRawHexString(),
            Error = null,
        )
    }

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
        private const val TIMESTAMP = 1_700_000_000_000L
        private const val DEFAULT_EXPIRATION_MS = 60_000L
        private const val ONE_HOUR_MS = 3_600_000L
        private const val FEE_LIMIT = 10_000_000L
        private val REF_BLOCK_BYTES = byteArrayOf(0x12, 0x34)
        private val REF_BLOCK_HASH = ByteArray(8) { it.toByte() }
        private val CONTRACT_PARAMETER = byteArrayOf(0x01, 0x02, 0x03)
    }
}

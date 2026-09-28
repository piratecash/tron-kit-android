package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.network.RawData
import io.horizontalsystems.tronkit.network.SignedTransaction
import io.horizontalsystems.tronkit.toRawHexString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionSenderTest {
    private interface TestNodeApiProvider : INodeApiProvider

    private val nodeApiProvider = mockk<TestNodeApiProvider>()
    private val signer = mockk<Signer>()
    private val transactionSender = TransactionSender(nodeApiProvider)

    @Test
    fun signedRawTransaction_validTransaction_doesNotBroadcast() = runTest {
        every { signer.sign(CREATED_TRANSACTION) } returns SIGNATURE

        val signedRawTransaction = transactionSender.signedRawTransaction(CREATED_TRANSACTION, signer)

        assertEquals(TX_ID, signedRawTransaction.txId)
        assertEquals(EXPIRATION, signedRawTransaction.expiration)
        coVerify(exactly = 0) { nodeApiProvider.broadcastTransaction(any<SignedTransaction>()) }
    }

    @Test
    fun signedRawTransaction_trc20PreservesFeeLimit() = runTest {
        val createdTransaction = createdTransaction(feeLimit = FEE_LIMIT)
        every { signer.sign(createdTransaction) } returns SIGNATURE

        val signedRawTransaction = transactionSender.signedRawTransaction(createdTransaction, signer)
        val decoded = RawTransactionUtils.decode(signedRawTransaction.raw)

        assertEquals(FEE_LIMIT, decoded.signedTransaction.raw_data.fee_limit)
    }

    @Test
    fun broadcastTransaction_validTransaction_usesSignedTransaction() = runTest {
        val signedTransactionSlot = slot<SignedTransaction>()

        every { signer.sign(CREATED_TRANSACTION) } returns SIGNATURE
        coEvery { nodeApiProvider.broadcastTransaction(capture(signedTransactionSlot)) } returns TX_ID

        val txId = transactionSender.broadcastTransaction(CREATED_TRANSACTION, signer)

        assertEquals(TX_ID, txId)
        assertEquals(listOf(SIGNATURE.toRawHexString()), signedTransactionSlot.captured.signature)
        assertEquals(CREATED_TRANSACTION.raw_data_hex, signedTransactionSlot.captured.raw_data_hex)
    }

    companion object {
        private const val RAW_DATA_HEX = "0a020001"
        private val TX_ID = Utils.sha256(RAW_DATA_HEX.hexStringToByteArray()).toRawHexString()
        private const val EXPIRATION = 1_700_000_300_000L
        private const val FEE_LIMIT = 10_000_000L
        private val SIGNATURE = ByteArray(65) { 1 }
        private val CREATED_TRANSACTION = createdTransaction(feeLimit = null)

        private fun createdTransaction(feeLimit: Long?) = CreatedTransaction(
            visible = false,
            txID = TX_ID,
            raw_data = RawData(
                contract = emptyList(),
                ref_block_bytes = "0000",
                ref_block_hash = "00000000",
                expiration = EXPIRATION,
                timestamp = 1_700_000_000_000,
                fee_limit = feeLimit
            ),
            raw_data_hex = RAW_DATA_HEX,
            Error = null
        )
    }
}

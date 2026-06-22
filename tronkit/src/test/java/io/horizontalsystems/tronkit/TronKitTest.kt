package io.horizontalsystems.tronkit

import io.horizontalsystems.hdwalletkit.Utils
import io.horizontalsystems.tronkit.account.AccountInfoManager
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.network.Network
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.RawData
import io.horizontalsystems.tronkit.sync.ChainParameterManager
import io.horizontalsystems.tronkit.sync.Syncer
import io.horizontalsystems.tronkit.toRawHexString
import io.horizontalsystems.tronkit.transaction.FeeProvider
import io.horizontalsystems.tronkit.transaction.RawTransactionBroadcaster
import io.horizontalsystems.tronkit.transaction.Signer
import io.horizontalsystems.tronkit.transaction.TransactionManager
import io.horizontalsystems.tronkit.transaction.TransactionSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TronKitTest {
    private val transactionSender = mockk<TransactionSender>()
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val signer = mockk<Signer>()
    private val tronKit = TronKit(
        address = Address.fromHex("410000000000000000000000000000000000000000"),
        network = Network.Mainnet,
        syncer = mockk<Syncer>(relaxed = true),
        transactionSyncer = null,
        accountInfoManager = mockk<AccountInfoManager>(relaxed = true),
        transactionManager = transactionManager,
        transactionSender = transactionSender,
        rawTransactionBroadcaster = mockk<RawTransactionBroadcaster>(relaxed = true),
        feeProvider = mockk<FeeProvider>(relaxed = true),
        chainParameterManager = mockk<ChainParameterManager>(relaxed = true),
        allowanceManager = mockk<AllowanceManager>(relaxed = true)
    )

    @Test
    fun send_createdTransaction_broadcastsAndHandlesHistory() = runTest {
        coEvery { transactionSender.broadcastTransaction(CREATED_TRANSACTION, signer) } returns TX_ID

        tronKit.send(CREATED_TRANSACTION, signer)

        coVerify { transactionSender.broadcastTransaction(CREATED_TRANSACTION, signer) }
        verify { transactionManager.handle(CREATED_TRANSACTION) }
    }

    companion object {
        private const val RAW_DATA_HEX = "0a020001"
        private val TX_ID = Utils.sha256(RAW_DATA_HEX.hexStringToByteArray()).toRawHexString()
        private val CREATED_TRANSACTION = CreatedTransaction(
            visible = false,
            txID = TX_ID,
            raw_data = RawData(
                contract = emptyList(),
                ref_block_bytes = "0000",
                ref_block_hash = "00000000",
                expiration = 1_700_000_300_000,
                timestamp = 1_700_000_000_000,
                fee_limit = null
            ),
            raw_data_hex = RAW_DATA_HEX,
            Error = null
        )
    }
}

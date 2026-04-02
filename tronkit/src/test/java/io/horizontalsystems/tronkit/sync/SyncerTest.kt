package io.horizontalsystems.tronkit.sync

import io.horizontalsystems.tronkit.TronKit.SyncState
import io.horizontalsystems.tronkit.account.AccountInfoManager
import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.models.AccountInfo
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.network.IHistoryProvider
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.network.IRpcApiProvider
import io.horizontalsystems.tronkit.network.NodeAccountResponse
import io.horizontalsystems.tronkit.rpc.BlockNumberJsonRpc
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SyncerTest {

    private val address = mockk<Address> {
        every { base58 } returns "TTestAddress123456789"
        every { hex } returns "41abcdef1234567890abcdef1234567890abcdef12"
    }
    private val syncTimer = mockk<SyncTimer>(relaxed = true)
    private val rpcApiProvider = mockk<IRpcApiProvider>()
    private val nodeApiProvider = mockk<INodeApiProvider>()
    private val accountInfoManager = mockk<AccountInfoManager>(relaxed = true)
    private val chainParameterManager = mockk<ChainParameterManager>(relaxed = true)
    private val transactionSyncer = mockk<TransactionSyncer>(relaxed = true)
    private val storage = mockk<Storage>(relaxed = true)

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var syncer: Syncer

    @Before
    fun setUp() {
        every { storage.getLastBlockHeight() } returns 100L
        every { accountInfoManager.trc20AddressesToSync() } returns emptyList()

        syncer = createSyncer(historyProvider = null)
    }

    private fun createSyncer(historyProvider: IHistoryProvider?): Syncer {
        val s = Syncer(
            address = address,
            syncTimer = syncTimer,
            rpcApiProvider = rpcApiProvider,
            nodeApiProvider = nodeApiProvider,
            historyProvider = historyProvider,
            accountInfoManager = accountInfoManager,
            chainParameterManager = chainParameterManager,
            transactionSyncer = transactionSyncer,
            storage = storage,
        )
        s.start(testScope)
        return s
    }

    /**
     * Simulates what SyncTimer does on connection established:
     * transitions the syncer from NotSynced → Syncing.
     */
    private fun simulateTimerReady(target: Syncer) {
        target.onUpdateSyncTimerState(SyncTimer.State.Ready)
    }

    // --- Test 1: new block height triggers account sync and transaction sync ---

    @Test
    fun sync_newBlockHeight_triggersAccountSyncAndTransactionSync() = testScope.runTest {
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } returns 200L
        coEvery { nodeApiProvider.fetchAccount(any()) } returns NodeAccountResponse(BigInteger.TEN)
        simulateTimerReady(syncer)

        syncer.sync()
        advanceUntilIdle()

        val addressSlot = slot<String>()
        coVerify { nodeApiProvider.fetchAccount(capture(addressSlot)) }
        assertEquals(address.hex, addressSlot.captured)

        verify { transactionSyncer.sync() }

        val heightSlot = slot<Long>()
        verify { storage.saveLastBlockHeight(capture(heightSlot)) }
        assertEquals(200L, heightSlot.captured)
    }

    // --- Test 2: same block height sets Synced without account/tx sync ---

    @Test
    fun sync_sameBlockHeight_setsSynced() = testScope.runTest {
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } returns 100L
        simulateTimerReady(syncer)

        syncer.sync()
        advanceUntilIdle()

        assertTrue(syncer.syncState is SyncState.Synced)
        coVerify(exactly = 0) { nodeApiProvider.fetchAccount(any()) }
        verify(exactly = 0) { transactionSyncer.sync() }
    }

    // --- Test 3: block height RPC error sets NotSynced ---

    @Test
    fun sync_blockHeightError_setsNotSynced() = testScope.runTest {
        val rpcError = RuntimeException("RPC unavailable")
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } throws rpcError
        simulateTimerReady(syncer)

        syncer.sync()
        advanceUntilIdle()

        val state = syncer.syncState
        assertTrue("Expected NotSynced but was $state", state is SyncState.NotSynced)
        assertTrue((state as SyncState.NotSynced).error === rpcError)
    }

    // --- Test 4: history provider available, uses history path ---

    @Test
    fun sync_historyProviderAvailable_usesHistoryPath() = testScope.runTest {
        val historyProvider = mockk<IHistoryProvider>()
        val accountInfo = AccountInfo(
            balance = BigInteger.valueOf(5000),
            trc20Balances = emptyList()
        )
        coEvery { historyProvider.fetchAccountInfo(any()) } returns accountInfo
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } returns 200L

        val syncerWithHistory = createSyncer(historyProvider)
        simulateTimerReady(syncerWithHistory)

        syncerWithHistory.sync()
        advanceUntilIdle()

        val addressSlot = slot<String>()
        coVerify { historyProvider.fetchAccountInfo(capture(addressSlot)) }
        assertEquals(address.base58, addressSlot.captured)

        coVerify { accountInfoManager.handle(accountInfo) }
        coVerify(exactly = 0) { nodeApiProvider.fetchAccount(any()) }
    }

    // --- Test 5: history provider fails, falls back to RPC ---

    @Test
    fun sync_historyProviderFails_fallsBackToRpc() = testScope.runTest {
        val historyProvider = mockk<IHistoryProvider>()
        coEvery { historyProvider.fetchAccountInfo(any()) } throws RuntimeException("History API down")
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } returns 200L
        coEvery { nodeApiProvider.fetchAccount(any()) } returns NodeAccountResponse(BigInteger.valueOf(3000))

        val syncerWithHistory = createSyncer(historyProvider)
        simulateTimerReady(syncerWithHistory)

        syncerWithHistory.sync()
        advanceUntilIdle()

        coVerify { historyProvider.fetchAccountInfo(any()) }

        val addressSlot = slot<String>()
        coVerify { nodeApiProvider.fetchAccount(capture(addressSlot)) }
        assertEquals(address.hex, addressSlot.captured)

        val balanceSlot = slot<BigInteger>()
        coVerify { accountInfoManager.handle(capture(balanceSlot)) }
        assertEquals(BigInteger.valueOf(3000), balanceSlot.captured)
    }

    // --- Test 6: no history provider, uses RPC path ---

    @Test
    fun sync_noHistoryProvider_usesRpcPath() = testScope.runTest {
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } returns 200L
        coEvery { nodeApiProvider.fetchAccount(any()) } returns NodeAccountResponse(BigInteger.valueOf(7000))
        simulateTimerReady(syncer)

        syncer.sync()
        advanceUntilIdle()

        val addressSlot = slot<String>()
        coVerify { nodeApiProvider.fetchAccount(capture(addressSlot)) }
        assertEquals(address.hex, addressSlot.captured)

        val balanceSlot = slot<BigInteger>()
        coVerify { accountInfoManager.handle(capture(balanceSlot)) }
        assertEquals(BigInteger.valueOf(7000), balanceSlot.captured)

        assertTrue(syncer.syncState is SyncState.Synced)
    }

    // --- Test 7: RPC path, inactive account (fetchAccount returns null) ---

    @Test
    fun sync_rpcPathInactiveAccount_handlesInactive() = testScope.runTest {
        coEvery { rpcApiProvider.fetch(any<BlockNumberJsonRpc>()) } returns 200L
        coEvery { nodeApiProvider.fetchAccount(any()) } returns null
        simulateTimerReady(syncer)

        syncer.sync()
        advanceUntilIdle()

        coVerify { accountInfoManager.handleInactiveAccount() }
        assertTrue(syncer.syncState is SyncState.Synced)
    }

    // --- Test 8: pause delegates to syncTimer ---

    @Test
    fun pause_delegatesToSyncTimer() {
        syncer.pause()

        verify { syncTimer.pause() }
    }

    // --- Test 9: resume delegates to syncTimer ---

    @Test
    fun resume_delegatesToSyncTimer() {
        syncer.resume()

        verify { syncTimer.resume() }
    }
}

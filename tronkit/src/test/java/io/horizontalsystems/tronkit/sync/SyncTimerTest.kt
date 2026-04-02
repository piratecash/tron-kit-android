package io.horizontalsystems.tronkit.sync

import io.horizontalsystems.tronkit.network.ConnectionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTimerTest {

    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val connectionListenerSlot = slot<ConnectionManager.Listener>()

    private lateinit var syncTimer: SyncTimer

    private val syncCallCount = AtomicInteger(0)
    private val syncIntervalSeconds = 30L
    private val syncIntervalMs = syncIntervalSeconds * 1000

    private val testListener = object : SyncTimer.Listener {
        override fun onUpdateSyncTimerState(state: SyncTimer.State) {}
        override fun sync() {
            syncCallCount.incrementAndGet()
        }
    }

    @Before
    fun setUp() {
        syncCallCount.set(0)

        every { connectionManager.listener = capture(connectionListenerSlot) } answers {}
        every { connectionManager.isConnected } returns false

        syncTimer = SyncTimer(syncIntervalSeconds, connectionManager)
    }

    private fun startConnected() {
        every { connectionManager.isConnected } returns true
    }

    private fun simulateConnectionChange() {
        connectionListenerSlot.captured.onConnectionChange()
    }

    // --- pause ---

    @Test
    fun pause_whenStartedAndNotPaused_stopsTimer() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()
        assertTrue("Timer should fire on start", syncCallCount.get() >= 1)

        syncTimer.pause()
        val countAtPause = syncCallCount.get()

        advanceTimeBy(syncIntervalMs + 1)
        runCurrent()

        assertEquals("No new sync() calls expected after pause", countAtPause, syncCallCount.get())
        assertTrue(syncTimer.state is SyncTimer.State.Ready)

        syncTimer.stop()
    }

    @Test
    fun pause_whenNotStarted_doesNothing() = runTest {
        syncTimer.pause()

        assertTrue(syncTimer.state is SyncTimer.State.NotReady)
        assertEquals(0, syncCallCount.get())
    }

    @Test
    fun pause_whenAlreadyPaused_doesNothing() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()

        syncTimer.pause()
        syncTimer.pause()

        val countAfterDoublePause = syncCallCount.get()

        advanceTimeBy(syncIntervalMs + 1)
        runCurrent()

        assertEquals(
            "No new sync() calls expected after double pause",
            countAfterDoublePause,
            syncCallCount.get()
        )

        syncTimer.stop()
    }

    // --- resume ---

    @Test
    fun resume_whenPaused_restartsTimerIfConnected() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()

        syncTimer.pause()
        val countAtPause = syncCallCount.get()

        advanceTimeBy(syncIntervalMs + 1)
        runCurrent()
        assertEquals("Timer should be stopped while paused", countAtPause, syncCallCount.get())

        syncTimer.resume()
        runCurrent()

        assertTrue("sync() should fire after resume", syncCallCount.get() > countAtPause)

        syncTimer.stop()
    }

    @Test
    fun resume_whenPausedButDisconnected_doesNotRestartTimer() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()

        syncTimer.pause()
        val countAtPause = syncCallCount.get()

        every { connectionManager.isConnected } returns false

        syncTimer.resume()
        runCurrent()

        advanceTimeBy(syncIntervalMs + 1)
        runCurrent()

        assertEquals(
            "No sync() calls expected when resuming while disconnected",
            countAtPause,
            syncCallCount.get()
        )

        syncTimer.stop()
    }

    @Test
    fun resume_whenNotPaused_doesNothing() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()
        val countBefore = syncCallCount.get()
        assertTrue("Timer should have fired at least once", countBefore >= 1)

        // resume without preceding pause is a no-op (guard: !isPaused returns early)
        syncTimer.resume()
        runCurrent()

        advanceTimeBy(syncIntervalMs + 1)
        runCurrent()

        assertTrue("Timer should continue running normally", syncCallCount.get() > countBefore)

        syncTimer.stop()
    }

    // --- stop ---

    @Test
    fun stop_resetsPausedFlag() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()

        syncTimer.pause()
        syncTimer.stop()

        syncCallCount.set(0)

        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()

        assertTrue(
            "Timer should fire after stop+restart (paused flag must be cleared)",
            syncCallCount.get() >= 1
        )

        syncTimer.stop()
    }

    // --- handleConnectionChange while paused ---

    @Test
    fun handleConnectionChange_whenPaused_doesNotStartTimer() = runTest {
        startConnected()
        syncTimer.start(testListener, this)
        runCurrent()

        syncTimer.pause()
        val countAtPause = syncCallCount.get()

        every { connectionManager.isConnected } returns false
        simulateConnectionChange()
        every { connectionManager.isConnected } returns true
        simulateConnectionChange()

        runCurrent()
        advanceTimeBy(syncIntervalMs + 1)
        runCurrent()

        assertEquals(
            "No sync() calls expected when connection changes while paused",
            countAtPause,
            syncCallCount.get()
        )
        assertTrue(syncTimer.state is SyncTimer.State.Ready)

        syncTimer.stop()
    }
}

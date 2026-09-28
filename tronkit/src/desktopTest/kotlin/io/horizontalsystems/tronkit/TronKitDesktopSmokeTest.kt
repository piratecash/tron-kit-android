package io.horizontalsystems.tronkit

import io.horizontalsystems.tronkit.database.MainDatabase
import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.RpcSource
import io.horizontalsystems.tronkit.models.Transaction
import io.horizontalsystems.tronkit.models.Trc20Balance
import io.horizontalsystems.tronkit.network.Network
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger

/**
 * Desktop counterpart of the Android host tests: the same Room DAOs and the same `Storage`
 * run on the JVM driver, so a missing blocking bridge or a wrong Room builder fails here.
 */
class TronKitDesktopSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        TronKit.init()
    }

    @Test
    fun storage_desktop_writesAndReadsThroughBlockingDao() {
        val database = MainDatabase.getInstance(PlatformContext(tmp.root), DB_NAME, DATABASE_KEY)
        try {
            val storage = Storage(database)

            storage.saveLastBlockHeight(42)
            assertEquals(42L, storage.getLastBlockHeight())

            // saveBalances runs inside a transaction (the expect/actual `inTransaction`).
            storage.saveBalances(BigInteger.TEN, listOf(Trc20Balance(CONTRACT_ADDRESS, BigInteger.ONE)))
            assertEquals(BigInteger.TEN, storage.getTrxBalance())
            assertEquals(BigInteger.ONE, storage.getTrc20Balance(CONTRACT_ADDRESS))

            val transaction = Transaction(hash = byteArrayOf(1, 2, 3), timestamp = 100, confirmed = true)
            storage.saveTransactions(listOf(transaction))

            // Both go through @RawQuery + RoomRawQuery.
            runBlocking {
                assertEquals(listOf(transaction), storage.getTransactionsBefore(emptyList(), null, null))
                assertEquals(listOf(transaction), storage.getTransactionsAfter(emptyList(), null, null))
            }
        } finally {
            database.close()
        }

        assertTrue(File(tmp.root, DB_NAME).exists())
    }

    @Test
    fun tronKit_desktop_isCreatedAndStopped() {
        val server = MockWebServer()
        server.start()
        try {
            val kit = TronKit.getInstance(
                PlatformContext(tmp.root),
                Address.fromHex("410000000000000000000000000000000000000000"),
                Network.Mainnet,
                RpcSource(listOf(server.url("/").toUrl())),
                null,
                "desktop-smoke",
                DATABASE_KEY
            )

            kit.stop()
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val DB_NAME = "Tron-Mainnet-desktop-smoke"
        val DATABASE_KEY = ByteArray(32) { it.toByte() }
        const val CONTRACT_ADDRESS = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    }
}

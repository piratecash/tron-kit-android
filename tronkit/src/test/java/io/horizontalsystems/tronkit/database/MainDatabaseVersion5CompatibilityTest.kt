package io.horizontalsystems.tronkit.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.math.BigInteger
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainDatabaseVersion5CompatibilityTest {
    @Test
    fun open_room261Version5Fixture_preservesStoredWalletState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tron-v5-compatibility-test.db"
        context.deleteDatabase(databaseName)
        val databasePath = context.getDatabasePath(databaseName).toPath()
        databasePath.parent?.let(Files::createDirectories)

        val fixture = requireNotNull(javaClass.getResourceAsStream("/databases/tron-v5-room-2.6.1.db"))
        fixture.use { Files.copy(it, databasePath) }

        // Robolectric cannot load the native SQLCipher library, so the plaintext fixture is opened by a plain
        // Room builder with the kit's schema and migrations; the encrypted path is covered on desktop and device.
        val database = Room.databaseBuilder(context, MainDatabase::class.java, databaseName)
            .addMigrations(MainDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(62_345_678L, database.lastBlockHeightDao().getLastBlockHeight()?.height)
            assertEquals(BigInteger("123456789"), database.balanceDao().getBalance("TRX")?.balance)
            assertEquals(BigInteger("987654321"), database.balanceDao().getBalance("TRC20|TFixtureToken")?.balance)

            val queuedTransaction = database.rawTransactionBroadcastDao()
                .dueRecords(lastAttemptBefore = 1_700_000_001_000L, now = 1_700_000_000_000L)
                .single()
            assertEquals("fixture-tx-id", queuedTransaction.txId)
            assertArrayEquals(byteArrayOf(0x0a, 0x02, 0x00, 0x01), queuedTransaction.rawTransaction)
            assertEquals(2, queuedTransaction.retriesCount)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}

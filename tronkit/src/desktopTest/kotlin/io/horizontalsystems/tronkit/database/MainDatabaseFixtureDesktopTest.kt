package io.horizontalsystems.tronkit.database

import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.tronkit.PlatformContext
import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.network.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.nio.file.Files

/** The Room 2.6.1 plaintext fixture survives the SQLCipher migration with every stored value intact. */
class MainDatabaseFixtureDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun migrateDatabase_room261Version5Fixture_preservesStoredWalletState() = runBlocking {
        val context = PlatformContext(tmp.root)
        val file = copyFixture()

        val result = TronKit.migrateDatabase(context, Network.Mainnet, WALLET_ID, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(file))
        val database = MainDatabase.getInstance(context, DB_NAME, key)
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
        }
    }

    @Test
    fun getInstance_plaintextFixtureWithoutMigration_throwsMigrationRequired() {
        val file = copyFixture()
        val plaintextBytes = file.readBytes()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            MainDatabase.getInstance(PlatformContext(tmp.root), DB_NAME, key)
        }

        assertArrayEquals(plaintextBytes, file.readBytes())
    }

    @Test
    fun migrateAndGetInstance_31ByteKey_throwBeforeAnyFileIsCreated() {
        val context = PlatformContext(File(tmp.root, "data"))
        val shortKey = ByteArray(31)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { TronKit.migrateDatabase(context, Network.Mainnet, WALLET_ID, shortKey) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainDatabase.getInstance(context, DB_NAME, shortKey)
        }

        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }

    private fun copyFixture(): File {
        val file = File(tmp.root, DB_NAME)
        val fixture = requireNotNull(javaClass.getResourceAsStream("/databases/tron-v5-room-2.6.1.db"))
        fixture.use { Files.copy(it, file.toPath()) }
        return file
    }

    private fun hasPlaintextSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
        return header.contentEquals(SQLITE_HEADER)
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
        const val WALLET_ID = "fixture"
        const val DB_NAME = "Tron-Mainnet-fixture"
    }
}

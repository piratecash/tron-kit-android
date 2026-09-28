package io.horizontalsystems.tronkit.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.network.Network
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.util.UUID

/** The SQLCipher path on a real Android runtime: native library, SupportOpenHelperFactory and getDatabasePath. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { (it * 3).toByte() }
    private val otherKey = ByteArray(32) { (it * 5 + 1).toByte() }
    private val walletId = "encrypted-${UUID.randomUUID()}"
    private val databaseName = "Tron-${Network.Mainnet.name}-$walletId"
    private val absoluteFile = File(context.filesDir, "tron-encrypted-${UUID.randomUUID()}.db")

    @After
    fun tearDown() {
        TronKit.clear(context, Network.Mainnet, walletId)
        clearByPath(absoluteFile)
    }

    @Test
    fun migrateDatabase_absolutePathInFilesDir_isOpenedByGetInstance() = runBlocking {
        copyFixture(absoluteFile)

        val result = MainDatabase.migrateDatabase(context, absoluteFile.absolutePath, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(absoluteFile))
        assertFixtureData(MainDatabase.getInstance(context, absoluteFile.absolutePath, key))
    }

    @Test
    fun migrateDatabase_room261Version5Fixture_preservesStoredWalletState() = runBlocking {
        val file = copyFixture(context.getDatabasePath(databaseName))

        val result = TronKit.migrateDatabase(context, Network.Mainnet, walletId, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(file))
        assertFixtureData(MainDatabase.getInstance(context, databaseName, key))
    }

    @Test
    fun migrateDatabase_alreadyEncrypted_reportsAlreadyEncrypted() = runBlocking {
        copyFixture(context.getDatabasePath(databaseName))
        TronKit.migrateDatabase(context, Network.Mainnet, walletId, key)

        val result = TronKit.migrateDatabase(context, Network.Mainnet, walletId, key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
    }

    @Test
    fun getInstance_otherKey_throwsKeyMismatchWithoutChangingFile() {
        val file = copyFixture(context.getDatabasePath(databaseName))
        runBlocking { TronKit.migrateDatabase(context, Network.Mainnet, walletId, key) }
        val encryptedBytes = file.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            MainDatabase.getInstance(context, databaseName, otherKey)
        }

        assertArrayEquals(encryptedBytes, file.readBytes())
        assertFixtureData(MainDatabase.getInstance(context, databaseName, key))
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequired() {
        val file = copyFixture(context.getDatabasePath(databaseName))
        val plaintextBytes = file.readBytes()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            MainDatabase.getInstance(context, databaseName, key)
        }

        assertArrayEquals(plaintextBytes, file.readBytes())
    }

    @Test
    fun migrateDatabase_stagedMigrationWasInterrupted_recoversAndPreservesData() = runBlocking {
        val file = copyFixture(context.getDatabasePath(databaseName))
        val staging = File("${file.path}.sqlcipher-migrating")
        exportEncrypted(file, staging, key)
        val manifest = File(file.parentFile, ".tron-kit-sqlcipher-${UUID.randomUUID()}.json")
        manifest.writeText(
            """{"version":1,"phase":"STAGED","entries":[{"databasePath":"${file.path}","stagingPath":"${staging.path}"}]}"""
        )

        val result = TronKit.migrateDatabase(context, Network.Mainnet, walletId, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(manifest.exists())
        assertFalse(staging.exists())
        assertFixtureData(MainDatabase.getInstance(context, databaseName, key))
    }

    @Test
    fun clear_encryptedDatabase_removesEveryFile() = runBlocking {
        val file = copyFixture(context.getDatabasePath(databaseName))
        TronKit.migrateDatabase(context, Network.Mainnet, walletId, key)
        MainDatabase.getInstance(context, databaseName, key).let { database ->
            database.lastBlockHeightDao().getLastBlockHeight()
            database.close()
        }

        TronKit.clear(context, Network.Mainnet, walletId)

        val leftovers = file.parentFile!!.list()!!.filter { name ->
            name.startsWith(file.name) || (name.startsWith(".tron-kit-sqlcipher-") && name.endsWith(".json"))
        }
        assertTrue("leftovers: $leftovers", leftovers.isEmpty())
    }

    private fun copyFixture(target: File): File {
        target.parentFile?.mkdirs()
        val fixture = requireNotNull(javaClass.getResourceAsStream("/databases/tron-v5-room-2.6.1.db"))
        fixture.use { Files.copy(it, target.toPath()) }
        return target
    }

    private fun assertFixtureData(database: MainDatabase) {
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

    private fun clearByPath(file: File) {
        tronKitDatabases.clearDatabases(file.absoluteFile.parent!!, listOf(file.name), migrationId = file.path)
    }

    // A staging file as sqlcipher-room writes it: sqlcipher_export into an attached database keyed with x'<hex>'.
    private fun exportEncrypted(source: File, target: File, databaseKey: ByteArray) {
        System.loadLibrary("sqlcipher")
        val keyLiteral = ("x'" + databaseKey.joinToString("") { "%02x".format(it) } + "'").encodeToByteArray()
        // ATTACH inherits these flags: without CREATE_IF_NECESSARY it cannot create the target file.
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        SQLiteDatabase.openDatabase(source.path, null, flags).use { database ->
            val userVersion = database.rawQuery("PRAGMA user_version", emptyArray<String>()).use { cursor ->
                check(cursor.moveToFirst()) { "SQLCipher returned no user_version" }
                cursor.getInt(0)
            }
            database.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(target.path, keyLiteral))
            database.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            database.rawExecSQL("PRAGMA encrypted.user_version=$userVersion")
            database.rawExecSQL("DETACH DATABASE encrypted")
        }
    }

    private fun hasPlaintextSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
        return header.contentEquals(SQLITE_HEADER)
    }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}

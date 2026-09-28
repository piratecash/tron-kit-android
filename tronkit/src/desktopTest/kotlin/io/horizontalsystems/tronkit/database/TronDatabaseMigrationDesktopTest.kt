package io.horizontalsystems.tronkit.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.SqlCipherMigration
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.tronkit.PlatformContext
import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.network.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One-database adaptation of bitcoin-kit's DatabaseMigrationJvmTest, driven through the kit's public entry points. */
class TronDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val database: File get() = File(directory, DB_NAME)

    @Test
    fun migrateDatabase_plaintextDatabase_encryptsAndPreservesData() = runBlocking {
        createPlaintextDatabase(database, "tron")

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(database))
        assertEquals("tron", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_alreadyEncrypted_reportsCountAndKeepsFileBytes() = runBlocking {
        createPlaintextDatabase(database, "tron")
        migrate(key)
        val encryptedBytes = database.readBytes()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertArrayEquals(encryptedBytes, database.readBytes())
        assertEquals("tron", readEncryptedValue(database, key))
    }

    @Test
    fun getInstance_databaseEncryptedWithOtherKey_throwsKeyMismatchWithoutChangingFile() {
        createEncryptedDatabase(database, "tron", key)
        val encryptedBytes = database.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            MainDatabase.getInstance(context, DB_NAME, otherKey)
        }

        assertArrayEquals(encryptedBytes, database.readBytes())
        assertEquals("tron", readEncryptedValue(database, key))
    }

    @Test
    fun migrateDatabase_otherKeyWithoutManifestOrBackup_throwsKeyMismatchAndKeepsFileBytes() {
        createEncryptedDatabase(database, "tron", key)
        val encryptedBytes = database.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking { migrate(otherKey) }
        }

        assertArrayEquals(encryptedBytes, database.readBytes())
        assertEquals("tron", readEncryptedValue(database, key))
    }

    @Test
    fun migrateDatabase_stagedMigrationWasInterrupted_recoversAndMigrates() = runBlocking {
        createPlaintextDatabase(database, "tron")
        val entry = stagePlaintextDatabase(database, key)
        installStagedDatabase(entry)
        writeManifest(ManifestPhase.STAGED, listOf(entry))

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("tron", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_stagedUnderOtherKeyWasInterrupted_restoresPlaintextAndEncryptsWithNewKey() = runBlocking {
        createPlaintextDatabase(database, "tron")
        val entry = stagePlaintextDatabase(database, key)
        installStagedDatabase(entry)
        writeManifest(ManifestPhase.STAGED, listOf(entry))

        val result = migrate(otherKey)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("tron", readEncryptedValue(database, otherKey))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_committedUnderOtherKeyWasInterrupted_throwsKeyMismatchAndKeepsCiphertext() {
        createPlaintextDatabase(database, "tron")
        val entry = stagePlaintextDatabase(database, key)
        installStagedDatabase(entry)
        writeManifest(ManifestPhase.COMMITTED, listOf(entry))

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking { migrate(otherKey) }
        }

        assertEquals("tron", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_orphanedPlaintextBackupBesideOtherKeyCiphertext_restoresAndEncryptsWithNewKey() = runBlocking {
        createPlaintextDatabase(database, "tron")
        // An install finished but its manifest is gone: ciphertext under `key` plus the plaintext backup.
        installStagedDatabase(stagePlaintextDatabase(database, key))

        val result = migrate(otherKey)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("tron", readEncryptedValue(database, otherKey))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_orphanedMainBackupWithoutManifest_restoresDatabaseAndMigratesIt() = runBlocking {
        createPlaintextDatabase(database, "tron")
        // An install interrupted between the two moves, whose manifest is gone.
        stagePlaintextDatabase(database, key)
        Files.move(database.toPath(), backupOf(database).toPath())

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("tron", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_committedMigrationWasInterrupted_finishesCleanupWithoutRewritingDatabase() = runBlocking {
        createPlaintextDatabase(database, "tron")
        val entry = stagePlaintextDatabase(database, key)
        installStagedDatabase(entry)
        writeManifest(ManifestPhase.COMMITTED, listOf(entry))
        val encryptedBytes = database.readBytes()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertArrayEquals(encryptedBytes, database.readBytes())
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_clearingWasInterrupted_finishesDeletionBeforeMigration() = runBlocking {
        createPlaintextDatabase(database, "tron")
        val entry = stagePlaintextDatabase(database, key)
        installStagedDatabase(entry)
        writeManifest(ManifestPhase.CLEARING, listOf(entry))

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 0), result)
        assertFalse(database.exists())
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_unreadableManifest_discardsManifestAndEncryptsDatabase() = runBlocking {
        createPlaintextDatabase(database, "tron")
        val manifest = File(directory, ".tron-kit-sqlcipher-invalid.json").apply { writeText("not-json") }

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(manifest.exists())
        assertEquals("tron", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_truncatedMainBackupWithoutManifest_keepsEncryptedDatabase() = runBlocking {
        createPlaintextDatabase(database, "tron")
        installStagedDatabase(stagePlaintextDatabase(database, key))
        // eraseBackup truncates and fsyncs before unlinking: a kill in between leaves an empty backup.
        backupOf(database).writeBytes(ByteArray(0))

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertEquals("tron", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_customName_isOpenedByGetInstance() = runBlocking {
        copyFixture(File(directory, "custom.db"))

        val result = MainDatabase.migrateDatabase(context, "custom.db", key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFixtureBlockHeight(MainDatabase.getInstance(context, "custom.db", key))
    }

    @Test
    fun migrateDatabase_absolutePath_isOpenedByGetInstance() = runBlocking {
        val elsewhere = tmp.newFolder("elsewhere")
        val file = File(elsewhere, "absolute.db")
        copyFixture(file)
        val otherContext = PlatformContext(tmp.newFolder("data"))

        val result = MainDatabase.migrateDatabase(otherContext, file.absolutePath, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(file))
        assertFixtureBlockHeight(MainDatabase.getInstance(otherContext, file.absolutePath, key))
    }

    @Test
    fun migrateDatabase_invalidArguments_throwBeforeAnyFileIsCreated() {
        invalidArguments().forEach { (name, databaseKey) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { MainDatabase.migrateDatabase(PlatformContext(File(directory, "data")), name, databaseKey) }
            }
            assertDirectoryEmpty(name)
        }
    }

    @Test
    fun getInstance_invalidArguments_throwBeforeAnyFileIsCreated() {
        invalidArguments().forEach { (name, databaseKey) ->
            assertThrows(IllegalArgumentException::class.java) {
                MainDatabase.getInstance(PlatformContext(File(directory, "data")), name, databaseKey)
            }
            assertDirectoryEmpty(name)
        }
    }

    @Test
    fun clear_interruptedMigration_removesDatabaseFamilyAndMigrationLeftovers() {
        val walletDatabase = File(directory, "Tron-Mainnet-wallet")
        createPlaintextDatabase(walletDatabase, "tron")
        val entry = stagePlaintextDatabase(walletDatabase, key)
        installStagedDatabase(entry)
        listOf("-wal", "-shm", "-journal", "-mj0123").forEach { suffix -> File("${walletDatabase.path}$suffix").writeText("x") }
        File("${walletDatabase.path}-wal$BACKUP_SUFFIX").writeText("stale-wal")
        File("${entry.stagingPath}-wal").writeText("stale-staging-wal")
        // Written under the id the kit itself uses, so only a clear with the same id removes it.
        writeManifest(ManifestPhase.STAGED, listOf(entry), manifestFileFor(walletDatabase.path))

        TronKit.clear(context, Network.Mainnet, "wallet")

        assertEquals(listOf(LOCK_FILE_NAME), directory.list()!!.toList())
        val reopened = MainDatabase.getInstance(context, walletDatabase.name, key)
        reopened.close()
    }

    @Test
    fun migrateAndClear_bitcoinKitFilesInSameDirectory_areIgnoredAndUntouched() = runBlocking {
        val bitcoinDatabase = File(directory, "bitcoin-core.db")
        createPlaintextDatabase(backupOf(bitcoinDatabase), "bitcoin")
        val bitcoinManifest = File(directory, ".bitcoin-kit-sqlcipher-0123456789abcdef.json")
        bitcoinManifest.writeText(
            manifestJson(ManifestPhase.STAGED, listOf(StagedEntry(bitcoinDatabase.path, "${bitcoinDatabase.path}$STAGING_SUFFIX")))
        )
        val bitcoinLock = File(directory, ".bitcoin-kit-sqlcipher.lock").apply { writeText("") }
        val bitcoinFiles = listOf(backupOf(bitcoinDatabase), bitcoinManifest, bitcoinLock)
        val snapshot = bitcoinFiles.associateWith(File::readBytes)
        val walletDatabase = File(directory, "Tron-Mainnet-wallet")
        createPlaintextDatabase(walletDatabase, "tron")

        val result = TronKit.migrateDatabase(context, Network.Mainnet, "wallet", key)
        MainDatabase.getInstance(context, walletDatabase.name, key).close()
        TronKit.clear(context, Network.Mainnet, "wallet")

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(walletDatabase.exists())
        snapshot.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertEquals(
            (bitcoinFiles.map(File::getName) + LOCK_FILE_NAME).sorted(),
            directory.list()!!.sorted(),
        )
    }

    private suspend fun migrate(databaseKey: ByteArray): DatabaseMigrationResult =
        MainDatabase.migrateDatabase(context, DB_NAME, databaseKey)

    private fun invalidArguments(): List<Pair<String, ByteArray>> = listOf(
        DB_NAME to ByteArray(31),
        "" to key,
        " " to key,
        "${directory.path}/ " to key,
        ".tron-kit-sqlcipher-wallet.json" to key,
        ".tron-kit-sqlcipher.lock" to key,
        ".bitcoin-kit-sqlcipher-x.json" to key,
        "Tron-Mainnet-a$BACKUP_SUFFIX" to key,
        "Tron-Mainnet-a-wal$BACKUP_SUFFIX" to key,
        "Tron-Mainnet-a$STAGING_SUFFIX" to key,
        "Tron-Mainnet-a$STAGING_SUFFIX-wal" to key,
    )

    private fun assertDirectoryEmpty(name: String) {
        assertEquals("files after '$name'", emptyList<String>(), directory.list()!!.toList())
    }

    private fun createPlaintextDatabase(file: File, value: String) {
        BundledSQLiteDriver().open(file.path).use { connection -> createSample(connection::execSQL, value) }
    }

    private fun createEncryptedDatabase(file: File, value: String, databaseKey: ByteArray) {
        SqlCipherDriver(databaseKey).use { driver ->
            driver.open(file.path).use { connection -> createSample(connection::execSQL, value) }
        }
    }

    private fun createSample(execSql: (String) -> Unit, value: String) {
        execSql("CREATE TABLE sample(value TEXT NOT NULL)")
        execSql("INSERT INTO sample VALUES('$value')")
    }

    private fun readEncryptedValue(file: File, databaseKey: ByteArray): String = SqlCipherDriver(databaseKey).use { driver ->
        driver.open(file.path).use { connection ->
            connection.prepare("SELECT value FROM sample").use { statement ->
                check(statement.step()) { "Test database contains no sample row" }
                statement.getText(0)
            }
        }
    }

    private fun stagePlaintextDatabase(file: File, databaseKey: ByteArray): StagedEntry {
        val staging = File("${file.path}$STAGING_SUFFIX")
        SqlCipherMigration.exportPlaintext(file.path, staging.path, databaseKey)
        return StagedEntry(file.path, staging.path)
    }

    private fun installStagedDatabase(entry: StagedEntry) {
        Files.move(entry.databaseFile.toPath(), backupOf(entry.databaseFile).toPath(), StandardCopyOption.ATOMIC_MOVE)
        Files.move(entry.stagingFile.toPath(), entry.databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeManifest(
        phase: ManifestPhase,
        entries: List<StagedEntry>,
        file: File = File(directory, ".tron-kit-sqlcipher-test.json"),
    ) {
        file.writeText(manifestJson(phase, entries))
    }

    // The sqlcipher-room manifest format, version 1.
    private fun manifestJson(phase: ManifestPhase, entries: List<StagedEntry>): String =
        entries.joinToString(
            separator = ",",
            prefix = """{"version":1,"phase":"${phase.name}","entries":[""",
            postfix = "]}",
        ) { entry -> """{"databasePath":${jsonString(entry.databasePath)},"stagingPath":${jsonString(entry.stagingPath)}}""" }

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Same derivation as the kit's manifestFile: the first 8 bytes of SHA-256(migrationId) in hex.
    private fun manifestFileFor(migrationId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(migrationId.encodeToByteArray())
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(directory, ".tron-kit-sqlcipher-$id.json")
    }

    private fun copyFixture(target: File) {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/databases/tron-v5-room-2.6.1.db"))
        fixture.use { Files.copy(it, target.toPath()) }
    }

    private fun assertFixtureBlockHeight(mainDatabase: MainDatabase) {
        try {
            assertEquals(62_345_678L, mainDatabase.lastBlockHeightDao().getLastBlockHeight()?.height)
        } finally {
            mainDatabase.close()
        }
    }

    private fun backupOf(file: File): File = File("${file.path}$BACKUP_SUFFIX")

    private fun hasPlaintextSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
        return header.contentEquals(SQLITE_HEADER)
    }

    private fun assertNoMigrationArtifacts() {
        val artifacts = directory.list()!!.filter { name ->
            name.endsWith(".json") || name.endsWith(STAGING_SUFFIX) || name.endsWith(BACKUP_SUFFIX)
        }
        assertTrue("migration artifacts left: $artifacts", artifacts.isEmpty())
    }

    private data class StagedEntry(val databasePath: String, val stagingPath: String) {
        val databaseFile: File get() = File(databasePath)
        val stagingFile: File get() = File(stagingPath)
    }

    private enum class ManifestPhase { STAGED, COMMITTED, CLEARING }

    private companion object {
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
        const val DB_NAME = "Tron-Mainnet-migration"
        const val LOCK_FILE_NAME = ".tron-kit-sqlcipher.lock"
        const val STAGING_SUFFIX = ".sqlcipher-migrating"
        const val BACKUP_SUFFIX = ".plaintext-backup"
    }
}

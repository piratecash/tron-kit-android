package io.horizontalsystems.tronkit.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationConflictException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationInProgressException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.InsufficientDatabaseMigrationSpaceException
import io.horizontalsystems.tronkit.PlatformContext
import io.horizontalsystems.tronkit.models.Balance
import io.horizontalsystems.tronkit.models.ChainParameter
import io.horizontalsystems.tronkit.models.InternalTransaction
import io.horizontalsystems.tronkit.models.LastBlockHeight
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.tronkit.models.Transaction
import io.horizontalsystems.tronkit.models.TransactionSyncState
import io.horizontalsystems.tronkit.models.TransactionTag
import io.horizontalsystems.tronkit.models.Trc20EventRecord
import java.io.File

@Database(
    entities = [
        LastBlockHeight::class,
        Balance::class,
        TransactionSyncState::class,
        Transaction::class,
        InternalTransaction::class,
        Trc20EventRecord::class,
        TransactionTag::class,
        ChainParameter::class,
        RawTransactionBroadcastRecord::class,
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class MainDatabase : RoomDatabase() {

    abstract fun lastBlockHeightDao(): LastBlockHeightDao
    abstract fun balanceDao(): BalanceDao
    abstract fun transactionDao(): TransactionDao
    abstract fun tagsDao(): TransactionTagDao
    abstract fun chainParameterDao(): ChainParameterDao
    abstract fun rawTransactionBroadcastDao(): RawTransactionBroadcastDao

    companion object {
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `RawTransactionBroadcastRecord` (
                        `txId` TEXT NOT NULL,
                        `rawTransaction` BLOB NOT NULL,
                        `expiration` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastAttemptAt` INTEGER,
                        `retriesCount` INTEGER NOT NULL,
                        PRIMARY KEY(`txId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Opens the SQLCipher database [databaseName]: a name inside the platform database directory or
         * an absolute path. [databaseKey] must be exactly 32 bytes and the file name (basename) of
         * [databaseName] must not be blank, start with the reserved `.tron-kit-sqlcipher` or
         * `.bitcoin-kit-sqlcipher` prefix, or contain the reserved `.sqlcipher-migrating` or `.plaintext-backup`
         * suffix, otherwise [IllegalArgumentException] is thrown before any I/O.
         *
         * Call [migrateDatabase] with the same name and key first. Failures:
         * - [DatabaseMigrationRequiredException] or [DatabaseMigrationInProgressException]: call [migrateDatabase];
         * - [DatabaseKeyMismatchException]: the file is kept; only deleting it and using a new key (data lost) recovers.
         */
        fun getInstance(context: PlatformContext, databaseName: String, databaseKey: ByteArray): MainDatabase {
            require(databaseKey.size == DATABASE_KEY_SIZE) { "Database key must contain exactly $DATABASE_KEY_SIZE bytes" }
            requireValidDatabaseName(databaseName)
            return mainDatabaseBuilder(context, databaseName, databaseKey)
                .addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)
                .build()
        }

        /**
         * Encrypts an existing plaintext [databaseName] with [databaseKey], keeping its data, and recovers an
         * interrupted migration. Idempotent: an already encrypted database is only verified with the key.
         * Accepts the same names as [getInstance] and must finish before it.
         *
         * [databaseKey] must be exactly 32 bytes and the file name (basename) must pass the same name check as
         * [getInstance], otherwise [IllegalArgumentException] is thrown before any I/O. Failures:
         * - [DatabaseKeyMismatchException]: the encrypted file is kept unchanged;
         * - [DatabaseMigrationConflictException]: another migration or clear is running; retry later;
         * - [InsufficientDatabaseMigrationSpaceException]: the plaintext database is kept unchanged.
         */
        suspend fun migrateDatabase(
            context: PlatformContext,
            databaseName: String,
            databaseKey: ByteArray,
        ): DatabaseMigrationResult {
            require(databaseKey.size == DATABASE_KEY_SIZE) { "Database key must contain exactly $DATABASE_KEY_SIZE bytes" }
            requireValidDatabaseName(databaseName)
            val file = databaseFile(context, databaseName)
            return tronKitDatabases.migrateDatabases(
                dataDir = file.absoluteFile.parent,
                databaseNames = listOf(file.name),
                migrationId = file.path,
                databaseKey = databaseKey,
            )
        }

        private fun requireValidDatabaseName(databaseName: String) {
            val name = File(databaseName).name
            require(name.isNotBlank()) { "Database file name must not be blank: $databaseName" }
            require(!name.startsWith(MIGRATION_FILE_STEM) && !name.startsWith(BITCOIN_KIT_MIGRATION_FILE_STEM)) {
                "Database file name uses a reserved migration prefix: $databaseName"
            }
            // Contains, not endsWith: the SQLite family of a staging file (-wal, -shm, ...) is recovered too.
            require(!name.contains(STAGING_SUFFIX) && !name.contains(BACKUP_SUFFIX)) {
                "Database file name uses a reserved migration suffix: $databaseName"
            }
        }

        private const val DATABASE_KEY_SIZE = 32

        // Mirror sqlcipher-room's private file names, so a wallet database never collides with migration files.
        private const val MIGRATION_FILE_STEM = ".tron-kit-sqlcipher"
        private const val BITCOIN_KIT_MIGRATION_FILE_STEM = ".bitcoin-kit-sqlcipher"
        private const val STAGING_SUFFIX = ".sqlcipher-migrating"
        private const val BACKUP_SUFFIX = ".plaintext-backup"
    }
}

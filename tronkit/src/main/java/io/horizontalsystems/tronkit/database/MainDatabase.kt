package io.horizontalsystems.tronkit.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.horizontalsystems.tronkit.models.Balance
import io.horizontalsystems.tronkit.models.ChainParameter
import io.horizontalsystems.tronkit.models.InternalTransaction
import io.horizontalsystems.tronkit.models.LastBlockHeight
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.tronkit.models.Transaction
import io.horizontalsystems.tronkit.models.TransactionSyncState
import io.horizontalsystems.tronkit.models.TransactionTag
import io.horizontalsystems.tronkit.models.Trc20EventRecord

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
    exportSchema = false
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
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
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

        fun getInstance(context: Context, databaseName: String): MainDatabase {
            return Room.databaseBuilder(context, MainDatabase::class.java, databaseName)
                .addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade()
                .allowMainThreadQueries()
                .build()
        }
    }
}

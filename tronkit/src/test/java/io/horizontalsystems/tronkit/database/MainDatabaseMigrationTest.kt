package io.horizontalsystems.tronkit.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.tronkit.models.Transaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MIGRATION_4_5 only creates the RawTransactionBroadcastRecord table, so this test-only
 * database declares that single entity: Room's post-migration validation then confirms the
 * migration produced exactly the schema the entity expects, in addition to the explicit column
 * checks below, which read the table the same way production code does.
 */
@Database(version = 5, entities = [RawTransactionBroadcastRecord::class], exportSchema = false)
internal abstract class Migration4To5TestDatabase : RoomDatabase()

// Same idea for MIGRATION_5_6, which only touches the Transaction table.
@Database(version = 6, entities = [Transaction::class], exportSchema = false)
internal abstract class Migration5To6TestDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
class MainDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration4To5_createsRawTransactionBroadcastRecordTable() {
        seedDatabase(version = 4)

        val database = openMigratedDatabase().openHelper.writableDatabase

        val columns = mutableMapOf<String, String>()
        database.query("PRAGMA table_info(`RawTransactionBroadcastRecord`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val typeIndex = cursor.getColumnIndex("type")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIndex)] = cursor.getString(typeIndex)
            }
        }

        assertEquals("TEXT", columns["txId"])
        assertEquals("BLOB", columns["rawTransaction"])
        assertEquals("INTEGER", columns["expiration"])
        assertEquals("INTEGER", columns["createdAt"])
        assertEquals("INTEGER", columns["lastAttemptAt"])
        assertEquals("INTEGER", columns["retriesCount"])
        assertTrue(columns.containsKey("txId"))
    }

    @Test
    fun migration5To6_addsOriginEnergyUsageAndClearsZeroFilledReceipts() {
        seedDatabase(
            version = 5,
            V5_TRANSACTION_TABLE,
            "INSERT INTO `Transaction` VALUES (x'01', 1, 1, 0, 10, 0, 0, 0, 0, 0, 0, NULL, 1)",
            "INSERT INTO `Transaction` VALUES (x'02', 2, 1, 0, 11, 345000, 345, 0, 13000, 0, 13000, NULL, 1)"
        )

        val database = Room.databaseBuilder(context, Migration5To6TestDatabase::class.java, DB_NAME)
            .addMigrations(MainDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        val sqlite = database.openHelper.writableDatabase

        assertEquals(listOf(null, null, null, null, null, null, 0L), sqlite.receiptColumns("01"))
        assertEquals(listOf(345L, 0L, 13_000L, 0L, 13_000L, null, 345_000L), sqlite.receiptColumns("02"))
        database.close()
    }

    private fun SupportSQLiteDatabase.receiptColumns(hashHex: String): List<Long?> =
        query(
            "SELECT netUsage, netFee, energyUsage, energyFee, energyUsageTotal, originEnergyUsage, fee " +
                "FROM `Transaction` WHERE hash = x'$hashHex'"
        ).use { cursor ->
            cursor.moveToFirst()
            (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getLong(it) }
        }

    // Room invokes MIGRATION_4_5 itself while opening this v4 file, exercising the migration
    // through the same path production code uses (unlike calling migrate() directly).
    private fun openMigratedDatabase(): Migration4To5TestDatabase {
        return Room.databaseBuilder(context, Migration4To5TestDatabase::class.java, DB_NAME)
            .addMigrations(MainDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
    }

    private fun seedDatabase(version: Int, vararg statements: String) {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private companion object {
        const val DB_NAME = "migration-test"
        const val V5_TRANSACTION_TABLE = "CREATE TABLE `Transaction` (`hash` BLOB NOT NULL, `timestamp` INTEGER NOT NULL, " +
            "`confirmed` INTEGER NOT NULL, `isFailed` INTEGER NOT NULL, `blockNumber` INTEGER, `fee` INTEGER, " +
            "`netUsage` INTEGER, `netFee` INTEGER, `energyUsage` INTEGER, `energyFee` INTEGER, " +
            "`energyUsageTotal` INTEGER, `contractsRaw` TEXT, `processed` INTEGER NOT NULL, PRIMARY KEY(`hash`))"
    }
}

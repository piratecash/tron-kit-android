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

@RunWith(RobolectricTestRunner::class)
class MainDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration4To5_createsRawTransactionBroadcastRecordTable() {
        seedV4Database()

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

    // Room invokes MIGRATION_4_5 itself while opening this v4 file, exercising the migration
    // through the same path production code uses (unlike calling migrate() directly).
    private fun openMigratedDatabase(): Migration4To5TestDatabase {
        return Room.databaseBuilder(context, Migration4To5TestDatabase::class.java, DB_NAME)
            .addMigrations(MainDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
    }

    private fun seedV4Database() {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private companion object {
        const val DB_NAME = "migration-4-5-test"
    }
}

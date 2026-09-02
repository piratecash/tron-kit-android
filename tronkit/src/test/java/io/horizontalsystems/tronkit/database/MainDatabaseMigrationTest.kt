package io.horizontalsystems.tronkit.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainDatabaseMigrationTest {
    @Test
    fun migration4To5_createsRawTransactionBroadcastRecordTable() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext()
            )
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )

        val database = helper.writableDatabase

        MainDatabase.MIGRATION_4_5.migrate(database)

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

        database.close()
        helper.close()
    }
}

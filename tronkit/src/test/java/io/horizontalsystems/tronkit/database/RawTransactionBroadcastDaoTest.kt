package io.horizontalsystems.tronkit.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawTransactionBroadcastDaoTest {
    private lateinit var database: MainDatabase
    private lateinit var dao: RawTransactionBroadcastDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MainDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.rawTransactionBroadcastDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_duplicateTxId_ignoresNewRecord() {
        dao.insert(record(retriesCount = 0))
        dao.insert(record(retriesCount = 5))

        val records = dao.dueRecords(lastAttemptBefore = 2_000, now = 1_000)

        assertEquals(1, records.size)
        assertEquals(0, records.single().retriesCount)
    }

    @Test
    fun update_existingRecord_updatesRetryState() {
        val initialRecord = record(retriesCount = 0)
        dao.insert(initialRecord)

        dao.update(initialRecord.retried(now = 1_500))

        val records = dao.dueRecords(lastAttemptBefore = 2_000, now = 1_000)

        assertEquals(1, records.size)
        assertEquals(1, records.single().retriesCount)
        assertEquals(1_500L, records.single().lastAttemptAt)
    }

    private fun record(retriesCount: Int) = RawTransactionBroadcastRecord(
        txId = "tx-id",
        rawTransaction = byteArrayOf(1, 2, 3),
        expiration = 3_000,
        createdAt = 1_000,
        lastAttemptAt = null,
        retriesCount = retriesCount
    )
}

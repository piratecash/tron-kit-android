package io.horizontalsystems.tronkit.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.horizontalsystems.tronkit.models.RawTransactionBroadcastRecord

@Dao
interface RawTransactionBroadcastDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(record: RawTransactionBroadcastRecord)

    @Update
    fun update(record: RawTransactionBroadcastRecord)

    @Query("DELETE FROM RawTransactionBroadcastRecord WHERE txId = :txId")
    fun delete(txId: String)

    @Query("DELETE FROM RawTransactionBroadcastRecord WHERE expiration <= :now")
    fun deleteExpired(now: Long)

    @Query(
        """
        SELECT * FROM RawTransactionBroadcastRecord
        WHERE expiration > :now AND (lastAttemptAt IS NULL OR lastAttemptAt <= :lastAttemptBefore)
        ORDER BY createdAt ASC
        """
    )
    fun dueRecords(lastAttemptBefore: Long, now: Long): List<RawTransactionBroadcastRecord>
}

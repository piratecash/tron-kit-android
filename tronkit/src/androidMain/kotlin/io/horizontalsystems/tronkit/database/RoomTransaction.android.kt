package io.horizontalsystems.tronkit.database

import androidx.room.RoomDatabase

internal actual fun RoomDatabase.inTransaction(body: () -> Unit) = runInTransaction(body)

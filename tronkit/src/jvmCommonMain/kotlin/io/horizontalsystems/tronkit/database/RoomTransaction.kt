package io.horizontalsystems.tronkit.database

import androidx.room.RoomDatabase

internal expect fun RoomDatabase.inTransaction(body: () -> Unit)

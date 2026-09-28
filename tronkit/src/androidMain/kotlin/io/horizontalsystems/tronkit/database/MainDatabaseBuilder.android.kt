package io.horizontalsystems.tronkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.tronkit.PlatformContext

internal fun mainDatabaseBuilder(
    context: PlatformContext,
    databaseName: String,
    databaseKey: ByteArray,
): RoomDatabase.Builder<MainDatabase> =
    tronKitDatabases.encrypted(
        Room.databaseBuilder(context, MainDatabase::class.java, databaseName),
        databaseFile(context, databaseName).path,
        databaseKey,
    ).allowMainThreadQueries()

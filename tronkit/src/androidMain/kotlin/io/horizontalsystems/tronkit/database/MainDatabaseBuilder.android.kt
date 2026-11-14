package io.horizontalsystems.tronkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.tronkit.PlatformContext

internal fun mainDatabaseBuilder(
    context: PlatformContext,
    databaseName: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): RoomDatabase.Builder<MainDatabase> =
    tronKitDatabases(nameResolver).encrypted(
        Room.databaseBuilder(context, MainDatabase::class.java, databaseName),
        databaseFile(context, databaseName).path,
        databaseKey,
    ).allowMainThreadQueries()

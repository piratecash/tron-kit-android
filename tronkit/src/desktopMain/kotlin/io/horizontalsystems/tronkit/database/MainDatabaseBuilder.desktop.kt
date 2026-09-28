package io.horizontalsystems.tronkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.tronkit.PlatformContext
import kotlinx.coroutines.Dispatchers

// `allowMainThreadQueries` is ignored: there is no main-thread check off Android.
internal fun mainDatabaseBuilder(
    context: PlatformContext,
    databaseName: String,
    databaseKey: ByteArray,
): RoomDatabase.Builder<MainDatabase> {
    val file = databaseFile(context, databaseName)
    // Verifies the file against the key before any directory is created.
    val builder = tronKitDatabases.encrypted(Room.databaseBuilder<MainDatabase>(file.path), file.path, databaseKey)
    // Unlike Android's getDatabasePath, a JVM driver does not create the parent directory.
    file.absoluteFile.parentFile?.mkdirs()
    // A blocking DAO nested in a transaction must reach Room's `useConnection` undispatched, before
    // its first suspension, so Room recovers the transaction's connection from its thread local.
    return builder.setQueryCoroutineContext(Dispatchers.Unconfined)
}

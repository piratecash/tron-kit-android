package io.horizontalsystems.tronkit.database

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.tronkit.PlatformContext
import io.horizontalsystems.tronkit.network.Network

internal object TronDatabaseManager {

    fun getMainDatabase(
        context: PlatformContext,
        network: Network,
        walletId: String,
        databaseKey: ByteArray,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): MainDatabase {
        return MainDatabase.getInstance(context, getDatabaseName(network, walletId), databaseKey, nameResolver)
    }

    suspend fun migrateMainDatabase(
        context: PlatformContext,
        network: Network,
        walletId: String,
        databaseKey: ByteArray,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): DatabaseMigrationResult {
        return MainDatabase.migrateDatabase(context, getDatabaseName(network, walletId), databaseKey, nameResolver)
    }

    fun clear(
        context: PlatformContext,
        network: Network,
        walletId: String,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ) {
        synchronized(this) {
            // Same migrationId as MainDatabase.migrateDatabase, so an interrupted migration's manifest is cleared too.
            val file = databaseFile(context, nameResolver.resolve(getDatabaseName(network, walletId)))
            tronKitDatabases(nameResolver).clearDatabases(
                dataDir = file.absoluteFile.parent,
                databaseNames = listOf(file.name),
                migrationId = file.path,
            )
        }
    }

    private fun getDatabaseName(network: Network, walletId: String): String {
        return "Tron-${network.name}-$walletId"
    }

}

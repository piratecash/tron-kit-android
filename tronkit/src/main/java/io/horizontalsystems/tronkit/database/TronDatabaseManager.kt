package io.horizontalsystems.tronkit.database

import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.tronkit.PlatformContext
import io.horizontalsystems.tronkit.network.Network

internal object TronDatabaseManager {

    fun getMainDatabase(context: PlatformContext, network: Network, walletId: String, databaseKey: ByteArray): MainDatabase {
        return MainDatabase.getInstance(context, getDatabaseName(network, walletId), databaseKey)
    }

    suspend fun migrateMainDatabase(
        context: PlatformContext,
        network: Network,
        walletId: String,
        databaseKey: ByteArray
    ): DatabaseMigrationResult {
        return MainDatabase.migrateDatabase(context, getDatabaseName(network, walletId), databaseKey)
    }

    fun clear(context: PlatformContext, network: Network, walletId: String) {
        synchronized(this) {
            // Same migrationId as MainDatabase.migrateDatabase, so an interrupted migration's manifest is cleared too.
            val file = databaseFile(context, getDatabaseName(network, walletId))
            tronKitDatabases.clearDatabases(
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

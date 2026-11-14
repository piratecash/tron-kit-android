package io.horizontalsystems.tronkit.database

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases

// The namespace names the on-disk manifest and lock files (`.tron-kit-sqlcipher*`); the default must never change.
private const val NAMESPACE = "tron-kit"

internal fun resolvedNamespace(nameResolver: DatabaseNameResolver): String = nameResolver.resolve(NAMESPACE)

internal fun tronKitDatabases(nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity) =
    SqlCipherDatabases(resolvedNamespace(nameResolver))

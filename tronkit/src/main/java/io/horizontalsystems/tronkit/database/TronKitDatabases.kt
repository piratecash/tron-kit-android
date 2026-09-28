package io.horizontalsystems.tronkit.database

import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases

// The namespace names the on-disk manifest and lock files (`.tron-kit-sqlcipher*`); it must never change.
internal val tronKitDatabases = SqlCipherDatabases("tron-kit")

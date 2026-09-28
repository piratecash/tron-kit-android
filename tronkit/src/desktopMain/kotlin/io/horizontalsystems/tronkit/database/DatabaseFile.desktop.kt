package io.horizontalsystems.tronkit.database

import io.horizontalsystems.tronkit.PlatformContext
import java.io.File

internal actual fun databaseFile(context: PlatformContext, name: String): File =
    File(name).takeIf { it.isAbsolute } ?: File(context.dataDir, name)

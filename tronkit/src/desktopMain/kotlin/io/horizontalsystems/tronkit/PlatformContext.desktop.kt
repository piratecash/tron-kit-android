package io.horizontalsystems.tronkit

import java.io.File

actual abstract class PlatformContext internal constructor() {
    abstract val dataDir: File
}

/**
 * [dataDir] holds the kit's databases and must be a writable directory (created if missing); otherwise
 * database migration and clearing fail with [IllegalArgumentException].
 */
fun PlatformContext(dataDir: File): PlatformContext = DesktopPlatformContext(dataDir)

private class DesktopPlatformContext(override val dataDir: File) : PlatformContext()

package io.horizontalsystems.tronkit

import java.util.logging.Level
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger("TronKit")

internal actual fun logWarning(message: String, error: Throwable) {
    logger.log(Level.WARNING, message, error)
}

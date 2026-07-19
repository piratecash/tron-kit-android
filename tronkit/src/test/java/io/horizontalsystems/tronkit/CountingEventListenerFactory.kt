package io.horizontalsystems.tronkit

import okhttp3.Call
import okhttp3.EventListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test fixture shared by provider- and kit-level tests that verify an `eventListenerFactory`
 * was actually wired into the OkHttp client used for a request.
 */
internal class CountingEventListenerFactory : EventListener.Factory {
    val count = AtomicInteger(0)

    override fun create(call: Call): EventListener {
        count.incrementAndGet()
        return EventListener.NONE
    }
}

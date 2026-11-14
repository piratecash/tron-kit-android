package io.horizontalsystems.tronkit.models

data class AccountResources(
    val freeBandwidth: Resource = Resource(),
    val stakedBandwidth: Resource = Resource(),
    val energy: Resource = Resource(),
) {
    data class Resource(val limit: Long = 0, val used: Long = 0) {
        val available: Long
            get() = (limit - used).coerceAtLeast(0)
    }
}

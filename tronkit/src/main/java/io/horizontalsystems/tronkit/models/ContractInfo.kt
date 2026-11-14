package io.horizontalsystems.tronkit.models

/** Energy rules of a smart contract: the deployer pays (100 − percent)% of the call, capped by the limit. */
data class ContractInfo(
    val consumeUserResourcePercent: Long,
    val originEnergyLimit: Long,
    val originAddress: Address?,
)

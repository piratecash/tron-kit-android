package io.horizontalsystems.tronkit.models

data class TransactionInfo(
    val fee: Long,
    val blockNumber: Long,
    val netUsage: Long,
    val netFee: Long,
    val energyUsage: Long,
    val energyFee: Long,
    val originEnergyUsage: Long,
    val energyUsageTotal: Long,
)

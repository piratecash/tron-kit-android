package io.horizontalsystems.tronkit.network

import java.math.BigInteger

interface INodeApiProvider {
    suspend fun fetchAccount(address: String): NodeAccountResponse?
    suspend fun fetchChainParameters(): List<ChainParameterResponse>
    suspend fun getNowBlock(): NowBlock
    suspend fun createTransaction(ownerAddress: String, toAddress: String, amount: BigInteger): CreatedTransaction
    suspend fun triggerSmartContract(
        ownerAddress: String,
        contractAddress: String,
        functionSelector: String,
        parameter: String,
        callValue: Long,
        feeLimit: Long
    ): CreatedTransaction
    suspend fun broadcastTransaction(createdTransaction: CreatedTransaction, signature: ByteArray)
    suspend fun broadcastTransaction(signedTransaction: SignedTransaction): String
    suspend fun transactionExists(txId: String): Boolean
    suspend fun estimateEnergy(
        ownerAddress: String,
        contractAddress: String,
        functionSelector: String,
        parameter: String
    ): Long
    suspend fun triggerConstantContract(
        ownerAddress: String,
        contractAddress: String,
        functionSelector: String,
        parameter: String
    ): Long
}

data class NodeAccountResponse(val balance: BigInteger)

data class ChainParameterResponse(val key: String, val value: Long)

/** A recent block used as the TAPOS reference anchor for offline transaction building. */
data class NowBlock(val number: Long, val blockId: String, val timestamp: Long)

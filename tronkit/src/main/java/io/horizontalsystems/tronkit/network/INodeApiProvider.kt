package io.horizontalsystems.tronkit.network

import io.horizontalsystems.tronkit.models.AccountResources
import io.horizontalsystems.tronkit.models.ContractInfo
import io.horizontalsystems.tronkit.models.TransactionInfo
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
    suspend fun getAccountResource(address: String): AccountResources
    suspend fun getContract(contractAddress: String): ContractInfo

    /** Null while the transaction is not in a block yet. */
    suspend fun getTransactionInfoById(txId: String): TransactionInfo?
}

data class NodeAccountResponse(
    val balance: BigInteger,
    val ownerPermission: NodePermission? = null,
    val activePermissions: List<NodePermission> = emptyList(),
)

data class NodePermission(val threshold: Long, val keys: List<NodePermissionKey>, val operations: String? = null)

data class NodePermissionKey(val address: String, val weight: Long)

data class ChainParameterResponse(val key: String, val value: Long)

/** A recent block used as the TAPOS reference anchor for offline transaction building. */
data class NowBlock(val number: Long, val blockId: String, val timestamp: Long)

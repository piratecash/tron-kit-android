package io.horizontalsystems.tronkit.transaction

import com.google.protobuf.ByteString
import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.models.Contract
import io.horizontalsystems.tronkit.models.TransferContract
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.network.SignedTransaction
import io.horizontalsystems.tronkit.network.signedTransaction
import org.tron.protos.Protocol.Transaction

class TransactionSender(
    private val nodeApiProvider: INodeApiProvider
) {
    private fun isValidCreatedTransaction(createdTransaction: CreatedTransaction, contract: Contract): Boolean {
        val rawData = Transaction.raw.parseFrom(ByteString.fromHex(createdTransaction.raw_data_hex))
        val createdContractProto = if (rawData.contractCount == 1) rawData.getContract(0) else null
        val originalContractProto = contract.proto

        return createdContractProto != null &&
                createdContractProto.type == originalContractProto.type &&
                createdContractProto.hasParameter() &&
                createdContractProto.parameter == originalContractProto.parameter
    }

    suspend fun createTransaction(contract: Contract, feeLimit: Long?): CreatedTransaction {
        val createdTransaction = when (contract) {
            is TransferContract -> {
                nodeApiProvider.createTransaction(
                    ownerAddress = contract.ownerAddress.hex,
                    toAddress = contract.toAddress.hex,
                    amount = contract.amount
                )
            }

            is TriggerSmartContract -> {
                nodeApiProvider.triggerSmartContract(
                    ownerAddress = contract.ownerAddress.hex,
                    contractAddress = contract.contractAddress.hex,
                    functionSelector = contract.functionSelector ?: throw TransactionError.NoFunctionSelector(contract),
                    parameter = contract.parameter ?: throw TransactionError.NoParameter(contract),
                    callValue = contract.callValue?.toLong() ?: 0,
                    feeLimit = feeLimit ?: throw TransactionError.NoFeeLimit(contract)
                )
            }

            else -> {
                throw TransactionError.NotSupportedContract(contract)
            }
        }

        if (isValidCreatedTransaction(createdTransaction, contract)) {
            return createdTransaction
        } else {
            throw TransactionError.InvalidCreatedTransaction(createdTransaction.raw_data_hex)
        }
    }

    suspend fun broadcastTransaction(createdTransaction: CreatedTransaction, signer: Signer): String {
        val signedTransaction = signTransaction(createdTransaction, signer)
        nodeApiProvider.broadcastTransaction(signedTransaction)
        return createdTransaction.txID.lowercase()
    }

    internal fun signedRawTransaction(createdTransaction: CreatedTransaction, signer: Signer) =
        RawTransactionUtils.encode(signTransaction(createdTransaction, signer))

    internal fun signTransaction(createdTransaction: CreatedTransaction, signer: Signer): SignedTransaction {
        return createdTransaction.signedTransaction(signer.sign(createdTransaction))
    }

}

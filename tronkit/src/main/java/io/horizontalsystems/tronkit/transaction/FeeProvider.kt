package io.horizontalsystems.tronkit.transaction

import com.google.protobuf.ByteString
import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.models.AccountResources
import io.horizontalsystems.tronkit.models.AccountStatus
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.Contract
import io.horizontalsystems.tronkit.models.TransferAssetContract
import io.horizontalsystems.tronkit.models.TransferContract
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.sync.ChainParameterManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tron.protos.Protocol.Transaction


sealed class Fee {

    val feeInSuns: Long
        get() = when (this) {
            is AccountActivation -> amount
            is Bandwidth -> if (source == Bandwidth.Source.Burn) burnInSuns else 0
            is Energy -> (required - fromAccount - fromContract) * price
        }

    data class Bandwidth(
        val points: Long,
        val price: Long, //sun
        val source: Source = Source.Burn,
        val burnInSuns: Long = points * price
    ) : Fee() {
        enum class Source { Staked, Free, Burn }
    }

    data class Energy(
        val required: Long,
        val price: Long, //sun
        val fromAccount: Long = 0,
        val fromContract: Long = 0
    ) : Fee() {
        // The fee_limit to sign: it must cover the whole call, whoever pays for the energy.
        val feeLimitInSuns: Long
            get() = required * price
    }

    data class AccountActivation(
        val amount: Long //sun current 1TRX
    ) : Fee()

}

class FeeProvider(
    private val nodeApiProvider: INodeApiProvider,
    private val chainParameterManager: ChainParameterManager
) {

    private val MAX_RESULT_SIZE_IN_TX: Long = 64

    private val _accountResourcesFlow = MutableStateFlow<AccountResources?>(null)
    val accountResourcesFlow: StateFlow<AccountResources?> = _accountResourcesFlow.asStateFlow()

    // Unlike the fee estimate, an explicit refresh reports the failure so the caller can retry.
    suspend fun refreshAccountResources(owner: Address) {
        _accountResourcesFlow.value = nodeApiProvider.getAccountResource(owner.hex)
    }

    suspend fun isAccountActive(address: Address) =
        nodeApiProvider.fetchAccount(address.hex) != null

    suspend fun accountStatus(address: Address) =
        AccountStatus.from(address, nodeApiProvider.fetchAccount(address.hex))

    // Also the reserve for a transfer whose recipient is not known yet: the worst case for TRX/TRC10.
    fun feesAccountActivation(): List<Fee> {
        return listOf(
            Fee.Bandwidth(points = chainParameterManager.createAccountFee, price = chainParameterManager.transactionFee),
            Fee.AccountActivation(amount = chainParameterManager.createNewAccountFeeInSystemContract)
        )
    }

    private fun estimateBandwidth(contract: Contract, feeLimit: Long): Long {
        val transactionBuilder = Transaction.newBuilder()

        transactionBuilder.setRawData(
            Transaction.raw.newBuilder()
                .addContract(contract.proto)
                .setTimestamp(System.currentTimeMillis())
                .setExpiration(System.currentTimeMillis())
                .setRefBlockBytes(ByteString.copyFrom(ByteArray(2)))
                .setRefBlockHash(ByteString.copyFrom(ByteArray(8)))
                .setFeeLimit(feeLimit)
        )
            .addSignature(ByteString.copyFrom(ByteArray(65)))
            .clearRet()

        val transaction = transactionBuilder.build()

        return transaction.serializedSize + MAX_RESULT_SIZE_IN_TX
    }

    private suspend fun estimateEnergy(contract: TriggerSmartContract): Long {
        return try {
            nodeApiProvider.estimateEnergy(
                ownerAddress = contract.ownerAddress.hex,
                contractAddress = contract.contractAddress.hex,
                functionSelector = contract.functionSelector
                    ?: throw TronKit.TransactionError.NoFunctionSelector(contract),
                parameter = contract.parameter
                    ?: throw TronKit.TransactionError.NoParameter(contract)
            )
        } catch (error: Throwable) {
            if (!error.isUnsupportedEstimateEnergy()) {
                throw error
            }

            nodeApiProvider.triggerConstantContract(
                ownerAddress = contract.ownerAddress.hex,
                contractAddress = contract.contractAddress.hex,
                functionSelector = contract.functionSelector
                    ?: throw TronKit.TransactionError.NoFunctionSelector(contract),
                parameter = contract.parameter
                    ?: throw TronKit.TransactionError.NoParameter(contract)
            )
        }
    }

    private fun Throwable.isUnsupportedEstimateEnergy(): Boolean {
        val message = message ?: return false
        return message.contains("does not support estimate energy", ignoreCase = true)
    }

    suspend fun estimateFee(contract: Contract): List<Fee> = when (contract) {
        is TransferContract -> transferFees(contract, contract.ownerAddress, contract.toAddress)
        is TransferAssetContract -> transferFees(contract, contract.ownerAddress, contract.toAddress)
        is TriggerSmartContract -> smartContractFees(contract)
        else -> throw TronKit.TransactionError.NotSupportedContract(contract)
    }

    private suspend fun transferFees(contract: Contract, owner: Address, recipient: Address): List<Fee> {
        val recipientActive = isAccountActive(recipient)
        val resources = ownerResources(owner)
        val points = estimateBandwidth(contract, feeLimit = 0)

        if (recipientActive) return listOf(bandwidthFee(points, resources))

        return listOf(
            accountCreationBandwidthFee(points, resources),
            Fee.AccountActivation(amount = chainParameterManager.createNewAccountFeeInSystemContract)
        )
    }

    private suspend fun smartContractFees(contract: TriggerSmartContract): List<Fee> {
        val required = estimateEnergy(contract)
        val resources = ownerResources(contract.ownerAddress)
        val fromContract = deployerEnergyShare(contract, required)
        val energyFee = Fee.Energy(
            required = required,
            price = chainParameterManager.energyFee,
            fromAccount = minOf(required - fromContract, resources.energy.available),
            fromContract = fromContract
        )
        val points = estimateBandwidth(contract, energyFee.feeLimitInSuns)

        return listOf(energyFee, bandwidthFee(points, resources))
    }

    // Each quota must cover the whole transaction, otherwise all of it is burned.
    private fun bandwidthFee(points: Long, resources: AccountResources): Fee.Bandwidth {
        val source = when {
            resources.stakedBandwidth.available >= points -> Fee.Bandwidth.Source.Staked
            resources.freeBandwidth.available >= points -> Fee.Bandwidth.Source.Free
            else -> Fee.Bandwidth.Source.Burn
        }
        return Fee.Bandwidth(points = points, price = chainParameterManager.transactionFee, source = source)
    }

    // java-tron BandwidthProcessor.consumeForCreateNewAccount: no free quota, a flat burn instead of points × price.
    private fun accountCreationBandwidthFee(txPoints: Long, resources: AccountResources): Fee.Bandwidth {
        val points = txPoints * chainParameterManager.createNewAccountBandwidthRate
        val source = if (resources.stakedBandwidth.available >= points) Fee.Bandwidth.Source.Staked else Fee.Bandwidth.Source.Burn
        return Fee.Bandwidth(
            points = points,
            price = chainParameterManager.transactionFee,
            source = source,
            burnInSuns = chainParameterManager.createAccountFeeInSuns
        )
    }

    // Contract settings are mutable on-chain, so they are read on every estimate.
    private suspend fun deployerEnergyShare(contract: TriggerSmartContract, required: Long): Long {
        val info = orNull { nodeApiProvider.getContract(contract.contractAddress.hex) } ?: return 0
        val deployer = info.originAddress ?: return 0
        // A caller who deployed the contract pays from one pool (java-tron ReceiptCapsule).
        if (deployer == contract.ownerAddress || info.consumeUserResourcePercent >= 100) return 0

        val deployerAvailable = orNull { nodeApiProvider.getAccountResource(deployer.hex) }?.energy?.available ?: return 0
        val subsidised = required * (100 - info.consumeUserResourcePercent) / 100
        return minOf(subsidised, info.originEnergyLimit, deployerAvailable)
    }

    private suspend fun ownerResources(owner: Address): AccountResources =
        orNull { nodeApiProvider.getAccountResource(owner.hex) }
            ?.also { _accountResourcesFlow.value = it }
            ?: AccountResources()

    // Resource lookups only refine the estimate; a failure falls back to everything burned.
    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

}

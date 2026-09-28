package io.horizontalsystems.tronkit.rpc

import io.horizontalsystems.tronkit.contracts.ContractMethodHelper
import io.horizontalsystems.tronkit.contracts.trc20.DecimalsMethod
import io.horizontalsystems.tronkit.contracts.trc20.NameMethod
import io.horizontalsystems.tronkit.contracts.trc20.SymbolMethod
import io.horizontalsystems.tronkit.decoration.TokenInfo
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.network.IRpcApiProvider
import io.horizontalsystems.tronkit.network.Network
import io.horizontalsystems.tronkit.network.TronGridProvider
import io.horizontalsystems.tronkit.toBigInteger
import io.horizontalsystems.tronkit.toHexString

class Trc20Provider(
    private val rpcApiProvider: IRpcApiProvider
) {
    class TokenNotFoundException : Throwable()

    suspend fun getTokenInfo(contractAddress: Address): TokenInfo {
        val name = getTokenName(contractAddress)
        val symbol = getTokenSymbol(contractAddress)
        val decimals = getDecimals(contractAddress)

        return TokenInfo(name, symbol, decimals)
    }

    suspend fun getDecimals(contractAddress: Address): Int {
        val response = rpcApiProvider.fetch(CallJsonRpc(
            contractAddress = "0x${contractAddress.hex}",
            data = DecimalsMethod().encodedABI().toHexString(),
            defaultBlockParameter = DefaultBlockParameter.Latest.raw
        ))
        if (response.isEmpty()) throw TokenNotFoundException()

        return response.sliceArray(IntRange(0, 31)).toBigInteger().toInt()
    }

    suspend fun getTokenSymbol(contractAddress: Address): String {
        val response = rpcApiProvider.fetch(CallJsonRpc(
            contractAddress = "0x${contractAddress.hex}",
            data = SymbolMethod().encodedABI().toHexString(),
            defaultBlockParameter = DefaultBlockParameter.Latest.raw
        ))

        if (response.isEmpty()) throw TokenNotFoundException()

        val argumentTypes = listOf(ByteArray::class)
        val parsedArguments = ContractMethodHelper.decodeABI(response, argumentTypes)
        val stringBytes = parsedArguments[0] as? ByteArray ?: throw TokenNotFoundException()

        return String(stringBytes)
    }

    suspend fun getTokenName(contractAddress: Address): String {
        val response = rpcApiProvider.fetch(CallJsonRpc(
            contractAddress = "0x${contractAddress.hex}",
            data = NameMethod().encodedABI().toHexString(),
            defaultBlockParameter = DefaultBlockParameter.Latest.raw
        ))

        if (response.isEmpty()) throw TokenNotFoundException()

        val argumentTypes = listOf(ByteArray::class)
        val parsedArguments = ContractMethodHelper.decodeABI(response, argumentTypes)
        val stringBytes = parsedArguments[0] as? ByteArray ?: throw TokenNotFoundException()

        return String(stringBytes)
    }

    companion object {
        fun getInstance(network: Network, tronGridApiKeys: List<String>): Trc20Provider {
            val tronGridProvider = TronGridProvider(network.tronGridUrl, tronGridApiKeys)
            return Trc20Provider(tronGridProvider)
        }
    }

}

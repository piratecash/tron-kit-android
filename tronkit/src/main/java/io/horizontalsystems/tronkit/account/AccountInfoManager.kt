package io.horizontalsystems.tronkit.account

import io.horizontalsystems.tronkit.database.Storage
import io.horizontalsystems.tronkit.models.AccountInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

class AccountInfoManager(
    private val storage: Storage
) {

    var trxBalance: BigInteger = storage.getTrxBalance() ?: BigInteger.ZERO
        private set(value) {
            if (value != field) {
                field = value
                _trxBalanceFlow.update { value }
            }
        }

    private val _trxBalanceFlow = MutableStateFlow(trxBalance)
    val trxBalanceFlow: StateFlow<BigInteger> = _trxBalanceFlow

    private val _trc20BalancesMap = ConcurrentHashMap<String, MutableStateFlow<BigInteger>>()

    fun getTrc20Balance(contractAddress: String): BigInteger {
        return storage.getTrc20Balance(contractAddress) ?: BigInteger.ZERO
    }

    fun getTrc20BalanceFlow(contractAddress: String): StateFlow<BigInteger> =
        _trc20BalancesMap.computeIfAbsent(contractAddress) {
            MutableStateFlow(BigInteger.ZERO)
        }

    fun handle(accountInfo: AccountInfo) {
        storage.saveBalances(
            trxBalance = accountInfo.balance,
            balances = accountInfo.trc20Balances
        )

        val contractsWithBalance = accountInfo.trc20Balances.map { it.contractAddress }.toSet()

        accountInfo.trc20Balances.forEach { trc20Balance ->
            _trc20BalancesMap.computeIfAbsent(trc20Balance.contractAddress) {
                MutableStateFlow(trc20Balance.balance)
            }.update { trc20Balance.balance }
        }

        _trc20BalancesMap.forEach { (contractAddress, flow) ->
            if (contractAddress !in contractsWithBalance) {
                flow.update { BigInteger.ZERO }
            }
        }

        trxBalance = accountInfo.balance
    }

}

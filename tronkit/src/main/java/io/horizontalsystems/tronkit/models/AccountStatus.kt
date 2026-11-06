package io.horizontalsystems.tronkit.models

import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.network.NodeAccountResponse
import io.horizontalsystems.tronkit.network.NodePermission
import io.horizontalsystems.tronkit.network.NodePermissionKey

/** Voting, staking and resources: a key limited to these cannot move coins out of the account. */
private val SAFE_OPERATIONS = setOf(4, 11, 12, 13, 54, 55, 56, 57, 58, 59)
private val HEX_BYTES = Regex("(?:[0-9a-fA-F]{2})+")

enum class AccountStatus {
    NotActive, Active, ControlledByOthers;

    companion object {
        fun from(address: Address, account: NodeAccountResponse?): AccountStatus {
            account ?: return NotActive
            val owner = account.ownerPermission ?: return Active
            val controlledByOthers = owner.ownWeight(address) < owner.threshold ||
                owner.foreignWeight(address) >= owner.threshold ||
                account.activePermissions.any { active ->
                    active.foreignWeight(address) >= active.threshold && !active.grantsOnlySafeOperations()
                }
            return if (controlledByOthers) ControlledByOthers else Active
        }
    }
}

private fun NodePermission.ownWeight(address: Address) =
    keys.filter { it.belongsTo(address) }.sumOf { it.weight }

private fun NodePermission.foreignWeight(address: Address) =
    keys.filterNot { it.belongsTo(address) }.sumOf { it.weight }

private fun NodePermissionKey.belongsTo(owner: Address) = address.equals(owner.hex, ignoreCase = true)

private fun NodePermission.grantsOnlySafeOperations(): Boolean {
    if (operations?.matches(HEX_BYTES) != true) return false
    val granted = operations.hexStringToByteArray().flatMapIndexed { i, byte ->
        (0 until 8).filter { j -> (byte.toInt() shr j) and 1 == 1 }.map { j -> i * 8 + j }
    }
    return SAFE_OPERATIONS.containsAll(granted)
}

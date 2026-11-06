package io.horizontalsystems.tronkit.models

import io.horizontalsystems.tronkit.network.NodeAccountResponse
import io.horizontalsystems.tronkit.network.NodePermission
import io.horizontalsystems.tronkit.network.NodePermissionKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class AccountStatusTest {

    private val own = "41ce7ba9c4618bc93f00c6673f73042fc0a33f1b62"
    private val foreignA = "410a38028ed6146aa29c687c052b233131468b6635"
    private val foreignB = "417064a91922e737f220cbd0c7770c3eaf5d40e013"
    private val foreignC = "4147320df94ed2f975e015151cff71ac949733531c"
    private val address = Address.fromHex(own)

    private val ownOwner = NodePermission(threshold = 1, keys = listOf(NodePermissionKey(own, 1)))

    private fun status(
        owner: NodePermission? = ownOwner,
        active: List<NodePermission> = emptyList(),
    ) = AccountStatus.from(address, NodeAccountResponse(BigInteger.ZERO, owner, active))

    private fun foreignActive(operations: String?, threshold: Long = 1, weight: Long = 1) =
        NodePermission(threshold, listOf(NodePermissionKey(foreignA, weight)), operations)

    private fun operations(vararg types: Int): String {
        val bytes = IntArray(32)
        types.forEach { bytes[it / 8] = bytes[it / 8] or (1 shl (it % 8)) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun from_nullResponse_returnsNotActive() {
        assertEquals(AccountStatus.NotActive, AccountStatus.from(address, null))
    }

    @Test
    fun from_noOwnerPermission_returnsActive() {
        assertEquals(AccountStatus.Active, status(owner = null))
    }

    @Test
    fun from_noOwnerPermissionButForeignActive_returnsActive() {
        assertEquals(AccountStatus.Active, status(owner = null, active = listOf(foreignActive(null))))
    }

    @Test
    fun from_defaultPermissions_returnsActive() {
        val active = NodePermission(
            threshold = 1,
            keys = listOf(NodePermissionKey(own, 1)),
            operations = "7fff1fc0033efb0f000000000000000000000000000000000000000000000000",
        )

        assertEquals(AccountStatus.Active, status(active = listOf(active)))
    }

    @Test
    fun from_foreignOwnerWeightReachesThreshold_returnsControlledByOthers() {
        val owner = NodePermission(1, listOf(NodePermissionKey(own, 1), NodePermissionKey(foreignA, 1)))

        assertEquals(AccountStatus.ControlledByOthers, status(owner))
    }

    @Test
    fun from_foreignOwnerWeightBelowThreshold_returnsActive() {
        val owner = NodePermission(2, listOf(NodePermissionKey(own, 2), NodePermissionKey(foreignA, 1)))

        assertEquals(AccountStatus.Active, status(owner))
    }

    @Test
    fun from_ownOwnerWeightBelowThresholdWithoutForeignKeys_returnsControlledByOthers() {
        val owner = NodePermission(2, listOf(NodePermissionKey(own, 1)))

        assertEquals(AccountStatus.ControlledByOthers, status(owner))
    }

    @Test
    fun from_ownerWithoutOwnKey_returnsControlledByOthers() {
        val owner = NodePermission(1, listOf(NodePermissionKey(foreignA, 1)))

        assertEquals(AccountStatus.ControlledByOthers, status(owner))
    }

    @Test
    fun from_trapOwnWeight1ForeignWeights50Threshold100_returnsControlledByOthers() {
        val owner = NodePermission(
            threshold = 100,
            keys = listOf(
                NodePermissionKey(own, 1),
                NodePermissionKey(foreignA, 50),
                NodePermissionKey(foreignB, 50),
                NodePermissionKey(foreignC, 50),
            ),
        )

        assertEquals(AccountStatus.ControlledByOthers, status(owner))
    }

    @Test
    fun from_foreignActiveWithVotingAndResourceOperations_returnsActive() {
        val votingAndResources = "0000000000000006000000000000000000000000000000000000000000000000"

        assertEquals(AccountStatus.Active, status(active = listOf(foreignActive(votingAndResources))))
    }

    @Test
    fun from_foreignActiveWithEachSafeOperation_returnsActive() {
        listOf(4, 11, 12, 13, 54, 55, 56, 57, 58, 59).forEach { type ->
            assertEquals("type $type", AccountStatus.Active, status(active = listOf(foreignActive(operations(type)))))
        }
    }

    @Test
    fun from_foreignActiveWithAllSafeOperations_returnsActive() {
        val all = operations(4, 11, 12, 13, 54, 55, 56, 57, 58, 59)

        assertEquals(AccountStatus.Active, status(active = listOf(foreignActive(all))))
    }

    @Test
    fun from_foreignActiveWithTransfer_returnsControlledByOthers() {
        val transfer = "0200000000000000000000000000000000000000000000000000000000000000"

        assertEquals(AccountStatus.ControlledByOthers, status(active = listOf(foreignActive(transfer))))
    }

    @Test
    fun from_foreignActiveWithSafeOperationAndTransfer_returnsControlledByOthers() {
        assertEquals(AccountStatus.ControlledByOthers, status(active = listOf(foreignActive(operations(57, 1)))))
    }

    @Test
    fun from_foreignActiveWithUnknownOperation_returnsControlledByOthers() {
        val unknown = operations(200)

        assertEquals(AccountStatus.ControlledByOthers, status(active = listOf(foreignActive(unknown))))
    }

    @Test
    fun from_foreignActiveWithMissingEmptyOrUnreadableOperations_returnsControlledByOthers() {
        listOf(null, "", "zz", "abc", "+6", "not hex").forEach { unreadable ->
            assertEquals(
                "operations $unreadable",
                AccountStatus.ControlledByOthers,
                status(active = listOf(foreignActive(unreadable))),
            )
        }
    }

    @Test
    fun from_foreignActiveWithAllZeroOperations_returnsActive() {
        assertEquals(AccountStatus.Active, status(active = listOf(foreignActive("00".repeat(32)))))
    }

    @Test
    fun from_foreignActiveWeightBelowThreshold_returnsActive() {
        val active = foreignActive(operations(1), threshold = 2, weight = 1)

        assertEquals(AccountStatus.Active, status(active = listOf(active)))
    }

    @Test
    fun from_foreignActiveKeysTogetherReachThreshold_returnsControlledByOthers() {
        val active = NodePermission(
            threshold = 2,
            keys = listOf(NodePermissionKey(foreignA, 1), NodePermissionKey(foreignB, 1)),
            operations = operations(1),
        )

        assertEquals(AccountStatus.ControlledByOthers, status(active = listOf(active)))
    }

    @Test
    fun from_dangerousSecondActive_returnsControlledByOthers() {
        val active = listOf(foreignActive(operations(57)), foreignActive(operations(1)))

        assertEquals(AccountStatus.ControlledByOthers, status(active = active))
    }

    @Test
    fun from_ownActiveWithTransfer_returnsActive() {
        val active = NodePermission(1, listOf(NodePermissionKey(own, 1)), operations(1))

        assertEquals(AccountStatus.Active, status(active = listOf(active)))
    }

    @Test
    fun from_ownKeyInDifferentHexCase_isNotForeign() {
        val upper = own.uppercase()
        val owner = NodePermission(1, listOf(NodePermissionKey(upper, 1)))
        val active = NodePermission(1, listOf(NodePermissionKey(upper, 1)), operations(1))

        assertEquals(AccountStatus.Active, status(owner, listOf(active)))
    }

    @Test
    fun from_upperCaseOperations_decodesThem() {
        val uppercaseSafe = "00000000000000" + "0E" + "00".repeat(24)

        assertEquals(AccountStatus.Active, status(active = listOf(foreignActive(uppercaseSafe))))
    }
}

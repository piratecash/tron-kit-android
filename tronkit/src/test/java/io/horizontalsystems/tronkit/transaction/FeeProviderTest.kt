package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.tronkit.models.AccountResources
import io.horizontalsystems.tronkit.models.AccountStatus
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.ContractInfo
import io.horizontalsystems.tronkit.models.TransferContract
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.network.NodeAccountResponse
import io.horizontalsystems.tronkit.network.TronGridProvider
import io.horizontalsystems.tronkit.sync.ChainParameterManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

@OptIn(ExperimentalCoroutinesApi::class)
class FeeProviderTest {

    private interface TestApiProvider : INodeApiProvider

    private val apiProvider = mockk<TestApiProvider>()
    private val chainParameterManager = mockk<ChainParameterManager>()
    private val feeProvider = FeeProvider(apiProvider, chainParameterManager)

    private val ownerAddress = Address.fromHex("41ce7ba9c4618bc93f00c6673f73042fc0a33f1b62")
    private val contractAddress = Address.fromHex("410a38028ed6146aa29c687c052b233131468b6635")
    private val deployerAddress = Address.fromHex("41a614f803b6fd780986a42c78ec9c7f77e6ded13c")
    private val userPaysAll = ContractInfo(100, 0, deployerAddress)
    private val triggerSmartContract = TriggerSmartContract(
        data = "a9059cbb00000000",
        ownerAddress = ownerAddress,
        contractAddress = contractAddress,
        callValue = BigInteger.ZERO,
        callTokenValue = null,
        tokenId = null,
        functionSelector = "transfer(address,uint256)",
        parameter = "00000000"
    )

    private val transfer = TransferContract(BigInteger.ONE, ownerAddress, contractAddress)
    private val zeroBalanceAccount = """
        {
          "address": "${contractAddress.hex}",
          "owner_permission": {"threshold": 1, "keys": [{"address": "${contractAddress.hex}", "weight": 1}]}
        }
    """

    @Test
    fun estimateFee_triggerSmartContract_unsupportedEstimateEnergy_fallsBackToTriggerConstantContract() = runTest {
        val fallbackEnergyRequired = 64_300L

        every { chainParameterManager.energyFee } returns 420L
        every { chainParameterManager.transactionFee } returns 1_000L

        coEvery {
            apiProvider.estimateEnergy(
                ownerAddress = ownerAddress.hex,
                contractAddress = contractAddress.hex,
                functionSelector = "transfer(address,uint256)",
                parameter = "00000000"
            )
        } throws IllegalStateException(
            "estimateEnergy error: CONTRACT_VALIDATE_ERROR - this node does not support estimate energy"
        )
        coEvery {
            apiProvider.triggerConstantContract(
                ownerAddress = ownerAddress.hex,
                contractAddress = contractAddress.hex,
                functionSelector = "transfer(address,uint256)",
                parameter = "00000000"
            )
        } returns fallbackEnergyRequired

        val fees = feeProvider.estimateFee(triggerSmartContract)
        val energyFee = fees.filterIsInstance<Fee.Energy>().single()

        assertEquals(fallbackEnergyRequired, energyFee.required)
        coVerify(exactly = 1) {
            apiProvider.triggerConstantContract(
                ownerAddress = ownerAddress.hex,
                contractAddress = contractAddress.hex,
                functionSelector = "transfer(address,uint256)",
                parameter = "00000000"
            )
        }
    }

    @Test
    fun estimateFee_triggerSmartContract_otherEstimateError_throwsOriginalError() = runTest {
        val originalError = IllegalStateException("estimateEnergy error: CONTRACT_VALIDATE_ERROR - execution reverted")

        every { chainParameterManager.energyFee } returns 420L
        every { chainParameterManager.transactionFee } returns 1_000L

        coEvery { apiProvider.estimateEnergy(any(), any(), any(), any()) } throws originalError

        val thrownError = try {
            feeProvider.estimateFee(triggerSmartContract)
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(thrownError is IllegalStateException)
        assertSame(originalError, thrownError)
        coVerify(exactly = 0) { apiProvider.triggerConstantContract(any(), any(), any(), any()) }
    }

    @Test
    fun estimateFee_transferToExistingZeroBalanceAccount_hasNoActivationFee() = runTest {
        stubChainParameters()

        val fees = withAccountResponse(zeroBalanceAccount, "{}") { it.estimateFee(transfer) }

        assertFalse(fees.any { it is Fee.AccountActivation })
    }

    @Test
    fun estimateFee_transferToNeverActivatedAccount_hasActivationFee() = runTest {
        stubChainParameters()

        val fees = withAccountResponse("{}", "{}") { it.estimateFee(transfer) }

        assertEquals(1_000_000L, fees.filterIsInstance<Fee.AccountActivation>().single().amount)
    }

    @Test
    fun feesAccountActivation_chainParameters_matchesBurnOfTransferToNeverActivatedAccount() = runTest {
        stubChainParameters()
        every { chainParameterManager.createAccountFee } returns 100L

        val reserve = feeProvider.feesAccountActivation()
        val transferFees = withAccountResponse("{}", "{}") { it.estimateFee(transfer) }

        assertEquals(1_100_000L, reserve.sumOf { it.feeInSuns })
        assertEquals(reserve.sumOf { it.feeInSuns }, transferFees.sumOf { it.feeInSuns })
    }

    // --- Bandwidth ---

    @Test
    fun estimateFee_stakedBandwidthCoversExactly_paidFromStake() = runTest {
        val points = transferPoints()
        stubTransfer(recipientActive = true, resources(staked = points, free = points))

        val bandwidth = feeProvider.estimateFee(transfer).bandwidth()

        assertEquals(Fee.Bandwidth.Source.Staked, bandwidth.source)
        assertEquals(0L, bandwidth.feeInSuns)
    }

    @Test
    fun estimateFee_stakedOneShortFreeCovers_paidFromFreeQuota() = runTest {
        val points = transferPoints()
        stubTransfer(recipientActive = true, resources(staked = points - 1, free = points))

        val bandwidth = feeProvider.estimateFee(transfer).bandwidth()

        assertEquals(Fee.Bandwidth.Source.Free, bandwidth.source)
        assertEquals(0L, bandwidth.feeInSuns)
    }

    @Test
    fun estimateFee_bothQuotasOneShort_burnsWholeTransaction() = runTest {
        val points = transferPoints()
        stubTransfer(recipientActive = true, resources(staked = points - 1, free = points - 1))

        val bandwidth = feeProvider.estimateFee(transfer).bandwidth()

        assertEquals(Fee.Bandwidth.Source.Burn, bandwidth.source)
        assertEquals(points * 1_000L, bandwidth.feeInSuns)
    }

    @Test
    fun estimateFee_resourcesFetchFails_burnsEverything() = runTest {
        stubChainParameters()
        coEvery { apiProvider.fetchAccount(contractAddress.hex) } returns NodeAccountResponse(BigInteger.ZERO)
        coEvery { apiProvider.getAccountResource(ownerAddress.hex) } throws IllegalStateException("429")

        val bandwidth = feeProvider.estimateFee(transfer).bandwidth()

        assertEquals(Fee.Bandwidth.Source.Burn, bandwidth.source)
        assertEquals(bandwidth.points * 1_000L, bandwidth.feeInSuns)
        assertNull(feeProvider.accountResourcesFlow.value)
    }

    // --- Account activation ---

    @Test
    fun estimateFee_unactivatedRecipientStakeCovers_activationBandwidthFromStake() = runTest {
        val points = transferPoints()
        stubTransfer(recipientActive = false, resources(staked = points))

        val fees = feeProvider.estimateFee(transfer)

        assertEquals(Fee.Bandwidth.Source.Staked, fees.bandwidth().source)
        assertEquals(1_000_000L, fees.sumOf { it.feeInSuns })
    }

    @Test
    fun estimateFee_unactivatedRecipientOnlyFreeQuota_burnsFlatCreateAccountFee() = runTest {
        val points = transferPoints()
        stubTransfer(recipientActive = false, resources(staked = points - 1, free = 1_000_000))

        val fees = feeProvider.estimateFee(transfer)

        assertEquals(Fee.Bandwidth.Source.Burn, fees.bandwidth().source)
        assertEquals(100_000L, fees.bandwidth().feeInSuns)
        assertEquals(1_100_000L, fees.sumOf { it.feeInSuns })
    }

    @Test
    fun estimateFee_unactivatedRecipient_pointsScaleWithBandwidthRate() = runTest {
        val points = transferPoints()
        stubTransfer(recipientActive = false, resources(staked = 2 * points))
        every { chainParameterManager.createNewAccountBandwidthRate } returns 2L

        val bandwidth = feeProvider.estimateFee(transfer).bandwidth()

        assertEquals(2 * points, bandwidth.points)
        assertEquals(Fee.Bandwidth.Source.Staked, bandwidth.source)
    }

    // --- Energy ---

    @Test
    fun estimateFee_ownEnergyCoversAll_noEnergyFee() = runTest {
        stubTrigger(ownResources = resources(energy = 200_000), contract = userPaysAll)

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 100_000, fromContract = 0)
        assertEquals(0L, energy.feeInSuns)
    }

    @Test
    fun estimateFee_ownEnergyCoversPart_paysRest() = runTest {
        stubTrigger(ownResources = resources(energy = 40_000), contract = userPaysAll)

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 40_000, fromContract = 0)
        assertEquals(60_000L * ENERGY_PRICE, energy.feeInSuns)
    }

    @Test
    fun estimateFee_noOwnEnergyPercent100_paysAllWithoutDeployerLookup() = runTest {
        stubTrigger(ownResources = resources(), contract = userPaysAll)

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 0, fromContract = 0)
        assertEquals(REQUIRED_ENERGY * ENERGY_PRICE, energy.feeInSuns)
        coVerify(exactly = 0) { apiProvider.getAccountResource(deployerAddress.hex) }
    }

    @Test
    fun estimateFee_percent30DeployerHasEnough_deployerPays70Percent() = runTest {
        stubTrigger(
            ownResources = resources(energy = 10_000),
            contract = ContractInfo(30, 1_000_000, deployerAddress),
            deployerResources = resources(energy = 1_000_000)
        )

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 10_000, fromContract = 70_000)
        assertEquals(20_000L * ENERGY_PRICE, energy.feeInSuns)
    }

    @Test
    fun estimateFee_percent30DeployerShort_deployerPaysWhatItHas() = runTest {
        stubTrigger(
            ownResources = resources(),
            contract = ContractInfo(30, 1_000_000, deployerAddress),
            deployerResources = resources(energy = 50_000)
        )

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 0, fromContract = 50_000)
        assertEquals(50_000L * ENERGY_PRICE, energy.feeInSuns)
    }

    @Test
    fun estimateFee_originEnergyLimitBelowShare_capsDeployerShare() = runTest {
        stubTrigger(
            ownResources = resources(),
            contract = ContractInfo(30, 20_000, deployerAddress),
            deployerResources = resources(energy = 1_000_000)
        )

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 0, fromContract = 20_000)
    }

    @Test
    fun estimateFee_callerIsDeployer_paysFromOnePool() = runTest {
        stubTrigger(ownResources = resources(energy = 30_000), contract = ContractInfo(30, 1_000_000, ownerAddress))

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 30_000, fromContract = 0)
        coVerify(exactly = 1) { apiProvider.getAccountResource(ownerAddress.hex) }
    }

    @Test
    fun estimateFee_contractSettingsChange_rereadOnEachEstimate() = runTest {
        stubTrigger(ownResources = resources(), contract = userPaysAll, deployerResources = resources(energy = 1_000_000))
        coEvery { apiProvider.getContract(contractAddress.hex) } returnsMany listOf(
            userPaysAll,
            ContractInfo(0, 1_000_000, deployerAddress)
        )

        val first = feeProvider.estimateFee(triggerSmartContract).energy()
        val second = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(first, fromAccount = 0, fromContract = 0)
        assertEnergy(second, fromAccount = 0, fromContract = REQUIRED_ENERGY)
        coVerify(exactly = 2) { apiProvider.getContract(contractAddress.hex) }
    }

    @Test
    fun estimateFee_contractFetchFails_userPaysAll() = runTest {
        stubTrigger(ownResources = resources(), contract = userPaysAll)
        coEvery { apiProvider.getContract(contractAddress.hex) } throws IllegalStateException("timeout")

        val energy = feeProvider.estimateFee(triggerSmartContract).energy()

        assertEnergy(energy, fromAccount = 0, fromContract = 0)
        assertEquals(REQUIRED_ENERGY * ENERGY_PRICE, energy.feeInSuns)
    }

    @Test
    fun estimateFee_energyCoveredByAccount_bandwidthSizedWithFullFeeLimit() = runTest {
        stubTrigger(ownResources = resources(), contract = userPaysAll)
        val unpaidPoints = feeProvider.estimateFee(triggerSmartContract).bandwidth().points
        coEvery { apiProvider.getAccountResource(ownerAddress.hex) } returns resources(energy = REQUIRED_ENERGY)

        val fees = feeProvider.estimateFee(triggerSmartContract)

        assertEquals(0L, fees.energy().feeInSuns)
        assertEquals(unpaidPoints, fees.bandwidth().points)
    }

    // --- Account resources flow ---

    @Test
    fun estimateFee_ownerAndDeployerResourcesFetched_publishesOnlyOwners() = runTest {
        val own = resources(energy = 10_000)
        stubTrigger(
            ownResources = own,
            contract = ContractInfo(30, 1_000_000, deployerAddress),
            deployerResources = resources(energy = 1_000_000)
        )

        feeProvider.estimateFee(triggerSmartContract)

        assertEquals(own, feeProvider.accountResourcesFlow.value)
    }

    @Test
    fun refreshAccountResources_fetchSucceeds_publishesResources() = runTest {
        val own = resources(staked = 500, free = 600)
        coEvery { apiProvider.getAccountResource(ownerAddress.hex) } returns own

        feeProvider.refreshAccountResources(ownerAddress)

        assertEquals(own, feeProvider.accountResourcesFlow.value)
    }

    @Test
    fun accountStatus_neverActivatedAccount_returnsNotActive() = runTest {
        val status = withAccountResponse("{}") { it.accountStatus(ownerAddress) }

        assertEquals(AccountStatus.NotActive, status)
    }

    @Test
    fun accountStatus_zeroBalanceDefaultPermissions_returnsActive() = runTest {
        val status = withAccountResponse(zeroBalanceAccount) { it.accountStatus(contractAddress) }

        assertEquals(AccountStatus.Active, status)
    }

    @Test
    fun accountStatus_foreignOwner_returnsControlledByOthers() = runTest {
        val body = """
            {
              "address": "${ownerAddress.hex}",
              "owner_permission": {"threshold": 1, "keys": [{"address": "${contractAddress.hex}", "weight": 1}]}
            }
        """

        val status = withAccountResponse(body) { it.accountStatus(ownerAddress) }

        assertEquals(AccountStatus.ControlledByOthers, status)
    }

    @Test
    fun isAccountActive_zeroBalanceAccount_returnsTrue() = runTest {
        assertTrue(withAccountResponse(zeroBalanceAccount) { it.isAccountActive(ownerAddress) })
    }

    @Test
    fun isAccountActive_neverActivatedAccount_returnsFalse() = runTest {
        assertFalse(withAccountResponse("{}") { it.isAccountActive(ownerAddress) })
    }

    private fun stubChainParameters() {
        every { chainParameterManager.transactionFee } returns 1_000L
        every { chainParameterManager.energyFee } returns ENERGY_PRICE
        every { chainParameterManager.createAccountFeeInSuns } returns 100_000L
        every { chainParameterManager.createNewAccountBandwidthRate } returns 1L
        every { chainParameterManager.createNewAccountFeeInSystemContract } returns 1_000_000L
    }

    private fun stubTransfer(recipientActive: Boolean, ownResources: AccountResources) {
        stubChainParameters()
        coEvery { apiProvider.fetchAccount(contractAddress.hex) } returns
            NodeAccountResponse(BigInteger.ZERO).takeIf { recipientActive }
        coEvery { apiProvider.getAccountResource(ownerAddress.hex) } returns ownResources
    }

    private fun stubTrigger(
        ownResources: AccountResources,
        contract: ContractInfo,
        deployerResources: AccountResources = resources()
    ) {
        stubChainParameters()
        coEvery { apiProvider.estimateEnergy(any(), any(), any(), any()) } returns REQUIRED_ENERGY
        coEvery { apiProvider.getAccountResource(ownerAddress.hex) } returns ownResources
        coEvery { apiProvider.getAccountResource(deployerAddress.hex) } returns deployerResources
        coEvery { apiProvider.getContract(contractAddress.hex) } returns contract
    }

    private suspend fun transferPoints(): Long {
        stubTransfer(recipientActive = true, resources())
        return feeProvider.estimateFee(transfer).bandwidth().points
    }

    private fun resources(staked: Long = 0, free: Long = 0, energy: Long = 0) = AccountResources(
        freeBandwidth = AccountResources.Resource(limit = free + 100, used = 100),
        stakedBandwidth = AccountResources.Resource(limit = staked),
        energy = AccountResources.Resource(limit = energy)
    )

    private fun assertEnergy(energy: Fee.Energy, fromAccount: Long, fromContract: Long) {
        assertEquals(fromAccount, energy.fromAccount)
        assertEquals(fromContract, energy.fromContract)
        assertEquals(REQUIRED_ENERGY * ENERGY_PRICE, energy.feeLimitInSuns)
    }

    private fun List<Fee>.bandwidth() = filterIsInstance<Fee.Bandwidth>().single()
    private fun List<Fee>.energy() = filterIsInstance<Fee.Energy>().single()

    // Through the real provider: the zero-balance contract lives in its JSON handling.
    private suspend fun <T> withAccountResponse(vararg bodies: String, block: suspend (FeeProvider) -> T): T =
        MockWebServer().use { server ->
            bodies.forEach { body ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody(body)
                )
            }
            block(FeeProvider(TronGridProvider(server.url("/").toUrl(), emptyList()), chainParameterManager))
        }

    private companion object {
        const val REQUIRED_ENERGY = 100_000L
        const val ENERGY_PRICE = 100L
    }
}

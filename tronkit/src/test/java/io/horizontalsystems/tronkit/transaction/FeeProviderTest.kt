package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.tronkit.models.AccountStatus
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.TransferContract
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.INodeApiProvider
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
        every { chainParameterManager.transactionFee } returns 1_000L

        val fees = withAccountResponse(zeroBalanceAccount) { it.estimateFee(transfer) }

        assertFalse(fees.any { it is Fee.AccountActivation })
    }

    @Test
    fun estimateFee_transferToNeverActivatedAccount_hasActivationFee() = runTest {
        every { chainParameterManager.createAccountFee } returns 100L
        every { chainParameterManager.transactionFee } returns 1_000L
        every { chainParameterManager.createNewAccountFeeInSystemContract } returns 1_000_000L

        val fees = withAccountResponse("{}") { it.estimateFee(transfer) }

        assertEquals(1_000_000L, fees.filterIsInstance<Fee.AccountActivation>().single().amount)
    }

    @Test
    fun feesAccountActivation_chainParameters_matchesTransferToNeverActivatedAccount() = runTest {
        every { chainParameterManager.createAccountFee } returns 100L
        every { chainParameterManager.transactionFee } returns 1_000L
        every { chainParameterManager.createNewAccountFeeInSystemContract } returns 1_000_000L

        val reserve = feeProvider.feesAccountActivation()
        val transferFees = withAccountResponse("{}") { it.estimateFee(transfer) }

        assertEquals(1_100_000L, reserve.sumOf { it.feeInSuns })
        assertEquals(transferFees, reserve)
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

    // Through the real provider: the zero-balance contract lives in its JSON handling.
    private suspend fun <T> withAccountResponse(body: String, block: suspend (FeeProvider) -> T): T =
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(body)
            )
            block(FeeProvider(TronGridProvider(server.url("/").toUrl(), emptyList()), chainParameterManager))
        }
}

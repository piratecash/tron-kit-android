package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.TriggerSmartContract
import io.horizontalsystems.tronkit.network.INodeApiProvider
import io.horizontalsystems.tronkit.sync.ChainParameterManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}

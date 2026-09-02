package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.account.AddressHandler.AddressValidationException
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.network.Network
import io.horizontalsystems.tronkit.toRawHexString
import java.security.MessageDigest
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SignerTest {
    private lateinit var seed: ByteArray

    @Before
    fun setUp() {
        TronKit.init()
        seed = LegacySignerVector.seed()
    }

    @Test
    fun signer_mainnetAndNile_matchLegacyGoldenVectors() {
        listOf(Network.Mainnet, Network.NileTestnet).forEach { network ->
            assertSignerVector(
                network = network,
                expectedPrivateKey = LegacySignerVector.MAINNET_PRIVATE_KEY,
                expectedSignature = LegacySignerVector.MAINNET_SIGNATURE,
                expectedAddress = LegacySignerVector.MAINNET_ADDRESS,
            )
        }
    }

    @Test
    fun signer_shasta_matchesLegacyKeyAndSignature() {
        assertSignerVector(
            network = Network.ShastaTestnet,
            expectedPrivateKey = LegacySignerVector.SHASTA_PRIVATE_KEY,
            expectedSignature = LegacySignerVector.SHASTA_SIGNATURE,
        )
    }

    @Test
    fun getAddress_mainnetSeed_matchesSignerAddress() {
        val privateKey = Signer.privateKey(seed, Network.Mainnet)

        assertEquals(Signer.address(privateKey, Network.Mainnet), TronKit.getAddress(seed, Network.Mainnet))
    }

    @Test
    fun fromBase58_mainnetAddress_roundTrips() {
        val address = Address.fromBase58(LegacySignerVector.MAINNET_ADDRESS)

        assertEquals(LegacySignerVector.MAINNET_ADDRESS, address.base58)
        assertEquals(LegacySignerVector.MAINNET_ADDRESS_HEX, address.hex)
    }

    @Test
    fun fromBase58_corruptedChecksum_throwsInvalidChecksum() {
        val corruptedAddress = LegacySignerVector.MAINNET_ADDRESS.dropLast(1) + "1"

        assertThrows(AddressValidationException.InvalidChecksum::class.java) {
            Address.fromBase58(corruptedAddress)
        }
    }

    @Test
    fun init_calledTwice_keepsKeccakProvider() {
        TronKit.init()
        TronKit.init()

        val provider = requireNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME))
        assertNotNull(provider.getService("MessageDigest", "ETH-KECCAK-256"))
        assertNotNull(MessageDigest.getInstance("ETH-KECCAK-256"))
    }

    private fun assertSignerVector(
        network: Network,
        expectedPrivateKey: String,
        expectedSignature: String,
        expectedAddress: String? = null,
    ) {
        val privateKey = Signer.privateKey(seed, network)
        val signer = Signer.getInstance(seed, network)

        assertEquals(expectedPrivateKey, privateKey.toString(16).padStart(64, '0'))
        assertEquals(expectedSignature, signer.sign(LegacySignerVector.createdTransaction()).toRawHexString())
        expectedAddress?.let {
            assertEquals(it, Signer.address(privateKey, network).base58)
        }
    }

}

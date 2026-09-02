package io.horizontalsystems.tronkit.transaction

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.network.Network
import io.horizontalsystems.tronkit.toRawHexString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignerInstrumentedTest {
    @Test
    fun signer_mainnet_matchesLegacyGoldenVectorOnAndroid() {
        TronKit.init()
        val seed = LegacySignerVector.seed()
        val privateKey = Signer.privateKey(seed, Network.Mainnet)

        assertEquals(LegacySignerVector.MAINNET_PRIVATE_KEY, privateKey.toString(16).padStart(64, '0'))
        assertEquals(LegacySignerVector.MAINNET_ADDRESS, Signer.address(privateKey, Network.Mainnet).base58)
        val signature = Signer.getInstance(seed, Network.Mainnet)
            .sign(LegacySignerVector.createdTransaction())
            .toRawHexString()
        assertEquals(LegacySignerVector.MAINNET_SIGNATURE, signature)
    }
}

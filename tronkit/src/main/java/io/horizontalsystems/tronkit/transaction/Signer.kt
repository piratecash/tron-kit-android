package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.ECKey
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.tronkit.crypto.Utils
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.Network
import io.horizontalsystems.tronkit.toBigInteger
import io.horizontalsystems.tronkit.toRawHexString
import java.math.BigInteger
import org.bouncycastle.util.BigIntegers

open class Signer(
    private val privateKey: BigInteger
) {

    open fun sign(createdTransaction: CreatedTransaction): ByteArray {
        val rawTransactionHash = io.horizontalsystems.hdwalletkit.Utils.sha256(createdTransaction.raw_data_hex.hexStringToByteArray())

        return Utils.ellipticSign(rawTransactionHash, privateKey)
    }

    companion object {
        fun getInstance(seed: ByteArray, network: Network): Signer {
            return Signer(privateKey(seed, network))
        }

        fun privateKey(seed: ByteArray, network: Network): BigInteger {
            val hdWallet = HDWallet(seed, network.coinType, HDWallet.Purpose.BIP44)
            return hdWallet.privateKey(0, 0, true).privKeyBytes.toBigInteger()
        }

        fun address(privateKey: BigInteger, network: Network): Address {
            val privateKeyBytes = BigIntegers.asUnsignedByteArray(32, privateKey)
            val publicKey = ECKey.fromPrivate(privateKeyBytes, compressed = false).pubKey.drop(1).toByteArray()
            val raw = byteArrayOf(network.addressPrefixByte) + Utils.sha3(publicKey).takeLast(20).toByteArray()
            return Address(raw)
        }
    }
}

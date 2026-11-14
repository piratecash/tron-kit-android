package io.horizontalsystems.tronkit.transaction

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.tronkit.network.CreatedTransaction
import io.horizontalsystems.tronkit.network.RawData

internal object LegacySignerVector {
    const val RAW_DATA_HEX = "0a020001"
    const val MAINNET_PRIVATE_KEY = "15f0bbb1774be40b7a8d7965d637f324bda2f711fc5726a3dcc19585c6950954"
    const val MAINNET_ADDRESS = "TWer2Ygk5TEheHp3TPuYeqxmB6SsGZmaL6"
    const val MAINNET_ADDRESS_HEX = "41e2e1a54926527fbb4e4420de4c6bab82beaee24d"
    const val MAINNET_SIGNATURE = "2b6516503b4c25105ce870ca6b4d03be2c820c0baead68ddf5fc40a5e803de207aee138672eefdef671a19a61da5082e097ab71c7c2fb3dc76bc923ef511c0f800"
    const val SHASTA_PRIVATE_KEY = "7c299dda7c704f9d474b6ca5d7fee0b490c8decca493b5764541fe5ec6b65114"
    const val SHASTA_SIGNATURE = "a78c0cf44f27794fd8f7e8a827a26ef0a75b0db1c1289643148542c9f97095c862d600ce86bfe68a50b29c323f24b12d6d6db6a0f7ecbde892d3d6c3917c376d01"

    fun seed(): ByteArray = Mnemonic().toSeed(List(11) { "test" } + "junk")

    fun createdTransaction() = CreatedTransaction(
        visible = false,
        txID = "",
        raw_data = RawData(
            contract = emptyList(),
            ref_block_bytes = "",
            ref_block_hash = "",
            expiration = 0,
            timestamp = 0,
            fee_limit = null,
        ),
        raw_data_hex = RAW_DATA_HEX,
        Error = null,
    )
}

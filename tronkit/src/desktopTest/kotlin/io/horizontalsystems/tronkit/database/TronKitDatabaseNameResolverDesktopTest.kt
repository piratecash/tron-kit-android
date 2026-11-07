package io.horizontalsystems.tronkit.database

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.tronkit.PlatformContext
import io.horizontalsystems.tronkit.TronKit
import io.horizontalsystems.tronkit.network.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Android keeps the default names forever: a change here makes every existing wallet resync from scratch.
class TronKitDatabaseNameResolverDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val seed = ByteArray(64) { it.toByte() }
    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val opaque = DatabaseNameResolver { "n${it.hashCode().toUInt()}" }

    @Test
    fun getInstance_defaultResolver_opensLiteralName() {
        kit(WALLET, Network.Mainnet)

        assertTrue(File(directory, "Tron-Mainnet-w1").isFile)
    }

    @Test
    fun getInstance_differentNetworksAndWallets_namesArePairwiseDistinct() {
        listOf(WALLET to Network.Mainnet, WALLET to Network.NileTestnet, "w2" to Network.Mainnet).forEach { (wallet, network) ->
            kit(wallet, network)
            kit(wallet, network, opaque)
        }

        val defaults = listOf("Tron-Mainnet-w1", "Tron-NileTestnet-w1", "Tron-Mainnet-w2")
        assertEquals((defaults + defaults.map(opaque::resolve)).toSet(), databaseFiles())
    }

    @Test
    fun getInstance_overrideResolver_leavesNoDefaultNameOnDisk() {
        kit(WALLET, Network.Mainnet, opaque)

        assertEquals(setOf(opaque.resolve("Tron-Mainnet-w1")), databaseFiles())
    }

    @Test
    fun migrateDatabase_overrideResolver_namespaceFilesUseResolvedName() = runBlocking<Unit> {
        TronKit.migrateDatabase(context, Network.Mainnet, WALLET, key, opaque)

        assertEquals(listOf(".${opaque.resolve("tron-kit")}-sqlcipher.lock"), directory.list().orEmpty().toList())
    }

    @Test
    fun clear_defaultResolver_removesLiteralName() {
        val file = File(directory, "Tron-Mainnet-w1").apply { writeText("x") }

        TronKit.clear(context, Network.Mainnet, WALLET)

        assertFalse(file.exists())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNameAndKeepsDefaultOne() {
        File(directory, opaque.resolve("Tron-Mainnet-w1")).writeText("x")
        val foreign = File(directory, "Tron-Mainnet-w1").apply { writeText("x") }

        TronKit.clear(context, Network.Mainnet, WALLET, opaque)

        assertEquals(setOf(foreign.name), databaseFiles())
    }

    @Test
    fun migrateDatabase_resolvedNameInResolvedNamespacePrefix_throws() {
        val reserved = DatabaseNameResolver { if (it == "tron-kit") "ns" else ".ns-sqlcipher-x" }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { TronKit.migrateDatabase(context, Network.Mainnet, WALLET, key, reserved) }
        }
    }

    private fun kit(
        walletId: String,
        network: Network,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): TronKit = TronKit.getInstance(context, seed, network, listOf("key"), walletId, key, nameResolver = nameResolver)

    private fun databaseFiles(): Set<String> = directory.list().orEmpty()
        .filterNot { it.startsWith(".") || it.endsWith(".lck") || it.endsWith("-wal") || it.endsWith("-shm") }.toSet()

    private companion object {
        const val WALLET = "w1"
    }
}

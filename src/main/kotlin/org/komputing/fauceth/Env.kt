package org.komputing.fauceth

import com.github.michaelbull.retry.retry
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import org.ethereum.lists.chains.model.Chain
import org.kethereum.crypto.toAddress
import org.kethereum.ens.ENS
import org.kethereum.rpc.*
import org.kethereum.rpc.min3.getMin3RPC
import org.komputing.fauceth.FaucethLogLevel.*
import org.komputing.fauceth.util.AtomicNonce
import org.komputing.fauceth.util.log
import org.komputing.kaptcha.HCaptcha
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import kotlin.system.exitProcess


const val ADDRESS_KEY = "address"
const val CHAIN_KEY = "chain"

val keystoreFile = File("fauceth_keystore.json")
val ens = ENS(getMin3RPC())
val config = FaucethConfig()
val captchaVerifier = HCaptcha(config.hcaptchaSecret)

val okHttpClient = OkHttpClient.Builder().build()

private val chainsDefinitionFile = File("chains.json").also {
    if (!it.exists()) {
        it.createNewFile()

        val request = Request.Builder().url("https://chainid.network/chains_pretty.json").build();
        val response = okHttpClient.newCall(request).execute();
        if (!response.isSuccessful) {
            fail("could not download chains.json")
        }
        FileOutputStream(it).use { fos ->
            val body = response.body
            if (body == null) {
                fail("could not download chains.json")
            } else {
                fos.write(body.bytes())
            }
        }
    }
}

private val moshi = Moshi.Builder().build()
private var listMyData = Types.newParameterizedType(MutableList::class.java, Chain::class.java)
var chainsAdapter: JsonAdapter<List<Chain>> = moshi.adapter(listMyData)

val unfilteredChains = chainsAdapter.fromJson(chainsDefinitionFile.source().buffer()) ?: fail("Could not read chains.json")

class ChainWithRPCAndNonce(
    val staticChainInfo: Chain,
    val nonce: AtomicNonce,
    val rpc: EthereumRPC
)

val chains = unfilteredChains.filter { config.chains.contains(BigInteger.valueOf(it.chainId)) }.map {
    val rpc = if (config.logging == VERBOSE) {
        BaseEthereumRPC(ConsoleLoggingTransportWrapper(HttpTransport(it.rpc.first())))
    } else {
        HttpEthereumRPC(it.rpc.first())
    }

    var initialNonce: BigInteger? = null

    while(initialNonce == null) {
        log(INFO, "Fetching initial nonce for chain ${it.name}")
        initialNonce= rpc.getTransactionCount(config.keyPair.toAddress())
    }

    log(INFO, "Got initial nonce for chain ${it.name}: $initialNonce for address ${config.keyPair.toAddress()}")

    val atomicNonce = AtomicNonce(initialNonce!!)

    ChainWithRPCAndNonce(it, atomicNonce, rpc)
}

internal fun fail(msg: String): Nothing {
    println(msg)
    exitProcess(1)
}


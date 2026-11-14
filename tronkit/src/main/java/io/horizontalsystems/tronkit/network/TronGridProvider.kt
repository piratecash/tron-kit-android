package io.horizontalsystems.tronkit.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.horizontalsystems.tronkit.TronKit.TransactionError
import io.horizontalsystems.tronkit.hexStringToByteArray
import io.horizontalsystems.tronkit.models.AccountInfo
import io.horizontalsystems.tronkit.models.AccountResources
import io.horizontalsystems.tronkit.models.Address
import io.horizontalsystems.tronkit.models.ContractInfo
import io.horizontalsystems.tronkit.models.TransactionInfo
import io.horizontalsystems.tronkit.models.Trc20Balance
import io.horizontalsystems.tronkit.rpc.BigIntegerTypeAdapter
import io.horizontalsystems.tronkit.rpc.ByteArrayTypeAdapter
import io.horizontalsystems.tronkit.rpc.IntTypeAdapter
import io.horizontalsystems.tronkit.rpc.JsonRpc
import io.horizontalsystems.tronkit.rpc.LongTypeAdapter
import io.horizontalsystems.tronkit.rpc.RpcResponse
import io.reactivex.Single
import kotlinx.coroutines.rx2.await
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.math.BigInteger
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class TronGridProvider(
    baseUrl: URL,
    apiKeys: List<String>,
    private val auth: String? = null,
    eventListenerFactory: EventListener.Factory? = null
) : IRpcApiProvider, INodeApiProvider, IHistoryProvider {

    private var currentRpcId = AtomicInteger(0)
    private val logger = Logger.getLogger("TronGridProvider")
    private val extensionApi: TronGridExtensionAPI
    private val rpcApi: TronGridRpcAPI
    private val gsonRpc: Gson
    private val gson: Gson

    init {
        val loggingInterceptor = HttpLoggingInterceptor { message -> logger.info(message) }
            .setLevel(HttpLoggingInterceptor.Level.BASIC)

        val apiKeyInterceptor: Interceptor = if (apiKeys.isEmpty()) {
            Interceptor { chain -> chain.proceed(chain.request()) }
        } else {
            RateLimitInterceptor(ApiKeyProvider(apiKeys))
        }

        val httpClient = OkHttpClient.Builder()
            .addInterceptor(apiKeyInterceptor)
            .addInterceptor(loggingInterceptor)
            .apply {
                auth?.let { credentials ->
                    val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
                    addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Authorization", "Basic $encoded")
                            .build()
                        chain.proceed(request)
                    }
                }
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .apply { eventListenerFactory?.let { eventListenerFactory(it) } }

        val url = baseUrl.toString()
        gsonRpc = gson(isHex = true)
        rpcApi = retrofit(httpClient, url, gsonRpc).create(TronGridRpcAPI::class.java)

        gson = gson(isHex = false)
        extensionApi = retrofit(httpClient, url, gson).create(TronGridExtensionAPI::class.java)
    }

    // IRpcApiProvider

    override suspend fun <T> fetch(rpc: JsonRpc<T>): T {
        rpc.id = currentRpcId.incrementAndGet()
        val response = rpcApi.rpc(gsonRpc.toJson(rpc)).await()
        return rpc.parseResponse(response, gsonRpc)
    }

    // INodeApiProvider

    override suspend fun fetchAccount(address: String): NodeAccountResponse? {
        val response = extensionApi.getAccount(GetAccountRequest(address)).await()
        val balance = response["balance"]?.takeIf { !it.isJsonNull }?.asBigInteger
        val hasAddress = response["address"]?.isJsonNull == false
        if (balance == null && !hasAddress) return null
        return accountWithPermissions(balance ?: BigInteger.ZERO, response)
    }

    // Sync and fee estimation depend on fetchAccount, so bad permissions must not fail them.
    private fun accountWithPermissions(balance: BigInteger, response: JsonObject) = try {
        NodeAccountResponse(
            balance = balance,
            ownerPermission = response.getAsJsonObject("owner_permission")?.toNodePermission(),
            activePermissions = response.getAsJsonArray("active_permission")
                ?.map { it.asJsonObject.toNodePermission() }
                .orEmpty(),
        )
    } catch (_: RuntimeException) {
        NodeAccountResponse(balance)
    }

    // Read from the tree, not by Gson reflection, so R8 cannot break the field names.
    // Protobuf omits zero values from the response, hence the defaults.
    private fun JsonObject.toNodePermission() = NodePermission(
        threshold = get("threshold")?.asLong ?: 0,
        keys = getAsJsonArray("keys")
            ?.map { key ->
                val keyObject = key.asJsonObject
                NodePermissionKey(keyObject.get("address").asString, keyObject.get("weight")?.asLong ?: 0)
            }
            .orEmpty(),
        operations = get("operations")?.asString,
    )

    override suspend fun fetchChainParameters(): List<ChainParameterResponse> {
        val response = extensionApi.getChainParameters().await()
        return response.chainParameter.map { ChainParameterResponse(it.key, it.value) }
    }

    override suspend fun getNowBlock(): NowBlock {
        val response = extensionApi.getNowBlock().await()
        return NowBlock(
            number = response.block_header.raw_data.number,
            blockId = response.blockID,
            timestamp = response.block_header.raw_data.timestamp,
        )
    }

    override suspend fun createTransaction(
        ownerAddress: String,
        toAddress: String,
        amount: BigInteger
    ): CreatedTransaction {
        val response = extensionApi.createTransaction(
            CreateTransactionRequest(
                owner_address = ownerAddress,
                to_address = toAddress,
                amount = amount
            )
        ).await()

        check(response.Error == null) {
            "createTransaction error: ${response.Error?.let { hexStringToUtf8String(it) }}"
        }

        return response
    }

    override suspend fun triggerSmartContract(
        ownerAddress: String,
        contractAddress: String,
        functionSelector: String,
        parameter: String,
        callValue: Long,
        feeLimit: Long
    ): CreatedTransaction {
        val response = extensionApi.triggerSmartContract(
            TriggerSmartContractRequest(
                owner_address = ownerAddress,
                contract_address = contractAddress,
                function_selector = functionSelector,
                parameter = parameter,
                fee_limit = feeLimit,
                call_value = callValue
            )
        ).await()

        check(response.result.result) {
            "triggerSmartContract error: ${response.result.code} - ${hexStringToUtf8String(response.result.message)}"
        }

        return response.transaction
    }

    override suspend fun broadcastTransaction(
        createdTransaction: CreatedTransaction,
        signature: ByteArray
    ) {
        broadcastTransaction(createdTransaction.signedTransaction(signature))
    }

    override suspend fun broadcastTransaction(signedTransaction: SignedTransaction): String {
        val response = extensionApi.broadcastTransaction(signedTransaction).await()

        if (!response.result) {
            throw TransactionError.BroadcastFailed(
                code = response.code.orEmpty(),
                message = hexStringToUtf8String(response.message),
                txId = response.txid
            )
        }

        return response.txid?.takeIf { it.isNotBlank() } ?: signedTransaction.txID
    }

    override suspend fun transactionExists(txId: String): Boolean {
        return try {
            val response = extensionApi.getTransactionById(GetTransactionByIdRequest(txId)).await()
            response.get("txID")?.asString?.equals(txId, ignoreCase = true) == true
        } catch (error: HttpException) {
            if (error.code() == 404) false else throw error
        }
    }

    override suspend fun estimateEnergy(
        ownerAddress: String,
        contractAddress: String,
        functionSelector: String,
        parameter: String
    ): Long {
        val response = extensionApi.estimateEnergy(
            EstimateEnergyRequest(
                owner_address = ownerAddress,
                contract_address = contractAddress,
                function_selector = functionSelector,
                parameter = parameter
            )
        ).await()

        check(response.result.result) {
            "estimateEnergy error: ${response.result.code} - ${hexStringToUtf8String(response.result.message)}"
        }

        return response.energy_required
    }

    override suspend fun triggerConstantContract(
        ownerAddress: String,
        contractAddress: String,
        functionSelector: String,
        parameter: String
    ): Long {
        val response = extensionApi.triggerConstantContract(
            TriggerConstantContractRequest(
                owner_address = ownerAddress,
                contract_address = contractAddress,
                function_selector = functionSelector,
                parameter = parameter
            )
        ).await()

        check(response.result.result) {
            "triggerConstantContract error: ${response.result.code} - ${
                hexStringToUtf8String(
                    response.result.message
                )
            }"
        }

        return response.energy_used
    }

    override suspend fun getAccountResource(address: String): AccountResources {
        val response = extensionApi.getAccountResource(GetAccountRequest(address)).await()
        return AccountResources(
            freeBandwidth = AccountResources.Resource(response.longOrZero("freeNetLimit"), response.longOrZero("freeNetUsed")),
            stakedBandwidth = AccountResources.Resource(response.longOrZero("NetLimit"), response.longOrZero("NetUsed")),
            energy = AccountResources.Resource(response.longOrZero("EnergyLimit"), response.longOrZero("EnergyUsed")),
        )
    }

    override suspend fun getContract(contractAddress: String): ContractInfo {
        val response = extensionApi.getContract(GetTransactionByIdRequest(contractAddress)).await()
        return ContractInfo(
            consumeUserResourcePercent = response.longOrZero("consume_user_resource_percent"),
            // java-tron ContractCapsule.getOriginEnergyLimit: an unset (0) limit means the creator default.
            originEnergyLimit = response.longOrZero("origin_energy_limit").takeIf { it > 0 } ?: CREATOR_DEFAULT_ENERGY_LIMIT,
            originAddress = response.get("origin_address")?.takeIf { !it.isJsonNull }?.asString?.let(Address::fromHex),
        )
    }

    override suspend fun getTransactionInfoById(txId: String): TransactionInfo? {
        val response = extensionApi.getTransactionInfoById(GetTransactionByIdRequest(txId)).await()
        if (!response.has("blockNumber")) return null
        val receipt = response.getAsJsonObject("receipt") ?: JsonObject()
        return TransactionInfo(
            fee = response.longOrZero("fee"),
            blockNumber = response.longOrZero("blockNumber"),
            netUsage = receipt.longOrZero("net_usage"),
            netFee = receipt.longOrZero("net_fee"),
            energyUsage = receipt.longOrZero("energy_usage"),
            energyFee = receipt.longOrZero("energy_fee"),
            originEnergyUsage = receipt.longOrZero("origin_energy_usage"),
            energyUsageTotal = receipt.longOrZero("energy_usage_total"),
        )
    }

    // IHistoryProvider

    override suspend fun fetchAccountInfo(address: String): AccountInfo {
        val response = extensionApi.accountInfo(address).await()
        val data = response.data.firstOrNull()
            ?: throw IHistoryProvider.RequestError.FailedToFetchAccountInfo

        val trc20Balances = data.trc20.flatMap { balanceMap ->
            balanceMap.map { (contractAddress, balance) ->
                Trc20Balance(contractAddress, BigInteger(balance))
            }
        }

        return AccountInfo(data.balance ?: BigInteger.ZERO, trc20Balances)
    }

    override suspend fun fetchTransactions(
        address: String,
        minTimestamp: Long,
        cursor: String?
    ): Pair<List<TransactionData>, String?> {
        val response = extensionApi.transactions(
            address = address,
            startBlockTimestamp = minTimestamp,
            fingerprint = cursor,
            onlyConfirmed = true,
            limit = PAGE_LIMIT,
            orderBy = ORDER_BY
        ).await()

        check(response.success) { "fetchTransactions failed" }

        val transactions = response.data.map {
            if (it.has("internal_tx_id")) {
                gson.fromJson(it, InternalTransactionData::class.java)
            } else {
                gson.fromJson(it, RegularTransactionData::class.java)
            }
        }

        return Pair(transactions, response.meta.fingerprint)
    }

    override suspend fun fetchTrc20Transactions(
        address: String,
        minTimestamp: Long,
        cursor: String?
    ): Pair<List<ContractTransactionData>, String?> {
        val response = extensionApi.contractTransactions(
            address = address,
            startBlockTimestamp = minTimestamp,
            fingerprint = cursor,
            onlyConfirmed = true,
            limit = PAGE_LIMIT,
            orderBy = ORDER_BY
        ).await()

        check(response.success) { "fetchTrc20Transactions failed" }

        return Pair(response.data, response.meta.fingerprint)
    }

    // Helpers

    // Protobuf omits zero values from the response.
    private fun JsonObject.longOrZero(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0

    private fun hexStringToUtf8String(hexString: String?) = try {
        hexString?.let { String(it.hexStringToByteArray()) }
    } catch (_: Throwable) {
        hexString
    }

    private fun gson(isHex: Boolean): Gson = GsonBuilder()
        .setLenient()
        .registerTypeAdapter(BigInteger::class.java, BigIntegerTypeAdapter(isHex))
        .registerTypeAdapter(Long::class.java, LongTypeAdapter(isHex))
        .registerTypeAdapter(object : TypeToken<Long?>() {}.type, LongTypeAdapter(isHex))
        .registerTypeAdapter(Int::class.java, IntTypeAdapter(isHex))
        .registerTypeAdapter(ByteArray::class.java, ByteArrayTypeAdapter())
        .create()

    private fun retrofit(httpClient: OkHttpClient.Builder, baseUrl: String, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient.build())
            .build()

    // Retrofit API interfaces

    private interface TronGridRpcAPI {
        @POST("jsonrpc")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun rpc(@Body jsonRpc: String): Single<RpcResponse>
    }

    private interface TronGridExtensionAPI {

        @POST("wallet/getaccount")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun getAccount(@Body request: GetAccountRequest): Single<JsonObject>

        @GET("v1/accounts/{address}")
        fun accountInfo(@Path("address") address: String): Single<AccountInfoResponse>

        @GET("v1/accounts/{address}/transactions")
        fun transactions(
            @Path("address") address: String,
            @Query("min_timestamp") startBlockTimestamp: Long,
            @Query("fingerprint") fingerprint: String?,
            @Query("only_confirmed") onlyConfirmed: Boolean,
            @Query("limit") limit: Int,
            @Query("order_by") orderBy: String
        ): Single<TransactionsResponse>

        @GET("v1/accounts/{address}/transactions/trc20")
        fun contractTransactions(
            @Path("address") address: String,
            @Query("min_timestamp") startBlockTimestamp: Long,
            @Query("fingerprint") fingerprint: String?,
            @Query("only_confirmed") onlyConfirmed: Boolean,
            @Query("limit") limit: Int,
            @Query("order_by") orderBy: String
        ): Single<ContractTransactionsResponse>

        @POST("wallet/createtransaction")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun createTransaction(@Body request: CreateTransactionRequest): Single<CreatedTransaction>

        @POST("wallet/triggersmartcontract")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun triggerSmartContract(@Body request: TriggerSmartContractRequest): Single<TriggerSmartContractResponse>

        @POST("wallet/estimateenergy")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun estimateEnergy(@Body request: EstimateEnergyRequest): Single<EstimateEnergyResponse>

        @POST("wallet/triggerconstantcontract")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun triggerConstantContract(@Body request: TriggerConstantContractRequest): Single<TriggerConstantContractResponse>

        @POST("wallet/broadcasttransaction")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun broadcastTransaction(@Body signedTransaction: SignedTransaction): Single<BroadcastTransactionResponse>

        @POST("wallet/gettransactionbyid")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun getTransactionById(@Body request: GetTransactionByIdRequest): Single<JsonObject>

        @POST("wallet/gettransactioninfobyid")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun getTransactionInfoById(@Body request: GetTransactionByIdRequest): Single<JsonObject>

        @POST("wallet/getaccountresource")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun getAccountResource(@Body request: GetAccountRequest): Single<JsonObject>

        @POST("wallet/getcontract")
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun getContract(@Body request: GetTransactionByIdRequest): Single<JsonObject>

        @GET("wallet/getchainparameters")
        fun getChainParameters(): Single<ChainParametersResponse>

        @GET("wallet/getnowblock")
        fun getNowBlock(): Single<GetNowBlockResponse>
    }

    companion object {
        private const val PAGE_LIMIT = 200
        private const val ORDER_BY = "block_timestamp,asc"
        private const val CREATOR_DEFAULT_ENERGY_LIMIT = 10_000_000L
    }
}

data class GetAccountRequest(
    val address: String,
    val visible: Boolean = false
)

data class GetNowBlockResponse(
    val blockID: String,
    val block_header: BlockHeader,
) {
    data class BlockHeader(val raw_data: BlockRawData)
    data class BlockRawData(val number: Long = 0, val timestamp: Long = 0)
}

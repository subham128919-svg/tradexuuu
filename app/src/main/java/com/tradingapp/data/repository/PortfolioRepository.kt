package com.tradingapp.data.repository

import com.tradingapp.data.api.ApiService;
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tradingapp.data.api.HoldingsResponse
import com.tradingapp.data.api.PositionsResponse
import com.tradingapp.data.model.*; import com.tradingapp.util.Resource
import kotlinx.coroutines.flow.Flow; import kotlinx.coroutines.flow.flow; import javax.inject.Inject; import javax.inject.Singleton

@Singleton
class PortfolioRepository @Inject constructor(private val api: ApiService, @ApplicationContext private val context: Context) {
    private data class Cached(val value: Any, val at: Long)
    private val cache = mutableMapOf<String, Cached>()
    private val locks = mutableMapOf<String, Mutex>()
    private var account: String? = null
    private fun token() = context.getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE).getString("jwt_token", null)
    fun sessionKey(): String? = token()

    private fun <T : Any> cachedRead(key: String, fetch: suspend () -> T): Flow<Resource<T>> = flow {
        val session = token()
        val pair = synchronized(cache) {
            if (account != session) { cache.clear(); account = session }
            cache[key] to locks.getOrPut(key) { Mutex() }
        }
        @Suppress("UNCHECKED_CAST")
        if (pair.first != null) emit(Resource.Success(pair.first!!.value as T)) else emit(Resource.Loading)
        try {
            val result = pair.second.withLock {
                val recent = synchronized(cache) { cache[key]?.takeIf { account == session && System.currentTimeMillis() - it.at < 1500 } }
                @Suppress("UNCHECKED_CAST")
                if (recent != null) recent.value as T else fetch().also { value ->
                    synchronized(cache) { if (token() == session && account == session) cache[key] = Cached(value, System.currentTimeMillis()) }
                }
            }
            if (token() == session) emit(Resource.Success(result))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { emit(Resource.Error(e.message ?: "Refresh failed; showing saved data")) }
    }
    fun getHoldings() = cachedRead("holdings") {
        val r = api.getHoldings(); r.body()?.takeIf { r.isSuccessful } ?: error("Holdings refresh failed (${r.code()})")
    }
    fun getPositions() = cachedRead("positions") {
        val r = api.getPositions(); r.body()?.takeIf { r.isSuccessful } ?: error("Positions refresh failed (${r.code()})")
    }
    fun getOrders(status: String? = null) = cachedRead("orders:${status.orEmpty()}") {
        val r = api.getOrders(status); r.body()?.data?.takeIf { r.isSuccessful } ?: error("Orders refresh failed (${r.code()})")
    }
}

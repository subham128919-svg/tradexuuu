package com.tradingapp.data.repository

import com.tradingapp.data.api.ApiService; import com.tradingapp.data.db.QuoteDao
import com.tradingapp.data.model.*; import com.tradingapp.data.websocket.PriceWebSocket
import com.tradingapp.util.Resource; import kotlinx.coroutines.flow.Flow; import kotlinx.coroutines.flow.flow
import javax.inject.Inject; import javax.inject.Singleton

@Singleton
class MarketRepository @Inject constructor(
    private val api:      ApiService,
    private val quoteDao: QuoteDao,
    private val priceWs:  PriceWebSocket
) {
    fun getQuotes(symbols: List<String>): Flow<Resource<List<Quote>>> = flow {
        emit(Resource.Loading)
        try {
            val r = api.getQuotes(symbols.joinToString(","))
            if (r.isSuccessful) {
                val data = r.body()!!.data
                quoteDao.upsert(data)
                emit(Resource.Success(data))
            } else emit(Resource.Error("API error " + r.code()))
        } catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }

    // period: "1D" | "1W" | "1M" | "3M" | "1Y" | "5Y"  — backend maps to Groww resolution
    fun getCandles(symbol: String, period: String): Flow<Resource<List<Candle>>> = flow {
        emit(Resource.Loading)
        try {
            val r = api.getCandles(symbol, period)
            if (r.isSuccessful) emit(Resource.Success(r.body()!!.data))
            else emit(Resource.Error("API error " + r.code()))
        } catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }

    fun getIndices(): Flow<Resource<List<Quote>>> = flow {
        emit(Resource.Loading)
        try {
            val r = api.getIndices()
            if (r.isSuccessful) emit(Resource.Success(r.body()!!.data))
            else emit(Resource.Error("API error " + r.code()))
        } catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }

    fun getExplore(segment: String, type: String): Flow<Resource<List<Quote>>> = flow {
        emit(Resource.Loading)
        try {
            val r = api.getExplore(segment, type)
            if (r.isSuccessful) emit(Resource.Success(r.body()!!.data))
            else emit(Resource.Error("API error " + r.code()))
        } catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }

    fun subscribeToLivePrices(symbols: List<String>) { priceWs.connect(); priceWs.subscribe(symbols) }
    fun unsubscribe(symbols: List<String>)           { priceWs.unsubscribe(symbols) }
    val liveTicks = priceWs.ticks
}

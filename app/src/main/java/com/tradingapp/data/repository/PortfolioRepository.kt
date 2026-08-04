package com.tradingapp.data.repository

import com.tradingapp.data.api.ApiService; import com.tradingapp.data.model.*; import com.tradingapp.util.Resource
import kotlinx.coroutines.flow.Flow; import kotlinx.coroutines.flow.flow; import javax.inject.Inject; import javax.inject.Singleton

@Singleton
class PortfolioRepository @Inject constructor(private val api: ApiService) {
    fun getHoldings(): Flow<Resource<HoldingsResponse>> = flow {
        emit(Resource.Loading)
        try { val r = api.getHoldings(); if (r.isSuccessful) emit(Resource.Success(r.body()!!)) else emit(Resource.Error("Error " + r.code())) }
        catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }
    fun getPositions(): Flow<Resource<PositionsResponse>> = flow {
        emit(Resource.Loading)
        try { val r = api.getPositions(); if (r.isSuccessful) emit(Resource.Success(r.body()!!)) else emit(Resource.Error("Error " + r.code())) }
        catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }
    fun getOrders(status: String? = null): Flow<Resource<List<Order>>> = flow {
        emit(Resource.Loading)
        try { val r = api.getOrders(status); if (r.isSuccessful) emit(Resource.Success(r.body()!!.data)) else emit(Resource.Error("Error " + r.code())) }
        catch (e: Exception) { emit(Resource.Error(e.message ?: "Network error")) }
    }
    suspend fun placeOrder(params: Map<String, Any>): Resource<String> = try {
        val r = api.placeOrder(params); if (r.isSuccessful) Resource.Success(r.body()!!.orderId) else Resource.Error("Error " + r.code())
    } catch (e: Exception) { Resource.Error(e.message ?: "Network error") }
}

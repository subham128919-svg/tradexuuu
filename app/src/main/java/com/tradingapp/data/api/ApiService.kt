package com.tradingapp.data.api

import com.tradingapp.data.model.*; import retrofit2.Response; import retrofit2.http.*

data class ApiList<T>(val data: List<T>)
data class ApiSingle<T>(val data: T)
data class QuoteResponse(val data: List<Quote>)
data class CandleResponse(val data: List<Candle>)
data class PlaceOrderResponse(val orderId: String, val note: String = "")
data class AuthResponse(val token: String, val user: UserInfo)
data class UserInfo(val id: String, val name: String)
data class PortfolioSummary(val totalValue: Double, val totalInvested: Double, val totalPnl: Double, val totalPnlPct: Double)
data class HoldingsResponse(val summary: PortfolioSummary, val data: List<Holding>)
data class PositionSummary(val dayPnl: Double)
data class PositionsResponse(val summary: PositionSummary, val data: List<Position>)
data class TokenResponse(val message: String, val appToken: String)

interface ApiService {
    // Auth — update Groww token (testing phase)
    @POST("auth/update-groww-token")
    suspend fun updateGrowwToken(@Body body: Map<String, String>): Response<TokenResponse>

    // Market
    @GET("market/quotes")
    suspend fun getQuotes(@Query("symbols") symbols: String): Response<QuoteResponse>

    // Period: "1D" | "1W" | "1M" | "3M" | "1Y" | "5Y"
    @GET("market/candles")
    suspend fun getCandles(@Query("symbol") symbol: String, @Query("period") period: String): Response<CandleResponse>

    @GET("market/search")
    suspend fun searchInstruments(@Query("q") query: String): Response<ApiList<Quote>>

    @GET("market/indices")
    suspend fun getIndices(): Response<ApiList<Quote>>

    @GET("market/explore")
    suspend fun getExplore(@Query("segment") segment: String, @Query("type") type: String): Response<ApiList<Quote>>

    // Portfolio
    @GET("portfolio/holdings")
    suspend fun getHoldings(): Response<HoldingsResponse>

    @GET("portfolio/positions")
    suspend fun getPositions(): Response<PositionsResponse>

    // Orders
    @GET("orders")
    suspend fun getOrders(@Query("status") status: String? = null): Response<ApiList<Order>>

    @POST("orders")
    suspend fun placeOrder(@Body params: Map<String, @JvmSuppressWildcards Any>): Response<PlaceOrderResponse>

    @DELETE("orders/{id}")
    suspend fun cancelOrder(@Path("id") orderId: String): Response<Map<String, Boolean>>

    // Watchlist
    @GET("watchlist")
    suspend fun getWatchlist(): Response<ApiList<WatchlistItem>>

    @POST("watchlist")
    suspend fun addToWatchlist(@Body body: Map<String, String>): Response<ApiSingle<WatchlistItem>>

    @DELETE("watchlist/{id}")
    suspend fun removeFromWatchlist(@Path("id") id: Int): Response<Map<String, Boolean>>

    // Mutual Funds
    @GET("mf/explore")
    suspend fun getMfExplore(): Response<ApiList<MutualFund>>

    @GET("mf/search")
    suspend fun searchFunds(@Query("q") q: String): Response<ApiList<MutualFund>>

    @GET("mf/{code}/chart")
    suspend fun getMfChart(@Path("code") schemeCode: String): Response<ApiList<NavPoint>>

    @GET("mf/holdings")
    suspend fun getMfHoldings(): Response<ApiList<MutualFund>>

    @GET("mf/sips")
    suspend fun getSips(): Response<ApiList<Map<String, Any>>>
}

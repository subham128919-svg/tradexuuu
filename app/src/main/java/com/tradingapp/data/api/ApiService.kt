package com.tradingapp.data.api

import com.tradingapp.data.model.*
import retrofit2.Response
import retrofit2.http.*

data class ApiList<T>(val data: List<T>)
data class ApiSingle<T>(val data: T)
data class QuoteResponse(val data: List<Quote>)
data class CandleResponse(val data: List<Candle>)
data class PortfolioSummary(val totalValue: Double, val totalInvested: Double, val totalPnl: Double, val totalPnlPct: Double)
data class HoldingsResponse(val summary: PortfolioSummary, val data: List<Holding>)
data class PositionSummary(val dayPnl: Double)
data class PositionsResponse(val summary: PositionSummary, val data: List<Position>)
data class AuthUserInfo(val id: Int, val name: String, val email: String, val phone: String? = null)
data class AuthResponse(val token: String, val user: AuthUserInfo)
data class MoverItem(val symbol: String, val name: String, val ltp: Double = 0.0,
                     val prevClose: Double = 0.0, val changePct: Double = 0.0, val changeAbs: Double = 0.0)
data class TopMoversResponse(val gainers: List<MoverItem>, val losers: List<MoverItem>, val indices: List<MoverItem>)
data class Fundamentals(val mktCap: String?, val peRatio: String?, val pbRatio: String?,
                        val eps: String?, val roe: String?, val divYield: String?,
                        val bookValue: String?, val debtEquity: String?,
                        val sector: String?, val industry: String?)
data class FundamentalsResponse(val data: Fundamentals?)
data class AppPosition(val symbol: String, val exchange: String, val name: String,
                       val qty: Int, val avgPrice: Double, val ltp: Double,
                       val currentValue: Double, val invested: Double,
                       val pnl: Double, val pnlPct: Double, val isProfit: Boolean,
                       // FIX (P&L bug): backend now reports this explicitly.
                       // false means "we don't have a real price for this
                       // symbol yet" — pnl/pnlPct are held at 0 in that case,
                       // not a fabricated -100%. Defaults true so any other
                       // existing caller of this data class keeps compiling.
                       val priceAvailable: Boolean = true)
data class AppPositionsResponse(val data: List<AppPosition>, val totalValue: Double,
                                val totalInvested: Double, val totalPnl: Double, val totalPnlPct: Double)
data class AppOrderResponse(val success: Boolean, val message: String)
data class WalletResponse(val balance: Double, val transactions: List<Map<String, Any>>)
data class TrackResponse(val ok: Boolean, val symbol: String, val tracked: Int)

interface ApiService {
    @POST("users/login")
    suspend fun login(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<AuthResponse>

    @POST("users/register")
    suspend fun register(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<AuthResponse>

    @GET("market/quotes")
    suspend fun getQuotes(@Query("symbols") symbols: String): Response<QuoteResponse>

    @GET("market/candles")
    suspend fun getCandles(@Query("symbol") symbol: String, @Query("period") period: String): Response<CandleResponse>

    @GET("market/search")
    suspend fun searchInstruments(@Query("q") query: String): Response<ApiList<Quote>>

    @GET("market/indices")
    suspend fun getIndices(): Response<ApiList<Quote>>

    @GET("market/indices/all")
    suspend fun getAllIndices(): Response<ApiList<MoverItem>>

    @GET("market/explore")
    suspend fun getExplore(@Query("segment") segment: String, @Query("type") type: String): Response<ApiList<Quote>>

    @GET("market/top-movers")
    suspend fun getTopMovers(): Response<TopMoversResponse>

    @GET("market/fundamentals/{exchange}/{symbol}")
    suspend fun getFundamentals(@Path("exchange") ex: String, @Path("symbol") sym: String): Response<FundamentalsResponse>

    @GET("market/universe")
    suspend fun getUniverse(@Query("section") section: String): Response<ApiList<Map<String, Any>>>

    // Background tracking
    @POST("tracking/add")
    suspend fun trackSymbol(@Body body: Map<String, String>): Response<TrackResponse>

    // Wallet
    @GET("wallet")
    suspend fun getWallet(): Response<WalletResponse>

    // Orders
    @POST("app-orders")
    suspend fun placeAppOrder(@Body body: Map<String, @JvmSuppressWildcards Any>): Response<AppOrderResponse>

    @GET("app-orders")
    suspend fun getAppOrders(@Query("status") status: String? = null): Response<ApiList<Map<String, Any>>>

    @GET("app-orders/positions")
    suspend fun getAppPositions(): Response<AppPositionsResponse>

    @GET("portfolio/holdings")
    suspend fun getHoldings(): Response<HoldingsResponse>

    @GET("portfolio/positions")
    suspend fun getPositions(): Response<PositionsResponse>

    @GET("orders")
    suspend fun getOrders(@Query("status") status: String? = null): Response<ApiList<Order>>

    @GET("watchlist")
    suspend fun getWatchlist(): Response<ApiList<WatchlistItem>>

    @POST("watchlist")
    suspend fun addToWatchlist(@Body body: Map<String, String>): Response<ApiSingle<WatchlistItem>>

    @DELETE("watchlist/{id}")
    suspend fun removeFromWatchlist(@Path("id") id: Int): Response<Map<String, Boolean>>

    @GET("mf/explore")
    suspend fun getMfExplore(): Response<ApiList<MutualFund>>
}

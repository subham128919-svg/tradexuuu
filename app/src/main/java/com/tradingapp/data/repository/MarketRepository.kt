package com.tradingapp.data.repository

import com.tradingapp.data.api.ApiService
import com.tradingapp.data.db.QuoteDao
import com.tradingapp.data.db.RecentViewedDao
import com.tradingapp.data.model.Candle
import com.tradingapp.data.model.Quote
import com.tradingapp.data.model.RecentViewed
import com.tradingapp.data.websocket.ConnectionState
import com.tradingapp.data.websocket.PriceTick
import com.tradingapp.data.websocket.PriceWebSocket
import com.tradingapp.di.ApplicationScope
import com.tradingapp.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────
//  MarketRepository — offline-first, Room is the single source of
//  truth for anything displayed on screen.
//
//  Architecture:
//    WebSocket ticks ──► Room (quotes table) ──► Flow ──► UI
//                              ▲
//    REST refresh ─────────────┘  (seeds/updates the same table)
//
//  Screens NEVER merge ticks into a UI list manually anymore. They
//  observe Room via Flow; Room notifies them automatically whenever a
//  relevant row changes (see QuoteDao.observeQuotes). This is what
//  fixes "prices load once but don't move": previously each Fragment
//  tried to patch its own in-memory list from a raw tick stream, which
//  is exactly the kind of manual state management that leads to
//  missed updates, duplicate work, and race conditions.
//
//  Backward compatibility: the old method names/signatures
//  (getQuotes/getCandles/getIndices/getExplore/subscribeToLivePrices/
//  unsubscribe/liveTicks) are kept as thin wrappers so ChartViewModel
//  and any other existing call site keep compiling unchanged. New
//  code (Explore, Watchlist) should use the reactive API below instead.
// ─────────────────────────────────────────────────────────────────
@Singleton
class MarketRepository @Inject constructor(
    private val api: ApiService,
    private val quoteDao: QuoteDao,
    private val recentViewedDao: RecentViewedDao,
    private val priceWs: PriceWebSocket,
    @ApplicationScope private val appScope: CoroutineScope
) {
    val connectionState: StateFlow<ConnectionState> = priceWs.connectionState

    init {
        // Central tick-persistence pipeline. Runs for the entire app
        // lifetime (ApplicationScope, not viewModelScope) so prices keep
        // updating in the cache even while the user is on a screen that
        // isn't actively observing them — e.g. ticks for a watchlist
        // symbol still land in Room while the user is looking at a chart.
        appScope.launch {
            priceWs.ticks.collect { tick -> persistTick(tick) }
        }
    }

    private suspend fun persistTick(tick: PriceTick) {
        quoteDao.upsert(listOf(
            Quote(
                symbol    = tick.symbol,
                exchange  = tick.symbol.substringBefore(":", "NSE"),
                name      = null,
                ltp       = tick.ltp,
                open      = tick.open,
                high      = tick.high,
                low       = tick.low,
                close     = tick.close,
                change    = tick.change,
                changePct = tick.changePct,
                volume    = tick.volume,
                updatedAt = tick.ts
            )
        ))
    }

    // ═══════════════════════════════════════════════════════════════
    //  REACTIVE API — use this for anything shown continuously on
    //  screen (Explore grid, Watchlist rows, chart header price).
    // ═══════════════════════════════════════════════════════════════

    // Instant cached emission, then live updates as ticks arrive.
    // Returns empty list immediately for an empty symbol list rather
    // than hanging — avoids a common Flow footgun.
    fun observeQuotes(symbols: List<String>): Flow<List<Quote>> =
        if (symbols.isEmpty()) flowOf(emptyList()) else quoteDao.observeQuotes(symbols)

    // One-shot REST call that seeds/refreshes Room. The UI does NOT wait
    // on this to render — it already has cached data via observeQuotes.
    // This just keeps the cache honest.
    suspend fun refreshQuotes(symbols: List<String>): Resource<List<Quote>> {
        if (symbols.isEmpty()) return Resource.Success(emptyList())
        return try {
            val r = api.getQuotes(symbols.joinToString(","))
            if (r.isSuccessful) {
                val data = r.body()!!.data
                quoteDao.upsert(data)
                Resource.Success(data)
            } else Resource.Error("API error ${r.code()}")
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Network error")
        }
    }

    suspend fun refreshExplore(segment: String, type: String): Resource<List<Quote>> = try {
        val r = api.getExplore(segment, type)
        if (r.isSuccessful) {
            val data = r.body()!!.data
            quoteDao.upsert(data)
            Resource.Success(data)
        } else Resource.Error("API error ${r.code()}")
    } catch (e: Exception) {
        Resource.Error(e.message ?: "Network error")
    }

    suspend fun refreshIndices(): Resource<List<Quote>> = try {
        val r = api.getIndices()
        if (r.isSuccessful) {
            val data = r.body()!!.data
            quoteDao.upsert(data)
            Resource.Success(data)
        } else Resource.Error("API error ${r.code()}")
    } catch (e: Exception) {
        Resource.Error(e.message ?: "Network error")
    }

    fun subscribeLive(symbols: List<String>)   { if (symbols.isNotEmpty()) priceWs.subscribe(symbols) }
    fun unsubscribeLive(symbols: List<String>) { if (symbols.isNotEmpty()) priceWs.unsubscribe(symbols) }

    // ── Recently viewed instruments (cached locally, offline-safe) ────
    suspend fun markViewed(symbol: String, exchange: String, name: String) {
        recentViewedDao.upsert(RecentViewed(symbol = "$exchange:$symbol", exchange = exchange, name = name))
    }
    fun observeRecentViewed(): Flow<List<RecentViewed>> = recentViewedDao.observeRecent()

    // ═══════════════════════════════════════════════════════════════
    //  LEGACY API — kept unchanged so existing call sites (ChartViewModel
    //  etc.) keep compiling without modification. Internally now backed
    //  by the same Room-first logic above.
    // ═══════════════════════════════════════════════════════════════

    fun getQuotes(symbols: List<String>): Flow<Resource<List<Quote>>> = flow {
        emit(Resource.Loading)
        emit(refreshQuotes(symbols))
    }

    fun getCandles(symbol: String, period: String): Flow<Resource<List<Candle>>> = flow {
        emit(Resource.Loading)
        try {
            val r = api.getCandles(symbol, period)
            if (r.isSuccessful) emit(Resource.Success(r.body()!!.data))
            else emit(Resource.Error("API error ${r.code()}"))
        } catch (e: Exception) {
            emit(Resource.Error(e.message ?: "Network error"))
        }
    }

    fun getIndices(): Flow<Resource<List<Quote>>> = flow {
        emit(Resource.Loading)
        emit(refreshIndices())
    }

    fun getExplore(segment: String, type: String): Flow<Resource<List<Quote>>> = flow {
        emit(Resource.Loading)
        emit(refreshExplore(segment, type))
    }

    fun subscribeToLivePrices(symbols: List<String>) = subscribeLive(symbols)
    fun unsubscribe(symbols: List<String>) = unsubscribeLive(symbols)
    val liveTicks: Flow<PriceTick> = priceWs.ticks
}

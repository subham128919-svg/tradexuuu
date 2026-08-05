package com.tradingapp.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.db.WatchlistDao
import com.tradingapp.data.model.WatchlistItem
import com.tradingapp.data.repository.MarketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Row shown on screen: membership (from WatchlistDao) + live price
// (from MarketRepository/Room), kept as separate concerns and merged
// here rather than storing live price directly on WatchlistItem.
data class WatchlistRow(
    val symbol: String,
    val exchange: String,
    val ltp: Double,
    val changePct: Double
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val dao: WatchlistDao,
    private val api: ApiService,
    private val repo: MarketRepository
) : ViewModel() {

    // Reactive pipeline: watchlist membership (Room) -> subscribe those
    // symbols live -> combine with live quotes (Room) -> UI rows.
    // Both halves are Room-backed, so this works offline and updates
    // in real time as ticks arrive — no polling, no manual merging.
    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<WatchlistRow>> = dao.observeAll().flatMapLatest { items ->
        if (items.isEmpty()) return@flatMapLatest flowOf(emptyList())
        val fullSymbols = items.map { "${it.exchange}:${it.symbol}" }
        repo.subscribeLive(fullSymbols)
        repo.observeQuotes(fullSymbols).map { quotes ->
            val bySymbol = quotes.associateBy { it.symbol }
            items.map { item ->
                val key = "${item.exchange}:${item.symbol}"
                val q = bySymbol[key]
                WatchlistRow(
                    symbol    = item.symbol,
                    exchange  = item.exchange,
                    ltp       = q?.ltp ?: item.ltp,
                    changePct = q?.changePct ?: item.changePct
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun load() = viewModelScope.launch {
        try {
            val r = api.getWatchlist()
            if (r.isSuccessful) r.body()!!.data.forEach { dao.insert(it) }
        } catch (_: Exception) {
            // Offline is fine — dao.observeAll() still emits whatever
            // was cached from the last successful sync.
        }
    }

    fun remove(item: WatchlistItem) = viewModelScope.launch { dao.delete(item) }
}

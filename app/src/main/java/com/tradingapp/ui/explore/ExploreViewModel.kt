package com.tradingapp.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.model.DefaultUniverse
import com.tradingapp.data.model.Quote
import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.data.websocket.ConnectionState
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(private val repo: MarketRepository) : ViewModel() {

    // Seeded IMMEDIATELY from the bundled offline universe — the screen
    // never waits on a network call to know what to show. A background
    // refresh (see load()) re-ranks this for gainers/losers/trending,
    // but if that fails, the grid still shows the bundled list with
    // whatever's cached in Room (or "Fetching…" placeholders).
    private val _symbolOrder = MutableStateFlow(DefaultUniverse.symbols())

    // Non-blocking sync indicator — never used to hide the grid.
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError

    val connectionState: StateFlow<ConnectionState> = repo.connectionState

    @OptIn(ExperimentalCoroutinesApi::class)
    val quotes: StateFlow<List<Quote>> = _symbolOrder.flatMapLatest { symbols ->
        if (symbols.isEmpty()) flowOf(emptyList())
        else repo.observeQuotes(symbols).map { list ->
            val bySymbol = list.associateBy { it.symbol }
            // Never filter a symbol out for missing data — show a
            // placeholder instead so the card stays visible.
            symbols.map { sym -> bySymbol[sym] ?: DefaultUniverse.placeholder(sym) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Start receiving live ticks for the default universe immediately,
        // independent of whether the REST refresh below ever succeeds.
        repo.subscribeLive(DefaultUniverse.symbols())
    }

    fun load(segment: String, type: String) = viewModelScope.launch {
        when (val res = repo.refreshExplore(segment, type)) {
            is Resource.Success -> {
                _syncError.value = null
                val symbols = res.data.map { it.symbol }
                if (symbols.isNotEmpty()) {
                    _symbolOrder.value = symbols
                    repo.subscribeLive(symbols)
                }
            }
            is Resource.Error -> {
                // Keep showing whatever's cached/bundled — just note why
                // the ranking might be stale, don't blank the screen.
                _syncError.value = res.message
            }
            else -> {}
        }
    }

    fun retry(segment: String, type: String) = load(segment, type)

    override fun onCleared() {
        super.onCleared()
        repo.unsubscribeLive(_symbolOrder.value)
    }
}

package com.tradingapp.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.MoverItem
import com.tradingapp.data.model.DefaultUniverse
import com.tradingapp.data.model.Quote
import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.data.websocket.ConnectionState
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val repo: MarketRepository,
    private val api: ApiService
) : ViewModel() {

    private val _symbolOrder = MutableStateFlow(DefaultUniverse.symbols())
    private val _syncError   = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError

    val connectionState: StateFlow<ConnectionState> = repo.connectionState

    // Top movers — loaded once, refreshed on pull-to-refresh
    private val _gainers = MutableStateFlow<List<MoverItem>>(emptyList())
    private val _losers  = MutableStateFlow<List<MoverItem>>(emptyList())
    val gainers: StateFlow<List<MoverItem>> = _gainers
    val losers:  StateFlow<List<MoverItem>> = _losers

    @OptIn(ExperimentalCoroutinesApi::class)
    val quotes: StateFlow<List<Quote>> = _symbolOrder.flatMapLatest { symbols ->
        if (symbols.isEmpty()) flowOf(emptyList())
        else repo.observeQuotes(symbols).map { list ->
            val bySymbol = list.associateBy { it.symbol }
            symbols.map { sym -> bySymbol[sym] ?: DefaultUniverse.placeholder(sym) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        repo.subscribeLive(DefaultUniverse.symbols())
        loadTopMovers()
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
            is Resource.Error -> _syncError.value = res.message
            else -> {}
        }
    }

    fun loadTopMovers() = viewModelScope.launch {
        try {
            val r = api.getTopMovers()
            if (r.isSuccessful) {
                val body = r.body()!!
                _gainers.value = body.gainers
                _losers.value  = body.losers
            }
        } catch (_: Exception) {
            // Non-fatal — movers are a bonus feature; explore still works without them
        }
    }

    fun retry(segment: String, type: String) = load(segment, type)

    override fun onCleared() {
        super.onCleared()
        repo.unsubscribeLive(_symbolOrder.value)
    }
}

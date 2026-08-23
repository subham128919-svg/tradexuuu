package com.tradingapp.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.model.Candle
import com.tradingapp.data.model.Quote
import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repo: MarketRepository,
    private val api:  ApiService
) : ViewModel() {

    private val _candles = MutableStateFlow<Resource<List<Candle>>>(Resource.Loading)
    val candles: StateFlow<Resource<List<Candle>>> = _candles

    private val _quote = MutableStateFlow<Quote?>(null)
    val quote: StateFlow<Quote?> = _quote

    // F&O: whether this symbol has an option chain at all. Checked
    // against the real instruments table server-side (market.js
    // /has-options) rather than guessed client-side, since most
    // equities have no derivatives and a hardcoded list would be wrong
    // as often as it's right.
    private val _hasOptions = MutableStateFlow(false)
    val hasOptions: StateFlow<Boolean> = _hasOptions

    fun checkOptionsAvailable(fullSymbol: String) = viewModelScope.launch {
        try {
            val r = api.hasOptions(fullSymbol)
            if (r.isSuccessful) _hasOptions.value = r.body()?.hasOptions ?: false
        } catch (_: Exception) { /* button stays hidden */ }
    }

    val liveTicks = repo.liveTicks
    private var candleJob: Job? = null

    // Tell backend to permanently track this symbol so it stores
    // live + historical data indefinitely, irrespective of who's online.
    fun trackSymbol(fullSymbol: String) = viewModelScope.launch {
        try { api.trackSymbol(mapOf("symbol" to fullSymbol)) } catch (_: Exception) {}
    }

    fun loadChart(symbol: String, exchange: String, period: String) {
        loadCandles(symbol, exchange, period)
        viewModelScope.launch {
            repo.getQuotes(listOf("$exchange:$symbol")).collectLatest { res ->
                if (res is Resource.Success) _quote.value = res.data.firstOrNull()
            }
        }
        repo.subscribeToLivePrices(listOf("$exchange:$symbol"))
    }

    fun loadCandles(symbol: String, exchange: String, period: String) {
        candleJob?.cancel()
        candleJob = viewModelScope.launch {
            _candles.value = Resource.Loading
            repo.getCandles("$exchange:$symbol", period).collectLatest { _candles.value = it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _quote.value?.let { repo.unsubscribe(listOf(it.symbol)) }
    }
}

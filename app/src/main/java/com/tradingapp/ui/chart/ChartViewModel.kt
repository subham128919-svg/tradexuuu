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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var quoteJob: Job? = null
    private var subscribedSymbol: String? = null
    private var pollingJob: Job? = null
    private var selectedChart: Triple<String, String, String>? = null
    private val candleCache = mutableMapOf<String, List<Candle>>()

    // Tell backend to permanently track this symbol so it stores
    // live + historical data indefinitely, irrespective of who's online.
    fun trackSymbol(fullSymbol: String) = viewModelScope.launch {
        try { api.trackSymbol(mapOf("symbol" to fullSymbol)) } catch (_: Exception) {}
    }

    fun loadChart(symbol: String, exchange: String, period: String) {
        loadCandles(symbol, exchange, period)
        val fullSymbol = "$exchange:$symbol"
        if (subscribedSymbol == fullSymbol) return
        subscribedSymbol?.let { repo.unsubscribe(listOf(it)) }
        subscribedSymbol = fullSymbol
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            launch { repo.refreshQuotes(listOf(fullSymbol)) }
            repo.observeQuotes(listOf(fullSymbol)).collectLatest { quotes ->
                _quote.value = quotes.firstOrNull()
            }
        }
        repo.subscribeToLivePrices(listOf(fullSymbol))
    }

    fun loadCandles(symbol: String, exchange: String, period: String) {
        selectedChart = Triple(symbol, exchange, period)
        val key = "$exchange:$symbol:$period"
        candleJob?.cancel()
        candleJob = viewModelScope.launch {
            val cached = candleCache[key]
            _candles.value = if (cached == null) Resource.Loading else Resource.Success(cached)
            repo.getCandles("$exchange:$symbol", period).collectLatest {
                if (it is Resource.Success) candleCache[key] = it.data
                if (it !is Resource.Loading || cached == null) _candles.value = it
            }
        }
    }
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch { while (isActive) {
            delay(30_000)
            selectedChart?.let { (symbol, exchange, period) -> if(candleJob?.isActive != true) loadCandles(symbol, exchange, period) }
        } }
    }
    fun stopPolling() { pollingJob?.cancel(); pollingJob = null }

    override fun onCleared() {
        super.onCleared()
        subscribedSymbol?.let { repo.unsubscribe(listOf(it)) }
    }
}

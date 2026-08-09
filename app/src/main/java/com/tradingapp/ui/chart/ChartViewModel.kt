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

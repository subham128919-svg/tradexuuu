package com.tradingapp.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.model.Candle
import com.tradingapp.data.model.Quote
import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repo: MarketRepository
) : ViewModel() {

    private val _candles = MutableStateFlow<Resource<List<Candle>>>(Resource.Loading)
    val candles: StateFlow<Resource<List<Candle>>> = _candles

    private val _quote = MutableStateFlow<Quote?>(null)
    val quote: StateFlow<Quote?> = _quote

    val liveTicks = repo.liveTicks

    private var candleJob: Job? = null
    private var quoteJob:  Job? = null

    // Called each time the user taps a period button (1D, 1W, 1M, 3M, 1Y, 5Y)
    // and on initial load. Always goes to our backend which serves from DB
    // first, so this works whether the market is open or closed.
    fun loadChart(symbol: String, exchange: String, period: String) {
        loadCandles(symbol, exchange, period)
        loadQuote(symbol, exchange)
        repo.subscribeToLivePrices(listOf("$exchange:$symbol"))
    }

    fun loadCandles(symbol: String, exchange: String, period: String) {
        candleJob?.cancel()
        candleJob = viewModelScope.launch {
            _candles.value = Resource.Loading
            repo.getCandles("$exchange:$symbol", period).collectLatest { result ->
                _candles.value = result
            }
        }
    }

    private fun loadQuote(symbol: String, exchange: String) {
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            repo.getQuotes(listOf("$exchange:$symbol")).collectLatest { res ->
                if (res is Resource.Success) _quote.value = res.data.firstOrNull()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _quote.value?.let { repo.unsubscribe(listOf(it.symbol)) }
    }
}

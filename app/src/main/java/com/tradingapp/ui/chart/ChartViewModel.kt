package com.tradingapp.ui.chart

import androidx.lifecycle.*; import com.tradingapp.data.model.Candle; import com.tradingapp.data.model.Quote
import com.tradingapp.data.repository.MarketRepository; import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChartViewModel @Inject constructor(private val repo: MarketRepository) : ViewModel() {

    private val _candles = MutableStateFlow<Resource<List<Candle>>>(Resource.Loading)
    val candles: StateFlow<Resource<List<Candle>>> = _candles

    private val _quote = MutableStateFlow<Quote?>(null)
    val quote: StateFlow<Quote?> = _quote

    val liveTicks = repo.liveTicks

    fun loadChart(symbol: String, exchange: String, period: String) = viewModelScope.launch {
        // Load candles — backend now takes period directly (1D|1W|1M|3M|1Y|5Y)
        repo.getCandles("$exchange:$symbol", period).collect { _candles.value = it }
        // Subscribe live ticks
        repo.subscribeToLivePrices(listOf("$exchange:$symbol"))
        // Get current quote for header
        repo.getQuotes(listOf("$exchange:$symbol")).collect { res ->
            if (res is Resource.Success) _quote.value = res.data.firstOrNull()
        }
    }

    override fun onCleared() {
        super.onCleared()
        _quote.value?.let { repo.unsubscribe(listOf(it.symbol)) }
    }
}

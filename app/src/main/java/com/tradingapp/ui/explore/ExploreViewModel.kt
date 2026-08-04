package com.tradingapp.ui.explore

import androidx.lifecycle.*; import com.tradingapp.data.model.Quote; import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.util.Resource; import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch; import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(private val repo: MarketRepository) : ViewModel() {

    private val _exploreItems = MutableStateFlow<Resource<List<Quote>>>(Resource.Loading)
    val exploreItems: StateFlow<Resource<List<Quote>>> = _exploreItems

    private val _liveTick = MutableStateFlow<Map<String, Double>>(emptyMap())
    val liveTick: StateFlow<Map<String, Double>> = _liveTick

    fun load(segment: String, type: String) = viewModelScope.launch {
        repo.getExplore(segment, type).collect { _exploreItems.value = it }
    }

    // Subscribe to live price updates and merge with displayed items
    fun subscribeSymbols(symbols: List<String>) {
        repo.subscribeToLivePrices(symbols)
        viewModelScope.launch {
            repo.liveTicks.collect { tick ->
                _liveTick.value = _liveTick.value + (tick.symbol to tick.ltp)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        (_exploreItems.value as? Resource.Success)?.data
            ?.map { it.symbol }
            ?.let { repo.unsubscribe(it) }
    }
}

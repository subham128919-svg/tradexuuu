package com.tradingapp.ui.main

import androidx.lifecycle.*; import com.tradingapp.data.model.Quote; import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.util.Resource; import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(private val marketRepo: MarketRepository) : ViewModel() {

    private val _indices = MutableStateFlow<Resource<List<Quote>>>(Resource.Loading)
    val indices: StateFlow<Resource<List<Quote>>> = _indices

    // Currently selected segment: "equity" | "fno" | "mf"
    private val _segment = MutableStateFlow("equity")
    val segment: StateFlow<String> = _segment

    init { loadIndices() }

    fun loadIndices() = viewModelScope.launch {
        marketRepo.getIndices().collect { _indices.value = it }
    }

    fun setSegment(seg: String) {
        _segment.value = seg
    }
}

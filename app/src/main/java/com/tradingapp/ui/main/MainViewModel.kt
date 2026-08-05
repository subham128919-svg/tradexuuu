package com.tradingapp.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.model.DefaultUniverse
import com.tradingapp.data.model.Quote
import com.tradingapp.data.repository.MarketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(private val repo: MarketRepository) : ViewModel() {

    private val indexSymbols = DefaultUniverse.INDICES.map { it.symbol }

    // Room-backed — index ticker shows last cached values instantly on
    // launch, updates live via WebSocket, never blanks if a refresh
    // fails (e.g. token not yet refreshed today, market closed).
    val indices: StateFlow<List<Quote>> = repo.observeQuotes(indexSymbols)
        .map { list ->
            val bySymbol = list.associateBy { it.symbol }
            indexSymbols.map { sym -> bySymbol[sym] ?: DefaultUniverse.placeholder(sym) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _segment = MutableStateFlow("equity")
    val segment: StateFlow<String> = _segment

    init {
        repo.subscribeLive(indexSymbols)
        refreshIndices()
    }

    fun refreshIndices() = viewModelScope.launch { repo.refreshIndices() }

    fun setSegment(seg: String) {
        _segment.value = seg
    }
}

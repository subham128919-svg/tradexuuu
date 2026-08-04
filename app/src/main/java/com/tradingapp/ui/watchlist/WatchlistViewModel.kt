package com.tradingapp.ui.watchlist

import androidx.lifecycle.*; import com.tradingapp.data.api.ApiService; import com.tradingapp.data.db.WatchlistDao; import com.tradingapp.data.model.WatchlistItem; import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch; import javax.inject.Inject

@HiltViewModel
class WatchlistViewModel @Inject constructor(private val dao: WatchlistDao, private val api: ApiService) : ViewModel() {
    val watchlist: StateFlow<List<WatchlistItem>> = dao.observeAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    fun load() = viewModelScope.launch {
        try {
            val r = api.getWatchlist(); if (r.isSuccessful) { r.body()!!.data.forEach { dao.insert(it) } }
        } catch (_: Exception) {}
    }
    fun remove(item: WatchlistItem) = viewModelScope.launch { dao.delete(item) }
}

package com.tradingapp.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.model.Quote
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    private val _results = MutableStateFlow<Resource<List<Quote>>>(Resource.Success(emptyList()))
    val results: StateFlow<Resource<List<Quote>>> = _results

    private var searchJob: Job? = null

    // Debounced search — waits 350ms after typing stops before hitting the API
    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = Resource.Success(emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _results.value = Resource.Loading
            try {
                val r = api.searchInstruments(query.trim())
                if (r.isSuccessful) {
                    _results.value = Resource.Success(r.body()?.data ?: emptyList())
                } else {
                    _results.value = Resource.Error("Search failed (${r.code()})")
                }
            } catch (e: Exception) {
                _results.value = Resource.Error(e.message ?: "Network error")
            }
        }
    }
}

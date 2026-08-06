package com.tradingapp.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.MoverItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FnoExploreViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    private val _indices = MutableStateFlow<List<MoverItem>>(emptyList())
    val indices: StateFlow<List<MoverItem>> = _indices

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    init { loadIndices() }

    fun loadIndices() = viewModelScope.launch {
        _loading.value = true
        try {
            val r = api.getAllIndices()
            if (r.isSuccessful) _indices.value = r.body()!!.data
        } catch (_: Exception) {}
        _loading.value = false
    }
}

package com.tradingapp.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.MoverItem
import com.tradingapp.data.model.DefaultUniverse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FnoExploreViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    private val _indices = MutableStateFlow<List<MoverItem>>(emptyList())
    val indices: StateFlow<List<MoverItem>> = _indices

    init {
        // FIX #3: Show bundled index list IMMEDIATELY — no blank screen
        // Prices show as "—" until the API refreshes them
        _indices.value = DefaultUniverse.INDICES.map { inst ->
            MoverItem(symbol = inst.symbol, name = inst.name, ltp = 0.0, changePct = 0.0, changeAbs = 0.0)
        }
        loadIndices()
    }

    fun loadIndices() = viewModelScope.launch {
        try {
            val r = api.getAllIndices()
            if (r.isSuccessful && !r.body()!!.data.isNullOrEmpty()) {
                _indices.value = r.body()!!.data
            }
        } catch (_: Exception) {
            // Keep showing bundled defaults — no blank screen
        }
    }
}

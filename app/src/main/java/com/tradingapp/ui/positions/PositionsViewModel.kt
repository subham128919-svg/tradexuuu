package com.tradingapp.ui.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.AppPosition
import com.tradingapp.data.api.AppPositionsResponse
import com.tradingapp.data.websocket.PriceTick
import com.tradingapp.data.websocket.PriceWebSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val api: ApiService,
    private val priceWs: PriceWebSocket
) : ViewModel() {

    private val _positions = MutableStateFlow<AppPositionsResponse?>(null)
    val positions: StateFlow<AppPositionsResponse?> = _positions

    val liveTicks: Flow<PriceTick> = priceWs.ticks

    init { load(); pollPositions() }

    fun load() = viewModelScope.launch {
        try {
            val r = api.getAppPositions()
            if (r.isSuccessful) {
                val data = r.body()!!
                _positions.value = data
                // Subscribe WebSocket for all held symbols
                val syms = data.data.map { it.symbol }
                if (syms.isNotEmpty()) { priceWs.connect(); priceWs.subscribe(syms) }
            }
        } catch (_: Exception) {}
    }

    // Poll positions every 30s to refresh P&L even if WebSocket misses ticks
    private fun pollPositions() = viewModelScope.launch {
        while (isActive) { delay(30_000); load() }
    }
}

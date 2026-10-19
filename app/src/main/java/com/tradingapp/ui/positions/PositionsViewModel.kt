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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val api: ApiService,
    private val priceWs: PriceWebSocket,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _positions = MutableStateFlow<AppPositionsResponse?>(null)
    val positions: StateFlow<AppPositionsResponse?> = _positions

    val liveTicks: Flow<PriceTick> = priceWs.ticks
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var selectedSegment: String? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var subscribed = emptyList<String>()
    private var session: String? = null

    fun load(segment: String? = null) {
        if (segment != null) selectedSegment = segment.uppercase()
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
        try {
            val r = api.getAppPositions(selectedSegment)
            if (r.isSuccessful) {
                val data = r.body()!!
                _positions.value = data
                _error.value = null
                // Subscribe WebSocket for all held symbols
                val syms = data.data.map { it.symbol }
                val unique = syms.distinct()
                priceWs.unsubscribe(subscribed - unique.toSet())
                priceWs.subscribe(unique - subscribed.toSet())
                subscribed = unique
            } else _error.value = "Could not refresh positions (${r.code()}); displayed quantities may be stale"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _error.value = "Could not refresh positions; check connection before exiting" }
        }
    }

    // Poll positions every 30s to refresh P&L even if WebSocket misses ticks
    fun start(segment: String) {
        val token = context.getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE).getString("jwt_token", null)
        if(token != session) { stop(); _positions.value = null; _error.value = null; session = token }
        load(segment)
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch { while (isActive) { delay(30_000); load() } }
    }
    fun stop() {
        pollJob?.cancel(); pollJob = null
        loadJob?.cancel(); loadJob = null
        priceWs.unsubscribe(subscribed); subscribed = emptyList()
    }
    override fun onCleared() { stop(); super.onCleared() }
}

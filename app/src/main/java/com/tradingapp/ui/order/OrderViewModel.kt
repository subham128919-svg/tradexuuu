package com.tradingapp.ui.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrderViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    private val _ltp     = MutableStateFlow(0.0)
    val ltp: StateFlow<Double> = _ltp

    private val _balance = MutableStateFlow(0.0)
    val balance: StateFlow<Double> = _balance

    // FIX: fetched fresh from the backend rather than trusting an
    // Intent extra passed through from wherever the user navigated
    // from — that only worked when coming through the option chain
    // screen; search, watchlist, holdings, etc. never knew the lot
    // size to pass along, so those entry points silently fell back to
    // lot=1 (treating options like equity — raw quantity, not lots).
    // Defaults to 1 (equity, no lot concept) until the lookup resolves;
    // for NSE/BSE symbols it correctly stays 1 forever since lot-size
    // lookup only applies to NFO.
    private val _lotSize = MutableStateFlow(1)
    val lotSize: StateFlow<Int> = _lotSize

    fun loadLotSize(exchange: String, symbol: String) = viewModelScope.launch {
        if (exchange != "NFO") return@launch
        try {
            val r = api.getLotSize(symbol)
            if (r.isSuccessful) _lotSize.value = (r.body()?.lotSize ?: 1).coerceAtLeast(1)
        } catch (_: Exception) { /* stays at 1 — safe default, order will just be per-share */ }
    }

    // FIX #5: Load live price from backend (not from Intent which may be stale)
    fun loadLtp(exchange: String, symbol: String) = viewModelScope.launch {
        try {
            val r = api.getQuotes("$exchange:$symbol")
            if (r.isSuccessful) {
                _ltp.value = r.body()?.data?.firstOrNull()?.ltp ?: 0.0
            }
        } catch (_: Exception) {}
    }

    // FIX #4: Load user wallet balance to show Available funds
    fun loadBalance() = viewModelScope.launch {
        try {
            val r = api.getWallet()
            if (r.isSuccessful) _balance.value = r.body()?.balance ?: 0.0
        } catch (_: Exception) {}
    }

    suspend fun placeOrder(
        symbol: String, exchange: String, name: String,
        orderType: String, product: String, priceType: String,
        qty: Int, price: Double
    ): Result<Double> = try {
        val body = mapOf(
            "symbol" to symbol, "exchange" to exchange, "name" to name,
            "orderType" to orderType, "product" to product, "priceType" to priceType,
            "qty" to qty, "price" to price
        )
        val r = api.placeAppOrder(body)
        if (r.isSuccessful) {
            val newBal = r.body()?.let {
                // Try to parse newBalance from response
                (it as? Map<*, *>)?.get("newBalance").toString().toDoubleOrNull() ?: 0.0
            } ?: 0.0
            Result.success(newBal)
        } else {
            val errBody = r.errorBody()?.string() ?: "Order failed (${r.code()})"
            Result.failure(Exception(errBody))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

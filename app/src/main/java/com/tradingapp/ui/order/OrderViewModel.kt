package com.tradingapp.ui.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.AppOrderResponse
import com.tradingapp.data.api.MarginPreviewResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class OrderViewModel @Inject constructor(private val api: ApiService, private val savedState: SavedStateHandle) : ViewModel() {
    private val _ltp = MutableStateFlow(0.0)
    val ltp: StateFlow<Double> = _ltp
    private val _balance = MutableStateFlow(0.0)
    val balance: StateFlow<Double> = _balance
    private val _lotSize = MutableStateFlow<Int?>(null)
    val lotSize: StateFlow<Int?> = _lotSize
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _margin = MutableStateFlow<MarginPreviewResponse?>(null)
    val margin: StateFlow<MarginPreviewResponse?> = _margin
    private val _marginError = MutableStateFlow<String?>(null)
    val marginError: StateFlow<String?> = _marginError
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting
    private var previewJob: Job? = null
    private var previewParameters: Map<String, String>? = null

    private fun message(raw: String?, fallback: String): String = try {
        JsonParser.parseString(raw ?: "{}").asJsonObject.get("error")?.asString ?: fallback
    } catch (_: Exception) { fallback }

    fun loadLotSize(exchange: String, symbol: String) = viewModelScope.launch {
        if (exchange !in listOf("NFO", "BFO")) { _lotSize.value = 1; return@launch }
        _lotSize.value = null
        try {
            val r = api.getLotSize(symbol.substringAfter(":"), exchange)
            val size = r.body()?.lotSize
            if (!r.isSuccessful || size == null || size <= 0) throw Exception("Contract lot size unavailable. Reopen after instruments are refreshed.")
            _lotSize.value = size
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _error.value = e.message }
    }
    fun loadLtp(exchange: String, symbol: String) = viewModelScope.launch {
        try {
            val r = api.getQuotes("$exchange:${symbol.substringAfter(":")}")
            if (r.isSuccessful) _ltp.value = r.body()?.data?.firstOrNull()?.ltp ?: 0.0
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _error.value = "Could not refresh price; the server will validate execution." }
    }
    fun loadBalance() = viewModelScope.launch {
        try {
            val r = api.getWallet()
            if (r.isSuccessful) _balance.value = r.body()?.balance ?: 0.0
            else _error.value = "Could not load available funds"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _error.value = "Could not load available funds" }
    }
    fun preview(parameters: Map<String, String>?) {
        if (parameters == previewParameters) return
        previewParameters = parameters?.toMap()
        previewJob?.cancel()
        _margin.value = null; _marginError.value = null
        if (parameters == null) return
        previewJob = viewModelScope.launch {
            delay(350)
            try {
                val r = api.getMarginPreview(parameters)
                if (r.isSuccessful && r.body() != null) _margin.value = r.body()
                else _marginError.value = message(r.errorBody()?.string(), "Margin preview unavailable")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _marginError.value = "Margin preview unavailable; server validation is required" }
        }
    }
    suspend fun placeOrder(symbol: String, exchange: String, name: String, orderType: String,
        product: String, priceType: String, qty: Int, price: Double,
        reduceOnly: Boolean = false, positionId: Long? = null): Result<AppOrderResponse> {
        if (_submitting.value) return Result.failure(Exception("Order submission is already in progress"))
        _submitting.value = true
        try {
            val fingerprint = listOf(symbol, exchange, orderType, product, priceType, qty,
                if (priceType == "LIMIT") price else 0.0, reduceOnly, positionId).joinToString("|")
            if (savedState.get<String>("orderFingerprint") != fingerprint) {
                savedState["orderFingerprint"] = fingerprint
                savedState["clientOrderId"] = UUID.randomUUID().toString()
            }
            val body = mutableMapOf<String, Any>("symbol" to symbol, "exchange" to exchange, "name" to name,
                "orderType" to orderType, "product" to product, "priceType" to priceType,
                "qty" to qty, "price" to price, "reduceOnly" to reduceOnly,
                "clientOrderId" to savedState.get<String>("clientOrderId")!!)
            if (positionId != null) body["positionId"] = positionId
            val r = api.placeAppOrder(body)
            val result = r.body()
            if (!r.isSuccessful || result == null || !result.success)
                return Result.failure(Exception(message(r.errorBody()?.string(), "Order was not accepted (${r.code()})")))
            _balance.value = result.newBalance
            return Result.success(result)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return Result.failure(Exception("Could not confirm order. Check Orders; retrying unchanged inputs uses the same request ID.")) }
        finally { _submitting.value = false }
    }
}

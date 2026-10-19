package com.tradingapp.ui.trade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.tradingapp.data.api.ApiService
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import java.util.UUID

@HiltViewModel
class TradeViewModel @Inject constructor(private val api: ApiService, private val savedState: SavedStateHandle) : ViewModel() {
    private var submitting = false

    suspend fun placeOrder(
        symbol: String,
        exchange: String,
        transactionType: String,
        qty: Int,
        product: String,
        orderType: String,
        price: Double
    ): Resource<String> {
      if (submitting) return Resource.Error("Order submission already in progress")
      submitting = true
      return try {
        val fingerprint = listOf(symbol, exchange, transactionType, qty, product, orderType,
            if (orderType == "LIMIT") price else 0.0).joinToString("|")
        if (savedState.get<String>("fingerprint") != fingerprint) {
            savedState["fingerprint"] = fingerprint
            savedState["clientOrderId"] = UUID.randomUUID().toString()
        }
        val body = mapOf(
            "symbol"    to symbol,
            "exchange"  to exchange,
            "name"      to symbol,
            "orderType" to transactionType,
            "product"   to product,
            "priceType" to orderType,
            "qty"       to qty,
            "price"     to price,
            "clientOrderId" to savedState.get<String>("clientOrderId")!!
        )
        val r = api.placeAppOrder(body)
        if (r.isSuccessful && r.body()?.success == true) {
            Resource.Success("#${r.body()!!.orderId}: ${r.body()!!.message}")
        } else {
            Resource.Error(r.errorBody()?.string() ?: "Order failed (${r.code()})")
        }
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Resource.Error("Could not confirm order. Check Orders before retrying.") }
      finally { submitting = false }
    }
}

package com.tradingapp.ui.trade

import androidx.lifecycle.ViewModel
import com.tradingapp.data.api.ApiService
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TradeViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    suspend fun placeOrder(
        symbol: String,
        exchange: String,
        transactionType: String,
        qty: Int,
        product: String,
        orderType: String,
        price: Double
    ): Resource<String> = try {
        val body = mapOf(
            "symbol"    to symbol,
            "exchange"  to exchange,
            "name"      to symbol,
            "orderType" to transactionType,
            "product"   to product,
            "priceType" to orderType,
            "qty"       to qty,
            "price"     to price
        )
        val r = api.placeAppOrder(body)
        if (r.isSuccessful) {
            Resource.Success("Order placed successfully")
        } else {
            Resource.Error(r.errorBody()?.string() ?: "Order failed (${r.code()})")
        }
    } catch (e: Exception) {
        Resource.Error(e.message ?: "Network error")
    }
}

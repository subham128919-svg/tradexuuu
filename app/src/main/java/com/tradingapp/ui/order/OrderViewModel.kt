package com.tradingapp.ui.order

import androidx.lifecycle.ViewModel
import com.tradingapp.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OrderViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    suspend fun placeOrder(
        symbol: String, exchange: String, name: String,
        orderType: String, product: String, priceType: String,
        qty: Int, price: Double
    ): Result<Unit> = try {
        val body = mapOf(
            "symbol" to symbol, "exchange" to exchange, "name" to name,
            "orderType" to orderType, "product" to product, "priceType" to priceType,
            "qty" to qty, "price" to price
        )
        val r = api.placeAppOrder(body)
        if (r.isSuccessful) Result.success(Unit)
        else Result.failure(Exception(r.errorBody()?.string() ?: "Order failed (${r.code()})"))
    } catch (e: Exception) {
        Result.failure(e)
    }
}

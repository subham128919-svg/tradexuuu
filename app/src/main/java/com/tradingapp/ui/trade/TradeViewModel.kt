package com.tradingapp.ui.trade

import androidx.lifecycle.ViewModel; import com.tradingapp.data.repository.PortfolioRepository; import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel; import javax.inject.Inject

@HiltViewModel
class TradeViewModel @Inject constructor(private val repo: PortfolioRepository) : ViewModel() {
    suspend fun placeOrder(symbol: String, exchange: String, type: String, qty: Int, product: String, orderType: String, price: Double): Resource<String> =
        repo.placeOrder(mapOf("symbol" to symbol, "exchange" to exchange, "transactionType" to type, "quantity" to qty, "product" to product, "orderType" to orderType, "price" to price))
}

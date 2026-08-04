package com.tradingapp.data.model
data class Position(val symbol: String, val exchange: String, val product: String, val quantity: Int, val avgCost: Double, val lastPrice: Double, val pnl: Double, val unrealised: Double, val realised: Double) { val isLong get() = quantity > 0; val isProfit get() = pnl >= 0 }

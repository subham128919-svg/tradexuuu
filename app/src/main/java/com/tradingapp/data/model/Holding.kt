package com.tradingapp.data.model
data class Holding(val symbol: String, val exchange: String, val quantity: Int, val avgCost: Double, val currentPrice: Double, val currentValue: Double, val pnl: Double, val pnlPercent: Double, val instrumentToken: Long = 0) { val isProfit get() = pnl >= 0 }

package com.tradingapp.data.model
data class MutualFund(val schemeCode: String, val name: String, val category: String = "", val nav: Double = 0.0, val date: String = "", val returns3Y: Double = 0.0)
data class NavPoint(val time: String, val value: Double)

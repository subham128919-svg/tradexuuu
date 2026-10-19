package com.tradingapp.util

/** UI may enter lots; every backend request and position uses total units. */
object OrderQuantity {
    fun toUnits(input: String, lotSize: Int): Int? {
        val count = input.toLongOrNull() ?: return null
        if (count <= 0 || lotSize <= 0 || count > Int.MAX_VALUE.toLong() / lotSize) return null
        return (count * lotSize).toInt()
    }
    fun displayFromUnits(units: Long, lotSize: Int): String? {
        if (units <= 0 || units > Int.MAX_VALUE || lotSize <= 0 || units % lotSize != 0L) return null
        return (units / lotSize).toString()
    }
    fun exitSide(signedUnits: Int): String = if (signedUnits < 0) "BUY" else "SELL"
}

package com.tradingapp.data.model

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long = 0)

fun List<Candle>.toChartJson(): String {
    val sb = StringBuilder("[")
    forEachIndexed { i, c ->
        if (i > 0) sb.append(",")
        sb.append("{\"time\":").append(c.time)
        sb.append(",\"open\":").append(c.open)
        sb.append(",\"high\":").append(c.high)
        sb.append(",\"low\":").append(c.low)
        sb.append(",\"close\":").append(c.close)
        sb.append(",\"volume\":").append(c.volume).append("}")
    }
    sb.append("]")
    return sb.toString()
}

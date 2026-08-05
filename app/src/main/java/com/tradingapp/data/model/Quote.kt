package com.tradingapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// All String fields use "" default + custom getter to handle Gson setting them to null.
// Gson bypasses Kotlin's non-null type system when deserializing — it can put null
// into a String field. Using nullable types + defaults prevents the copy() NPE crash.
@Entity(tableName = "quotes")
data class Quote(
    @PrimaryKey val symbol: String = "",
    val exchange:   String? = "NSE",
    val name:       String? = "",
    val ltp:        Double  = 0.0,
    val open:       Double  = 0.0,
    val high:       Double  = 0.0,
    val low:        Double  = 0.0,
    val close:      Double  = 0.0,
    val change:     Double  = 0.0,
    @SerializedName("changePct") val changePct: Double = 0.0,
    val volume:     Long    = 0L,
    val updatedAt:  Long    = System.currentTimeMillis()
) {
    val isPositive: Boolean get() = change >= 0
    val safeExchange: String get() = exchange ?: "NSE"
    val safeName: String get() = name ?: symbol.substringAfter(":")
}

package com.tradingapp.data.model
import androidx.room.Entity; import androidx.room.PrimaryKey; import com.google.gson.annotations.SerializedName
@Entity(tableName = "quotes")
data class Quote(@PrimaryKey val symbol: String, val exchange: String = "NSE", val name: String = "", val ltp: Double = 0.0, val open: Double = 0.0, val high: Double = 0.0, val low: Double = 0.0, val close: Double = 0.0, val change: Double = 0.0, @SerializedName("changePct") val changePct: Double = 0.0, val volume: Long = 0L, val updatedAt: Long = System.currentTimeMillis()) { val isPositive get() = change >= 0 }

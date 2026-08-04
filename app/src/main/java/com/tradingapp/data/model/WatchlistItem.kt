package com.tradingapp.data.model
import androidx.room.Entity; import androidx.room.PrimaryKey
@Entity(tableName = "watchlist")
data class WatchlistItem(@PrimaryKey(autoGenerate = true) val id: Int = 0, val symbol: String, val exchange: String = "NSE", val listName: String = "My list 1", val ltp: Double = 0.0, val changePct: Double = 0.0, val addedAt: Long = System.currentTimeMillis())

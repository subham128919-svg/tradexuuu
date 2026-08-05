package com.tradingapp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// Tracks instruments the user has opened (chart view), most-recent first.
// Surfaced in the search screen ("Recently viewed") once Phase 2 (Search) lands.
@Entity(tableName = "recent_viewed")
data class RecentViewed(
    @PrimaryKey val symbol: String,   // full "EXCHANGE:SYMBOL" key
    val exchange: String,
    val name: String,
    val viewedAt: Long = System.currentTimeMillis()
)

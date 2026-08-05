package com.tradingapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tradingapp.data.model.Quote
import com.tradingapp.data.model.RecentViewed
import com.tradingapp.data.model.WatchlistItem

// Version bumped 1 -> 2 for the new recent_viewed table.
// fallbackToDestructiveMigration is acceptable pre-launch (no production
// user data yet); once real users have data, replace with a proper
// Migration(1, 2) that ADDs the table instead of wiping the DB.
@Database(
    entities = [Quote::class, WatchlistItem::class, RecentViewed::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun quoteDao(): QuoteDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun recentViewedDao(): RecentViewedDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tradingapp.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

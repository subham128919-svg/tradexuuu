package com.tradingapp.data.db
import android.content.Context; import androidx.room.*
import com.tradingapp.data.model.Quote; import com.tradingapp.data.model.WatchlistItem

@Database(entities = [Quote::class, WatchlistItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun quoteDao(): QuoteDao
    abstract fun watchlistDao(): WatchlistDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "tradingapp.db")
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

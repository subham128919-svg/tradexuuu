package com.tradingapp.data.db
import androidx.room.*; import com.tradingapp.data.model.Quote; import kotlinx.coroutines.flow.Flow

@Dao interface QuoteDao {
    @Query("SELECT * FROM quotes WHERE symbol IN (:symbols)")
    fun observeQuotes(symbols: List<String>): Flow<List<Quote>>
    @Query("SELECT * FROM quotes WHERE symbol = :symbol LIMIT 1")
    suspend fun getQuote(symbol: String): Quote?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(quotes: List<Quote>)
    @Query("DELETE FROM quotes WHERE updatedAt < :cutoff") suspend fun deleteStale(cutoff: Long)
}

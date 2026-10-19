package com.tradingapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tradingapp.data.model.Quote
import kotlinx.coroutines.flow.Flow

@Dao
interface QuoteDao {

    // Reactive — this Flow re-emits automatically whenever any row in the
    // `symbols` set changes. This is what makes prices "just move" on
    // screen: the WebSocket writes into this table (see MarketRepository),
    // and Room notifies every active observer of this query instantly.
    @Query("SELECT * FROM quotes WHERE symbol IN (:symbols)")
    fun observeQuotes(symbols: List<String>): Flow<List<Quote>>

    @Query("SELECT * FROM quotes WHERE symbol = :symbol LIMIT 1")
    fun observeQuote(symbol: String): Flow<Quote?>

    @Query("SELECT * FROM quotes WHERE symbol = :symbol LIMIT 1")
    suspend fun getQuote(symbol: String): Quote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(quotes: List<Quote>)

    @Query("SELECT * FROM quotes WHERE symbol IN (:symbols)")
    suspend fun getQuotes(symbols: List<String>): List<Quote>

    @Transaction
    suspend fun upsertFresh(quotes: List<Quote>) {
        if (quotes.isEmpty()) return
        val existing = getQuotes(quotes.map { it.symbol }).associateBy { it.symbol }
        upsert(quotes.filter { it.updatedAt >= (existing[it.symbol]?.updatedAt ?: 0L) }.map { quote ->
            quote.copy(name = quote.name ?: existing[quote.symbol]?.name)
        })
    }

    @Query("DELETE FROM quotes WHERE updatedAt < :cutoff")
    suspend fun deleteStale(cutoff: Long)
}

package com.tradingapp.data.db
import androidx.room.*; import com.tradingapp.data.model.WatchlistItem; import kotlinx.coroutines.flow.Flow

@Dao interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY listName, addedAt")
    fun observeAll(): Flow<List<WatchlistItem>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(item: WatchlistItem): Long
    @Delete suspend fun delete(item: WatchlistItem)
    @Query("DELETE FROM watchlist WHERE id = :id") suspend fun deleteById(id: Int)
}

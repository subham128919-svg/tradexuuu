package com.tradingapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tradingapp.data.model.RecentViewed
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentViewedDao {
    @Query("SELECT * FROM recent_viewed ORDER BY viewedAt DESC LIMIT 20")
    fun observeRecent(): Flow<List<RecentViewed>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentViewed)

    @Query("DELETE FROM recent_viewed WHERE symbol = :symbol")
    suspend fun remove(symbol: String)
}

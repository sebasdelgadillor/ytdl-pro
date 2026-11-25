package com.deniscerri.ytdl.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.deniscerri.ytdl.database.models.SiteSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteSessionDao {

    @Query("SELECT * FROM site_sessions ORDER BY lastUsedTimestamp DESC")
    fun getAllFlow(): Flow<List<SiteSession>>

    @Query("SELECT * FROM site_sessions WHERE isLoggedIn = 1 ORDER BY lastUsedTimestamp DESC")
    fun getLoggedInFlow(): Flow<List<SiteSession>>

    @Query("SELECT * FROM site_sessions WHERE isLoggedIn = 1 ORDER BY lastUsedTimestamp DESC")
    fun getLoggedIn(): List<SiteSession>

    @Query("SELECT * FROM site_sessions WHERE domain = :domain LIMIT 1")
    fun getByDomain(domain: String): SiteSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SiteSession): Long

    @Update
    suspend fun update(session: SiteSession)

    @Query("UPDATE site_sessions SET cookieCount = :cookieCount, lastUsedTimestamp = :timestamp WHERE domain = :domain")
    fun updateUsageSync(domain: String, cookieCount: Int, timestamp: Long)

    @Query("DELETE FROM site_sessions WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)

    @Query("DELETE FROM site_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

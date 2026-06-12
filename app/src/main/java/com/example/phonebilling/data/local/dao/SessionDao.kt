package com.example.phonebilling.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.data.local.entity.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE status = :status ORDER BY endsAt")
    fun observeSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeSession(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE deviceId = :deviceId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveSessionForDevice(deviceId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE deviceId = :deviceId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSessionForDevice(deviceId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE deviceId = :deviceId AND status = 'ACTIVE' ORDER BY startedAt DESC")
    suspend fun getActiveSessionsForDevice(deviceId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE synced = 0")
    suspend fun unsyncedSessions(): List<SessionEntity>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET synced = 1 WHERE sessionId IN (:sessionIds)")
    suspend fun markSynced(sessionIds: List<String>)
}

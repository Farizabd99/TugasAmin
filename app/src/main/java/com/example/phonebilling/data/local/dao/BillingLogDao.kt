package com.example.phonebilling.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.phonebilling.data.local.entity.BillingLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BillingLogDao {
    @Query("SELECT * FROM billing_logs ORDER BY occurredAt DESC")
    fun observeLogs(): Flow<List<BillingLogEntity>>

    @Query("SELECT * FROM billing_logs WHERE synced = 0 ORDER BY occurredAt")
    suspend fun unsyncedLogs(): List<BillingLogEntity>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM billing_logs")
    fun observeTotalRevenueCents(): Flow<Long>

    @Upsert
    suspend fun upsert(log: BillingLogEntity)

    @Query("UPDATE billing_logs SET synced = 1 WHERE logId IN (:logIds)")
    suspend fun markSynced(logIds: List<String>)
}

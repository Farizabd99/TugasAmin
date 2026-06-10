package com.example.phonebilling.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "billing_logs",
    indices = [Index("sessionId"), Index("synced")]
)
data class BillingLogEntity(
    @PrimaryKey val logId: String,
    val sessionId: String,
    val deviceId: String,
    val event: BillingEvent,
    val amountCents: Long,
    val occurredAt: Long,
    val details: String,
    val synced: Boolean
)

enum class BillingEvent { SESSION_STARTED, SESSION_EXTENDED, SESSION_STOPPED, SESSION_EXPIRED }

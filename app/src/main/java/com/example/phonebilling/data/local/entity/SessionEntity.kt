package com.example.phonebilling.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "sessions",
    indices = [Index("deviceId"), Index("status")]
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val deviceId: String,
    val tariffId: String,
    val operatorId: String,
    val status: SessionStatus,
    val startedAt: Long,
    val endsAt: Long,
    val stoppedAt: Long?,
    val extendedMinutes: Int,
    val priceCents: Long,
    val synced: Boolean
)

enum class SessionStatus { PENDING, ACTIVE, EXPIRED, STOPPED }

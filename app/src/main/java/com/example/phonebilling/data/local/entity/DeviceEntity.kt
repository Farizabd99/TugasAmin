package com.example.phonebilling.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val model: String,
    val mode: DeviceMode,
    val status: DeviceStatus,
    val serverBaseUrl: String,
    val lastSeenAt: Long,
    val createdAt: Long
)

enum class DeviceMode { OPERATOR, CLIENT }

enum class DeviceStatus { WAITING, ACTIVE, EXPIRED, OFFLINE }

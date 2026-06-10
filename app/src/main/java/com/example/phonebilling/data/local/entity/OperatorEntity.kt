package com.example.phonebilling.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "operators")
data class OperatorEntity(
    @PrimaryKey val operatorId: String,
    val username: String,
    val passwordHash: String,
    val displayName: String,
    val active: Boolean,
    val createdAt: Long
)

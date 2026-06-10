package com.example.phonebilling.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "tariffs")
data class TariffEntity(
    @PrimaryKey val tariffId: String,
    val name: String,
    val minutes: Int,
    val priceCents: Long,
    val active: Boolean
)

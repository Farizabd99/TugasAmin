package com.example.phonebilling.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.phonebilling.data.local.entity.TariffEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TariffDao {
    @Query("SELECT * FROM tariffs WHERE active = 1 ORDER BY minutes")
    fun observeActiveTariffs(): Flow<List<TariffEntity>>

    @Query("SELECT * FROM tariffs WHERE tariffId = :tariffId LIMIT 1")
    suspend fun getTariff(tariffId: String): TariffEntity?

    @Upsert
    suspend fun upsert(tariff: TariffEntity)
}

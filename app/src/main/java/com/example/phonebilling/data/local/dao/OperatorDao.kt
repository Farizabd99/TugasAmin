package com.example.phonebilling.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.phonebilling.data.local.entity.OperatorEntity

@Dao
interface OperatorDao {
    @Query("SELECT * FROM operators WHERE username = :username AND active = 1 LIMIT 1")
    suspend fun findActiveByUsername(username: String): OperatorEntity?

    @Query("SELECT COUNT(*) FROM operators")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(operator: OperatorEntity)
}

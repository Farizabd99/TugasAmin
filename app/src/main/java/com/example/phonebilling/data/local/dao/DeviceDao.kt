package com.example.phonebilling.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.phonebilling.data.local.entity.DeviceEntity
import com.example.phonebilling.data.local.entity.DeviceStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY displayName")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE status = :status ORDER BY displayName")
    fun observeDevicesByStatus(status: DeviceStatus): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId LIMIT 1")
    fun observeDevice(deviceId: String): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): DeviceEntity?

    @Upsert
    suspend fun upsert(device: DeviceEntity)
}

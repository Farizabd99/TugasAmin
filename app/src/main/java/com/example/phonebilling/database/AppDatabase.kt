package com.example.phonebilling.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.phonebilling.data.local.dao.BillingLogDao
import com.example.phonebilling.data.local.dao.DeviceDao
import com.example.phonebilling.data.local.dao.OperatorDao
import com.example.phonebilling.data.local.dao.SessionDao
import com.example.phonebilling.data.local.dao.TariffDao
import com.example.phonebilling.data.local.entity.BillingLogEntity
import com.example.phonebilling.data.local.entity.DeviceEntity
import com.example.phonebilling.data.local.entity.OperatorEntity
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.data.local.entity.TariffEntity

@Database(
    entities = [
        DeviceEntity::class,
        SessionEntity::class,
        TariffEntity::class,
        OperatorEntity::class,
        BillingLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(BillingTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun sessionDao(): SessionDao
    abstract fun tariffDao(): TariffDao
    abstract fun operatorDao(): OperatorDao
    abstract fun billingLogDao(): BillingLogDao
}

package com.example.phonebilling.database

import androidx.room.TypeConverter
import com.example.phonebilling.data.local.entity.BillingEvent
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.data.local.entity.SessionStatus

class BillingTypeConverters {
    @TypeConverter fun toDeviceMode(value: String): DeviceMode = DeviceMode.valueOf(value)
    @TypeConverter fun fromDeviceMode(value: DeviceMode): String = value.name
    @TypeConverter fun toDeviceStatus(value: String): DeviceStatus = DeviceStatus.valueOf(value)
    @TypeConverter fun fromDeviceStatus(value: DeviceStatus): String = value.name
    @TypeConverter fun toSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)
    @TypeConverter fun fromSessionStatus(value: SessionStatus): String = value.name
    @TypeConverter fun toBillingEvent(value: String): BillingEvent = BillingEvent.valueOf(value)
    @TypeConverter fun fromBillingEvent(value: BillingEvent): String = value.name
}

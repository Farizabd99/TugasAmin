package com.example.phonebilling.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("phone_billing_settings")

interface BillingSettings {
    val serverBaseUrl: Flow<String>
    val deviceId: Flow<String>
    suspend fun setServerBaseUrl(value: String)
}

@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context
) : BillingSettings {
    override val serverBaseUrl: Flow<String> = context.dataStore.data.map {
        it[SERVER_BASE_URL] ?: DEFAULT_SERVER_BASE_URL
    }

    override val deviceId: Flow<String> = context.dataStore.data.map {
        it[DEVICE_ID] ?: android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    override suspend fun setServerBaseUrl(value: String) {
        context.dataStore.edit { it[SERVER_BASE_URL] = value.trim() }
    }

    companion object {
        const val DEFAULT_SERVER_BASE_URL = "http://localhost:8080/"
        private val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        private val DEVICE_ID = stringPreferencesKey("device_id")
    }
}

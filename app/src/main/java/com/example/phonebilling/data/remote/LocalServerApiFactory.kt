package com.example.phonebilling.data.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Singleton
class LocalServerApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    private val cache = mutableMapOf<String, BillingApi>()

    fun create(baseUrl: String): BillingApi {
        val normalized = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        return cache.getOrPut(normalized) {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BillingApi::class.java)
        }
    }
}

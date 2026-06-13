package com.example.phonebilling.data.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

interface BillingApiProvider {
    fun create(baseUrl: String): BillingApi
}

@Singleton
class LocalServerApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) : BillingApiProvider {
    private val cache = mutableMapOf<String, BillingApi>()

    override fun create(baseUrl: String): BillingApi {
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

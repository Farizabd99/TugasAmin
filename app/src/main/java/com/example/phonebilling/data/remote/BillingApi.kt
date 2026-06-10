package com.example.phonebilling.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface BillingApi {
    @POST("api/devices/register")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): ApiEnvelope<DeviceDto>

    @GET("api/devices/{deviceId}/status")
    suspend fun getDeviceStatus(@Path("deviceId") deviceId: String): ApiEnvelope<DeviceStatusDto>

    @POST("api/sessions/start")
    suspend fun startSession(@Body request: StartSessionRequest): ApiEnvelope<SessionDto>

    @POST("api/sessions/{sessionId}/stop")
    suspend fun stopSession(@Path("sessionId") sessionId: String): ApiEnvelope<SessionDto>

    @POST("api/sessions/{sessionId}/extend")
    suspend fun extendSession(
        @Path("sessionId") sessionId: String,
        @Body request: ExtendSessionRequest
    ): ApiEnvelope<SessionDto>

    @POST("api/billing/logs/sync")
    suspend fun syncBillingLogs(@Body request: SyncBillingLogsRequest): ApiEnvelope<SyncResultDto>
}

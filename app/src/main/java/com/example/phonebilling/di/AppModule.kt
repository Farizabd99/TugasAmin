package com.example.phonebilling.di

import android.content.Context
import androidx.room.Room
import com.example.phonebilling.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "phone_billing.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideDeviceDao(db: AppDatabase) = db.deviceDao()
    @Provides fun provideSessionDao(db: AppDatabase) = db.sessionDao()
    @Provides fun provideTariffDao(db: AppDatabase) = db.tariffDao()
    @Provides fun provideOperatorDao(db: AppDatabase) = db.operatorDao()
    @Provides fun provideBillingLogDao(db: AppDatabase) = db.billingLogDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }
}

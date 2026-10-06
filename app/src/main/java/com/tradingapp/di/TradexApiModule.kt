package com.tradingapp.di

import com.tradingapp.data.api.TradexApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Builds TradexApi from the EXISTING Retrofit instance that
 * NetworkModule already provides, so the base URL, the JWT
 * interceptor and the OkHttp connection pool are all shared.
 *
 * NetworkModule.kt needs no changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object TradexApiModule {

    @Provides
    @Singleton
    fun provideTradexApi(retrofit: Retrofit): TradexApi =
        retrofit.create(TradexApi::class.java)
}

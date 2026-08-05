package com.tradingapp.di

import android.content.Context
import com.google.gson.GsonBuilder
import com.tradingapp.BuildConfig
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.db.AppDatabase
import com.tradingapp.data.db.QuoteDao
import com.tradingapp.data.db.RecentViewedDao
import com.tradingapp.data.db.WatchlistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // No auth interceptor — market data endpoints are public. The server
    // authenticates with Zerodha using its own stored token; app users
    // never need to log in just to view prices/charts.
    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)   // keeps the WS TCP connection alive at the OkHttp layer too
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    @Provides @Singleton
    fun provideRetrofit(okHttp: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()

    @Provides @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase = AppDatabase.getInstance(ctx)

    @Provides fun provideQuoteDao(db: AppDatabase): QuoteDao = db.quoteDao()
    @Provides fun provideWatchlistDao(db: AppDatabase): WatchlistDao = db.watchlistDao()
    @Provides fun provideRecentViewedDao(db: AppDatabase): RecentViewedDao = db.recentViewedDao()
}

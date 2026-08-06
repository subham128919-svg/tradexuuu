package com.tradingapp.di

import android.content.Context
import com.google.gson.GsonBuilder
import com.tradingapp.BuildConfig
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.db.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Attaches JWT to every request so /app-orders/positions and
    // other protected endpoints work after login.
    @Provides @Singleton
    fun provideAuthInterceptor(@ApplicationContext ctx: Context): Interceptor = Interceptor { chain ->
        val prefs = ctx.getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("jwt_token", null)
        val req   = if (!token.isNullOrEmpty())
            chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
        else chain.request()
        chain.proceed(req)
    }

    @Provides @Singleton
    fun provideOkHttp(authInterceptor: Interceptor): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
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

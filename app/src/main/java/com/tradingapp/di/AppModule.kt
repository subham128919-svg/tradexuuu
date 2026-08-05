package com.tradingapp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

// Marks the app-lifetime CoroutineScope, as opposed to a ViewModel-scoped one.
// Used by PriceWebSocket (reconnect/heartbeat loops) and MarketRepository
// (central tick-persistence pipeline) — both need to keep running regardless
// of which screen is currently visible, so they cannot use viewModelScope,
// which gets cancelled the moment a Fragment/Activity is destroyed.
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

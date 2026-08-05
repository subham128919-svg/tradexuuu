package com.tradingapp

import android.app.Application
import com.tradingapp.data.repository.MarketRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TradingApp : Application() {

    // Field injection is supported directly on the @HiltAndroidApp class.
    // Touching marketRepository here forces Hilt to construct it (and
    // start its tick-persistence + WebSocket pipeline, see
    // MarketRepository.init) as soon as the process starts — not
    // lazily on first screen load. This means reconnect/heartbeat
    // logic is live even before the user opens any particular screen.
    @Inject lateinit var marketRepository: MarketRepository

    override fun onCreate() {
        super.onCreate()
        marketRepository.hashCode() // no-op touch to force eager init
    }
}

package com.tradingapp.data.websocket

import android.util.Log; import com.google.gson.Gson; import com.tradingapp.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow; import kotlinx.coroutines.flow.SharedFlow; import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*; import javax.inject.Inject; import javax.inject.Singleton

// Tick received from backend WebSocket
data class PriceTick(val symbol: String, val ltp: Double, val change: Double, val changePct: Double, val volume: Long, val ts: Long)

@Singleton
class PriceWebSocket @Inject constructor(private val okHttpClient: OkHttpClient) {

    private val gson   = Gson()
    private var socket: WebSocket? = null
    private val subscribedSymbols = mutableSetOf<String>()

    private val _ticks = MutableSharedFlow<PriceTick>(replay = 0, extraBufferCapacity = 64)
    val ticks: SharedFlow<PriceTick> = _ticks.asSharedFlow()

    fun connect() {
        if (socket != null) return
        val request = Request.Builder().url(BuildConfig.WS_URL).build()
        socket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d("WS", "Connected")
                if (subscribedSymbols.isNotEmpty()) sendSubscribe(subscribedSymbols.toList())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val map = gson.fromJson(text, Map::class.java)
                    if (map["type"] != "tick") return
                    val tick = PriceTick(
                        symbol    = map["symbol"] as? String ?: return,
                        ltp       = (map["ltp"] as? Double) ?: 0.0,
                        change    = (map["change"] as? Double) ?: 0.0,
                        changePct = (map["changePct"] as? String)?.toDouble() ?: 0.0,
                        volume    = (map["volume"] as? Double)?.toLong() ?: 0L,
                        ts        = (map["ts"] as? Double)?.toLong() ?: 0L
                    )
                    _ticks.tryEmit(tick)
                } catch (e: Exception) { Log.e("WS", "Parse error", e) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e("WS", "Failure: " + t.message)
                socket = null
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { socket = null }
        })
    }

    fun subscribe(symbols: List<String>) {
        subscribedSymbols.addAll(symbols)
        sendSubscribe(symbols)
    }

    fun unsubscribe(symbols: List<String>) {
        subscribedSymbols.removeAll(symbols.toSet())
        socket?.send(gson.toJson(mapOf("action" to "unsubscribe", "symbols" to symbols)))
    }

    private fun sendSubscribe(symbols: List<String>) {
        socket?.send(gson.toJson(mapOf("action" to "subscribe", "symbols" to symbols)))
    }

    fun disconnect() { socket?.close(1000, "User navigated away"); socket = null }
}

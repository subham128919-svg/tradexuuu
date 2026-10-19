package com.tradingapp.data.websocket

import android.util.Log
import com.google.gson.Gson
import com.tradingapp.BuildConfig
import com.tradingapp.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

data class PriceTick(
    val symbol: String,
    val ltp: Double,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val close: Double = 0.0,
    val change: Double = 0.0,
    val changePct: Double = 0.0,
    val volume: Long = 0L,
    val ts: Long = System.currentTimeMillis()
)

sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Disconnected(val reason: String) : ConnectionState()
    data class Degraded(val reason: String) : ConnectionState()
}

// ─────────────────────────────────────────────────────────────────
//  PriceWebSocket — production-grade WebSocket client
//
//  What this fixes vs. the old version:
//   1. Reconnects automatically with exponential backoff (1s→2s→4s→8s→
//      16s, capped at 30s + jitter) instead of dying silently on any
//      network blip, screen lock, or server restart.
//   2. Heartbeat (ping every 20s) detects "zombie" connections — sockets
//      that look open but stopped receiving data — and forces a
//      reconnect instead of leaving the UI stuck with stale prices.
//   3. Subscribed symbols survive reconnects: after any reconnect, every
//      symbol the app cared about is automatically re-subscribed.
//   4. Connection state is observable (StateFlow) so the UI can show a
//      "Reconnecting…" indicator instead of silently going stale.
// ─────────────────────────────────────────────────────────────────
@Singleton
class PriceWebSocket @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val gson = Gson()
    private var socket: WebSocket? = null

    // FIX: the injected okHttpClient is shared with Retrofit and has
    // readTimeout(15s) — OkHttp applies that same readTimeout to
    // WebSocket connections (this is a well-known OkHttp gotcha, see
    // square/okhttp#1930). Since it's shorter than pingInterval(20s),
    // any 15s gap with no incoming frame (a closed-market lull, a slow
    // tick cycle, backgrounding) throws SocketTimeoutException and
    // kills the socket *before* the next scheduled ping/pong can save
    // it — the socket then reconnects, drops again ~15s later, and
    // repeats. That reconnect loop is what "live data isn't updating"
    // looks like from the outside. A dedicated client with readTimeout
    // disabled makes the ping/pong + our own heartbeat the only things
    // that decide liveness, which is what they're there for.
    private val wsClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Symbols the app wants live data for. Persists across reconnects —
    // this is the whole point: the caller shouldn't have to re-subscribe
    // after a network blip, the socket layer handles that transparently.
    private val subscriptions = SubscriptionRegistry()

    private val userDisconnected = AtomicBoolean(true)
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastPongAt = System.currentTimeMillis()

    private val _ticks = MutableSharedFlow<PriceTick>(replay = 0, extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val ticks: SharedFlow<PriceTick> = _ticks.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Synchronized
    fun connect() {
        userDisconnected.set(false)
        if (socket != null || reconnectJob?.isActive == true) return
        openSocket()
    }

    @Synchronized private fun openSocket() {
        if (socket != null || userDisconnected.get()) return
        _connectionState.value =
            if (reconnectAttempt > 0) ConnectionState.Reconnecting(reconnectAttempt)
            else ConnectionState.Connecting
        val request = Request.Builder().url(BuildConfig.WS_URL).build()
        socket = wsClient.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@PriceWebSocket) {
            if (webSocket !== socket) return@synchronized
            Log.d(TAG, "Connected")
            reconnectAttempt = 0
            lastPongAt = System.currentTimeMillis()
            _connectionState.value = ConnectionState.Connected
            val symbols = subscriptions.symbols()
            if (symbols.isNotEmpty()) sendSubscribe(symbols)
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            try {
                @Suppress("UNCHECKED_CAST")
                val map = gson.fromJson(text, Map::class.java) as Map<String, Any?>
                when (map["type"]) {
                    "pong" -> lastPongAt = System.currentTimeMillis()
                    "tick" -> parseTick(map)?.let { _ticks.tryEmit(it) }
                    "error" -> _connectionState.value = ConnectionState.Degraded(map["message"]?.toString() ?: "Price subscription failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Failure: ${t.message}")
            handleDisconnect(webSocket, "failure: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed: $reason")
            handleDisconnect(webSocket, "closed: $reason")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
    }

    private fun parseTick(map: Map<String, Any?>): PriceTick? {
        val symbol = map["symbol"] as? String ?: return null
        fun num(key: String): Double = when (val v = map[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        return PriceTick(
            symbol    = symbol,
            ltp       = num("ltp"),
            open      = num("open"),
            high      = num("high"),
            low       = num("low"),
            close     = num("close"),
            change    = num("change"),
            changePct = num("changePct"),
            volume    = num("volume").toLong(),
            // Backend (priceStream.js / marketDataService.js) sends this
            // field as "updatedAt", not "ts" — this was always missing,
            // silently falling back to local receipt time every tick.
            // Harmless in practice (receipt time is a fine proxy) but not
            // what was intended; "ts" kept as a fallback for safety.
            ts        = ((map["updatedAt"] ?: map["ts"]) as? Number)?.toLong() ?: 0L
        )
    }

    @Synchronized private fun handleDisconnect(expected: WebSocket?, reason: String) {
        if (expected !== socket) return
        stopHeartbeat()
        socket = null
        _connectionState.value = ConnectionState.Disconnected(reason)
        if (!userDisconnected.get()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempt++
        val backoffMs = min(30_000L, 1000L * 2.0.pow(min(reconnectAttempt, 5)).toLong())
        val jitterMs  = (0..500).random()
        reconnectJob = appScope.launch {
            _connectionState.value = ConnectionState.Reconnecting(reconnectAttempt)
            Log.d(TAG, "Reconnecting in ${backoffMs + jitterMs}ms (attempt $reconnectAttempt)")
            delay(backoffMs + jitterMs)
            if (!userDisconnected.get()) openSocket()
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = appScope.launch {
            while (isActive) {
                delay(20_000)
                val silentForMs = System.currentTimeMillis() - lastPongAt
                if (silentForMs > 45_000) {
                    Log.w(TAG, "Heartbeat timeout ($silentForMs ms) — forcing reconnect")
                    val expired = socket
                    handleDisconnect(expired, "heartbeat timeout")
                    expired?.cancel()
                    break
                }
                socket?.send(gson.toJson(mapOf("action" to "ping")))
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    @Synchronized fun subscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val added = subscriptions.acquire(symbols)
        connect()
        if ((_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Degraded) && added.isNotEmpty()) sendSubscribe(added)
    }

    @Synchronized fun unsubscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val removed = subscriptions.release(symbols)
        if (removed.isNotEmpty()) socket?.send(gson.toJson(mapOf("action" to "unsubscribe", "symbols" to removed)))
    }

    private fun sendSubscribe(symbols: List<String>) {
        socket?.send(gson.toJson(mapOf("action" to "subscribe", "symbols" to symbols)))
    }

    // Explicit user/app-triggered disconnect — reconnect loop will NOT
    // restart until connect() is called again.
    @Synchronized fun disconnect() {
        userDisconnected.set(true)
        reconnectJob?.cancel()
        stopHeartbeat()
        val previous = socket
        socket = null
        previous?.close(1000, "app disconnect")
        _connectionState.value = ConnectionState.Idle
    }

    companion object {
        private const val TAG = "PriceWebSocket"
    }
}

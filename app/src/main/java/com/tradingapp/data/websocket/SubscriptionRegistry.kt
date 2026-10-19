package com.tradingapp.data.websocket

/** One reference per screen; changing one screen cannot remove another's feed. */
class SubscriptionRegistry {
    private val counts = mutableMapOf<String, Int>()
    @Synchronized fun acquire(symbols: List<String>): List<String> = symbols.distinct().filter { symbol ->
        val previous = counts[symbol] ?: 0
        counts[symbol] = previous + 1
        previous == 0
    }
    @Synchronized fun release(symbols: List<String>): List<String> = symbols.distinct().filter { symbol ->
        val previous = counts[symbol] ?: return@filter false
        if (previous == 1) { counts.remove(symbol); true } else { counts[symbol] = previous - 1; false }
    }
    @Synchronized fun symbols(): List<String> = counts.keys.toList()
}

package com.tradingapp.data.websocket

import org.junit.Assert.*
import org.junit.Test

class SubscriptionRegistryTest {
    @Test fun anotherScreenKeepsItsSubscription() {
        val registry = SubscriptionRegistry()
        assertEquals(listOf("NSE:ABC"), registry.acquire(listOf("NSE:ABC")))
        assertTrue(registry.acquire(listOf("NSE:ABC")).isEmpty())
        assertTrue(registry.release(listOf("NSE:ABC")).isEmpty())
        assertEquals(listOf("NSE:ABC"), registry.symbols())
        assertEquals(listOf("NSE:ABC"), registry.release(listOf("NSE:ABC")))
        assertTrue(registry.symbols().isEmpty())
    }
    @Test fun duplicateSymbolsAndRepeatedCleanupDoNotLeakOrUnderflow() {
        val registry = SubscriptionRegistry()
        assertEquals(listOf("BFO:OPT"), registry.acquire(listOf("BFO:OPT", "BFO:OPT")))
        assertEquals(listOf("BFO:OPT"), registry.release(listOf("BFO:OPT", "BFO:OPT")))
        assertTrue(registry.release(listOf("BFO:OPT")).isEmpty())
        assertEquals(listOf("BFO:OPT"), registry.acquire(listOf("BFO:OPT")))
    }
}

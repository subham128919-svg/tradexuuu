package com.tradingapp.util

import org.junit.Assert.*
import org.junit.Test

class OrderQuantityTest {
    @Test fun exitQuantityIsConvertedFromUnitsExactlyOnce() {
        assertEquals("1", OrderQuantity.displayFromUnits(50, 50))
        assertEquals(50, OrderQuantity.toUnits("1", 50))
        assertEquals("2", OrderQuantity.displayFromUnits(100, 50))
        assertEquals(100, OrderQuantity.toUnits("2", 50))
    }
    @Test fun shortExitBuysAndLongExitSells() {
        assertEquals("BUY", OrderQuantity.exitSide(-50))
        assertEquals("SELL", OrderQuantity.exitSide(50))
    }
    @Test fun invalidAndOverflowingQuantitiesCannotBeSubmitted() {
        assertNull(OrderQuantity.toUnits("2147483647", 50))
        assertNull(OrderQuantity.toUnits("0", 50))
        assertNull(OrderQuantity.toUnits("-1", 50))
        assertNull(OrderQuantity.toUnits("1.5", 50))
        assertNull(OrderQuantity.displayFromUnits(75, 50))
        assertNull(OrderQuantity.toUnits("1", 0))
    }
}

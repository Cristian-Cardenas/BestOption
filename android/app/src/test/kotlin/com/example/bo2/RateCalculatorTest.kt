package com.example.bo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateCalculatorTest {
    private val min = 1300.0
    private val max = 1500.0

    @Test
    fun usesPickupPlusTripDistance() {
        // Valor de imagen: 11.030 / (1,1 + 6,6) km.
        assertEquals(1432.47, RateCalculator.perKm(11030.0, 7.7)!!, 0.01)

        // Recogida en metros: 661 m + 6,6 km = 7,261 km.
        assertEquals(1519.08, RateCalculator.perKm(11030.0, 7.261)!!, 0.01)

        // Capturas anteriores con la distancia total.
        assertEquals(937.0, RateCalculator.perKm(7215.0, 7.7)!!, 0.5)
        assertEquals(1375.0, RateCalculator.perKm(12375.0, 9.0)!!, 0.5)
        assertEquals(1386.5, RateCalculator.perKm(11924.0, 8.6)!!, 0.5)
    }

    @Test
    fun classifiesAtTheStandardThresholds() {
        assertEquals("MALO", RateCalculator.classify(1299.0, min, max))
        assertEquals("REGULAR", RateCalculator.classify(1300.0, min, max))
        assertEquals("REGULAR", RateCalculator.classify(1400.0, min, max))
        assertEquals("REGULAR", RateCalculator.classify(1500.0, min, max))
        assertEquals("BUENO", RateCalculator.classify(1501.0, min, max))
    }

    @Test
    fun screenshotVerdictsWithNewThresholds() {
        assertEquals("MALO", RateCalculator.classify(937.0, min, max))
        assertEquals("REGULAR", RateCalculator.classify(1375.0, min, max))
        assertEquals("REGULAR", RateCalculator.classify(1386.5, min, max))
        assertEquals("REGULAR", RateCalculator.classify(1432.47, min, max))
    }

    @Test
    fun rejectsMissingOrZeroDistance() {
        assertNull(RateCalculator.perKm(10000.0, null))
        assertNull(RateCalculator.perKm(10000.0, 0.0))
        assertNull(RateCalculator.perKm(0.0, 5.0))
    }
}

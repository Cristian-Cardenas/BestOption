package com.example.bo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RateCalculatorTest {
    private val min = 1300.0
    private val max = 1500.0

    @Test
    fun usesTripDistanceOnly() {
        // Valores de las capturas: precio / km del trayecto, no / recogida + trayecto.
        assertEquals(1244.0, RateCalculator.perKm(7215.0, 5.8)!!, 0.5)
        assertEquals(1743.0, RateCalculator.perKm(12375.0, 7.1)!!, 0.5)
        assertEquals(2021.0, RateCalculator.perKm(11924.0, 5.9)!!, 0.5)

        // La suma vieja (recogida + viaje) daba estos valores erróneos.
        assertEquals(937.0, RateCalculator.perKm(7215.0, 7.7)!!, 0.5)
        assertEquals(1375.0, RateCalculator.perKm(12375.0, 9.0)!!, 0.5)
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
        assertEquals("MALO", RateCalculator.classify(1244.0, min, max))
        assertEquals("BUENO", RateCalculator.classify(1743.0, min, max))
        assertEquals("BUENO", RateCalculator.classify(2021.0, min, max))
    }

    @Test
    fun rejectsMissingOrZeroDistance() {
        assertNull(RateCalculator.perKm(10000.0, null))
        assertNull(RateCalculator.perKm(10000.0, 0.0))
        assertNull(RateCalculator.perKm(0.0, 5.0))
    }
}

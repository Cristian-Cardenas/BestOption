package com.example.bo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistanceParserTest {
    @Test
    fun parsesKilometers() {
        assertEquals(1.1, DistanceParser.km("A 5 min (1,1 km)")!!, 0.001)
        assertEquals(6.6, DistanceParser.km("Viaje: 23 min (6.6 km)")!!, 0.001)
    }

    @Test
    fun parsesMetersAsFractionOfKilometer() {
        assertEquals(0.661, DistanceParser.km("A 2 min (661 m)")!!, 0.001)
        assertEquals(0.661, DistanceParser.km("A 2 min (661m)")!!, 0.001)
    }

    @Test
    fun ignoresMinutesWithoutDistance() {
        assertNull(DistanceParser.km("5 min"))
    }
}

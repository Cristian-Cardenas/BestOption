package com.example.bo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BarrioIndexTest {
    private fun syntheticIndex() = BarrioIndex(
        sequenceOf(
            "B|La Flora|02",
            "B|Altos de Menga|02",
            "B|Calima|04",
            "B|La Fortaleza|11",
            "P|CARRERA 4A NORTE|64|Calima|04",
            "P|CARRERA 29B|32|La Fortaleza|11",
            "P|CALLE 49 NORTE|8|La Flora|02",
            "V|CALLE 49 NORTE|Altos de Menga|02|2",
            "V|CALLE 49 NORTE|La Flora|02|2"
        )
    )

    @Test
    fun resolvesTheThreeScreenshotAddresses() {
        val index = syntheticIndex()
        assertEquals(
            BarrioIndex.Entry("Calima", "04"),
            index.resolve("Carrera 4a Nte. #64 - 102, Comuna 4")
        )
        assertEquals(
            BarrioIndex.Entry("La Fortaleza", "11"),
            index.resolve("Carrera 29 B #32-82, Comuna 11")
        )
        assertEquals(
            BarrioIndex.Entry("La Flora", "02"),
            index.resolve("Calle 49 Norte #8 Norte-75, Comuna 2")
        )
    }

    @Test
    fun doesNotInventABarrioWhenViaFallbackTies() {
        val index = syntheticIndex()
        assertNull(index.resolve("Calle 49 Norte #30-10, Comuna 2"))
    }

    @Test
    fun resolvesAgainstTheRealBundledAsset() {
        val file = File("src/main/assets/cali_barrios_index.txt")
        assumeTrue(file.exists())
        val index = BarrioIndex(file.readLines().asSequence())

        assertEquals("Calima", index.resolve("Carrera 4a Nte. #64 - 102, Comuna 4")?.barrio)
        assertEquals("La Fortaleza", index.resolve("Carrera 29 B #32-82, Comuna 11")?.barrio)
        assertEquals("La Flora", index.resolve("Calle 49 Norte #8 Norte-75, Comuna 2")?.barrio)

        // Los nombres de negocio no son direcciones: se muestran como texto, sin barrio.
        assertNull(index.resolve("Mantra Rooftop, Comuna 2"))
        assertNull(index.resolve("Panadería El Paisa, Comuna 11"))
    }
}

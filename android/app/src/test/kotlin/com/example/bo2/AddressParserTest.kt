package com.example.bo2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressParserTest {
    @Test
    fun expandsDirectionalAbbreviationAndPlate() {
        val p = AddressParser.parse("Carrera 4a Nte. #64 - 102, Comuna 4")!!
        assertEquals("CARRERA 4A NORTE", p.via)
        assertEquals("64", p.cross)
        assertEquals("102", p.plate)
    }

    @Test
    fun joinsSeparatedLetterSuffixToVia() {
        val p = AddressParser.parse("Carrera 29 B #32-82, Comuna 11")!!
        assertEquals("CARRERA 29B", p.via)
        assertEquals("32", p.cross)
        assertEquals("82", p.plate)
    }

    @Test
    fun parsesNorthernCrossWithoutBreakingPlate() {
        val p = AddressParser.parse("Calle 49 Norte #8 Norte-75, Comuna 2")!!
        assertEquals("CALLE 49 NORTE", p.via)
        assertEquals("8", p.cross)
    }

    @Test
    fun normalizesComunaHint() {
        assertEquals("02", AddressParser.comunaHint("Calle 49 Norte #8 Norte-75, Comuna 2"))
        assertEquals("11", AddressParser.comunaHint("Carrera 29 B #32-82, comuna 11"))
        assertNull(AddressParser.comunaHint("Mantra Rooftop"))
    }
}

package com.example.bo2

/** Convierte distancias del OCR a kilómetros, incluyendo valores en metros. */
internal object DistanceParser {
    private val KM_VALUE_REGEX = Regex("""(?i)([0-9loIO]+(?:[.,][0-9loIO]+)?)\s*km""")
    private val METERS_VALUE_REGEX = Regex("""(?i)([0-9loIO]+(?:[.,][0-9loIO]+)?)\s*m\b""")

    fun km(text: String): Double? {
        val kmMatch = KM_VALUE_REGEX.find(text)
        if (kmMatch != null) {
            return ocrNumber(kmMatch.groupValues[1]).replace(",", ".").toDoubleOrNull()
        }
        val metersMatch = METERS_VALUE_REGEX.find(text) ?: return null
        val meters = ocrNumber(metersMatch.groupValues[1]).replace(",", ".").toDoubleOrNull() ?: return null
        return meters / 1000.0
    }

    private fun ocrNumber(s: String): String =
        s.replace('l', '1').replace('I', '1').replace('O', '0').replace('o', '0')
}

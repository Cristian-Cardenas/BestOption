package com.example.bo2

import java.text.Normalizer
import java.util.Locale

/**
 * Normalización y separación de direcciones de Cali.
 *
 * Se mantiene independiente de Android para poder probarla con JUnit.
 */
internal object AddressParser {
    data class Parsed(val via: String, val cross: String?, val plate: String?)

    private val VIAS = setOf("CALLE", "CARRERA", "AVENIDA", "DIAGONAL", "TRANSVERSAL", "AUTOPISTA")
    private val DIRS = setOf("NORTE", "SUR", "ESTE", "OESTE", "NORESTE", "NOROESTE", "SURESTE", "SUROESTE")

    private val ABBR = listOf(
        Regex("""\b(CLL|CLLE|CL|CI|C1)\b""") to "CALLE",
        Regex("""\b(CRA|CR|CARR|KRA|KR)\b""") to "CARRERA",
        Regex("""\b(AV|AVDA)\b""") to "AVENIDA",
        Regex("""\b(DG|DIAG)\b""") to "DIAGONAL",
        Regex("""\b(TV|TR|TRANS)\b""") to "TRANSVERSAL",
        Regex("""\b(AUTOP)\b""") to "AUTOPISTA",
        Regex("""\b(EI|E1)\b""") to "EL",
        // Direcciones que el OCR suele abreviar.
        Regex("""\b(NTE|NORT)\b""") to "NORTE",
        Regex("""\b(N)\b""") to "NORTE",
        Regex("""\b(S)\b""") to "SUR",
        Regex("""\b(E)\b""") to "ESTE",
        Regex("""\b(O)\b""") to "OESTE"
    )
    private val PUNCT = Regex("""[#.,;:_()\-/]+""")
    private val SPACES = Regex("""\s+""")
    private val DIACRITICS = Regex("""[\u0300-\u036f]""")
    private val NUM_PREFIX = Regex("""^(\d+(?:\.\d+)?)""")
    private val LEADING_ZEROS = Regex("""^0+(?=\d)""")
    private val COMUNA_HINT = Regex("""(?i)\bcomuna\s*(\d{1,2})""")

    fun normalize(s: String): String {
        var t = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .uppercase(Locale.ROOT)
        for ((re, replacement) in ABBR) t = t.replace(re, replacement)
        t = t.replace(PUNCT, " ")
        return t.replace(SPACES, " ").trim()
    }

    /**
     * Acepta formatos como:
     *   Carrera 4a Nte. #64-102
     *   Carrera 29 B #32-82
     *   Calle 49 Norte #8 Norte-75
     */
    fun parse(raw: String): Parsed? {
        val t = normalize(raw).split(' ').filter { it.isNotEmpty() }
        var i = 0
        if (t.isEmpty() || t[0] !in VIAS) return null
        var via = t[i++]
        if (i >= t.size || !t[i][0].isDigit()) return null
        via += " " + t[i++]

        // Une el sufijo cuando el OCR lo separa: "29 B" -> "29B".
        if (i < t.size && isLetterSuffix(t[i])) {
            val next = t.getOrNull(i + 1)
            if (next != null && (next[0].isDigit() || next in DIRS)) {
                via += t[i++]
            }
        }
        while (i < t.size && t[i] in DIRS) via += " " + t[i++]
        while (i < t.size && t[i] == "BIS") {
            via += " BIS"
            i++
        }

        var cross: String? = null
        var plate: String? = null
        if (i < t.size && t[i][0].isDigit()) cross = t[i++]
        if (cross != null && i < t.size && isLetterSuffix(t[i])) {
            val next = t.getOrNull(i + 1)
            if (next != null && (next[0].isDigit() || next in DIRS)) {
                cross += t[i++]
            }
        }
        while (i < t.size && cross != null && t[i] == "BIS") {
            cross += " BIS"
            i++
        }
        if (i < t.size && t[i][0].isDigit()) plate = canonicalPlate(t[i++])
        return Parsed(via, cross, plate)
    }

    fun numericPrefix(s: String): Double? =
        NUM_PREFIX.find(s)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Comuna normalizada a dos dígitos ("2" -> "02"). */
    fun comunaHint(raw: String): String? =
        COMUNA_HINT.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.toString()?.padStart(2, '0')

    private fun isLetterSuffix(s: String): Boolean =
        s.length == 1 && s[0].isLetter()

    private fun canonicalPlate(s: String): String = s.replace(LEADING_ZEROS, "")
}

package com.example.bo2

import android.content.Context
import android.util.Log
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Índice offline de barrios de Cali.
 *
 * Fuente: capa de nomenclatura de Cali (300.000 direcciones) cruzada espacialmente
 * contra los polígonos de barrios de Cali (337 barrios). Se empaqueta como asset.
 *
 * Formato del asset:
 *   B|barrio|comuna
 *   P|via|cruce|barrio|comuna
 *   O|via|cruce|placa|barrio|comuna
 *
 * P = resultado mayoritario para una esquina (vía + cruce).
 * O = excepciones por placa cuando una esquina cambia de barrio.
 */
class BarrioIndex(context: Context) {
    data class Entry(val barrio: String, val comuna: String)

    private data class NameEntry(val key: String, val entry: Entry)

    private val pairs = HashMap<String, Entry>(24000)
    private val overrides = HashMap<String, Entry>(8000)
    private val names = ArrayList<NameEntry>()
    private val viaCrosses = HashMap<String, MutableList<String>>(6000)

    init {
        try {
            context.assets.open("cali_barrios_index.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val p = line.split('|')
                    when (p[0]) {
                        "B" -> if (p.size >= 3) {
                            addName(p[1], p[2])
                        }
                        "P" -> if (p.size >= 5) {
                            val entry = Entry(p[3], p[4])
                            pairs[p[1] + "|" + p[2]] = entry
                            viaCrosses.getOrPut(p[1]) { ArrayList() }.add(p[2])
                        }
                        "O" -> if (p.size >= 6) {
                            overrides[p[1] + "|" + p[2] + "|" + p[3]] = Entry(p[4], p[5])
                        }
                    }
                }
            }
            Log.i(TAG, "BarrioIndex: ${pairs.size} esquinas, ${overrides.size} placas, ${names.size} barrios")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo cargar el índice de barrios", e)
        }
    }

    /** true si la línea parece una dirección o contiene un barrio conocido. */
    fun looksLikeAddress(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (PURE_COMUNA.matches(t)) return false
        if (ADDRESS_RE.containsMatchIn(t)) return true
        if (HASH_RE.containsMatchIn(t)) return true
        if (COMUNA_RE.containsMatchIn(t)) return true
        return findName(t) != null
    }

    /** Resuelve una dirección OCR a barrio/comuna, o null si no hay coincidencia. */
    fun resolve(raw: String): Entry? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val p = parse(text)
        if (p != null) {
            if (p.plate != null) {
                overrides[p.via + "|" + p.cross + "|" + p.plate]?.let { return it }
            }
            if (p.cross != null) {
                pairs[p.via + "|" + p.cross]?.let { return it }
                nearestCross(p)?.let { return it }
            }
        }
        return findName(text)?.entry
    }

    private fun nearestCross(p: Parsed): Entry? {
        val want = numericPrefix(p.cross ?: return null) ?: return null
        var bestCross: String? = null
        var bestDist = Double.MAX_VALUE
        for (cross in viaCrosses[p.via] ?: return null) {
            val v = numericPrefix(cross) ?: continue
            val d = abs(v - want)
            if (d < bestDist) {
                bestDist = d
                bestCross = cross
            }
        }
        if (bestCross == null || bestDist > MAX_NEAREST_CROSS) return null
        return pairs[p.via + "|" + bestCross]
    }

    private fun findName(text: String): NameEntry? {
        val n = normalize(text)
        if (n.isEmpty()) return null
        var best: NameEntry? = null
        var bestAt = Int.MAX_VALUE
        for (name in names) {
            if (name.key.length < MIN_NAME_LENGTH) continue
            var from = 0
            while (true) {
                val at = n.indexOf(name.key, from)
                if (at < 0) break
                if (isWordBoundary(n, at, name.key.length) && at < bestAt) {
                    best = name
                    bestAt = at
                }
                from = at + 1
            }
        }
        return best
    }

    // Agrega el nombre completo y un alias sin prefijos ("Urbanización Ciudad Jardín" → "Ciudad Jardín").
    private fun addName(display: String, comuna: String) {
        val entry = Entry(display, comuna)
        names.add(NameEntry(normalize(display), entry))
        val cleaned = display.replace(PREFIX, " ").replace(SPACES, " ").trim()
        if (cleaned != display && cleaned.length >= MIN_NAME_LENGTH) {
            names.add(NameEntry(normalize(cleaned), entry))
        }
    }

    private fun isWordBoundary(s: String, at: Int, len: Int): Boolean {
        val before = if (at > 0) s[at - 1] else ' '
        val after = if (at + len < s.length) s[at + len] else ' '
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }

    private data class Parsed(val via: String, val cross: String?, val plate: String?)

    private fun parse(raw: String): Parsed? {
        val t = normalize(raw).split(' ')
        var i = 0
        if (t.isEmpty() || t[0] !in VIAS) return null
        var via = t[i++]
        if (i >= t.size || t[i].isEmpty() || !t[i][0].isDigit()) return null
        via += " " + t[i++]
        while (i < t.size && t[i] in DIRS) {
            via += " " + t[i++]
        }
        while (i < t.size && t[i] == "BIS") {
            via += " BIS"
            i++
        }
        var cross: String? = null
        var plate: String? = null
        if (i < t.size && t[i].isNotEmpty() && t[i][0].isDigit()) cross = t[i++]
        while (i < t.size && cross != null && t[i] == "BIS") {
            cross += " BIS"
            i++
        }
        if (i < t.size && t[i].isNotEmpty() && t[i][0].isDigit()) plate = canonicalPlate(t[i])
        return Parsed(via, cross, plate)
    }

    companion object {
        private const val TAG = "BO2Barrio"
        private const val MIN_NAME_LENGTH = 4
        private const val MAX_NEAREST_CROSS = 12.0

        private val VIAS = setOf("CALLE", "CARRERA", "AVENIDA", "DIAGONAL", "TRANSVERSAL", "AUTOPISTA")
        private val DIRS = setOf("NORTE", "SUR", "ESTE", "OESTE", "NORESTE", "NOROESTE", "SURESTE", "SUROESTE")

        private val ADDRESS_RE = Regex("""(?i)\b(calle|carrera|avenida|diagonal|transversal|autopista|cl|ci|c1|cra|kr|av|dg|tv)\b\.?\s*\d""")
        private val HASH_RE = Regex("""#\s*\d""")
        private val COMUNA_RE = Regex("""(?i)\bcomuna\s*\d{1,2}""")
        private val PURE_COMUNA = Regex("""(?i)^comuna\s*\d{1,2}$""")
        private val PREFIX = Regex("""(?i)\b(urbanización|urbanizacion|sector|barrio|ciudadela|unidad residencial|urb\.?)\b""")

        private val ABBR = listOf(
            Regex("""\b(CLL|CLLE|CL|CI|C1)\b""") to "CALLE",
            Regex("""\b(CRA|CR|CARR|KRA|KR)\b""") to "CARRERA",
            Regex("""\b(AV|AVDA)\b""") to "AVENIDA",
            Regex("""\b(DG|DIAG)\b""") to "DIAGONAL",
            Regex("""\b(TV|TR|TRANS)\b""") to "TRANSVERSAL",
            Regex("""\b(AUTOP)\b""") to "AUTOPISTA",
            Regex("""\b(EI|E1)\b""") to "EL"
        )
        private val PUNCT = Regex("""[#.,;:_()\-/]+""")
        private val SPACES = Regex("""\s+""")
        private val DIACRITICS = Regex("""[\u0300-\u036f]""")
        private val NUM_PREFIX = Regex("""^(\d+(?:\.\d+)?)""")
        private val LEADING_ZEROS = Regex("""^0+(?=\d)""")

        private fun normalize(s: String): String {
            var t = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(DIACRITICS, "")
                .uppercase(Locale.ROOT)
            for ((re, replacement) in ABBR) t = t.replace(re, replacement)
            t = t.replace(PUNCT, " ")
            return t.replace(SPACES, " ").trim()
        }

        private fun canonicalPlate(s: String): String = s.replace(LEADING_ZEROS, "")

        private fun numericPrefix(s: String): Double? =
            NUM_PREFIX.find(s)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}

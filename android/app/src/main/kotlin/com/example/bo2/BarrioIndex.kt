package com.example.bo2

import android.content.Context
import android.util.Log
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
 *   V|via|barrio|comuna|conteo
 *
 * P = resultado mayoritario para una esquina (vía + cruce).
 * O = excepciones por placa cuando una esquina cambia de barrio.
 * V = barrios observados sobre una vía, para direcciones sin cruce en el índice.
 */
class BarrioIndex private constructor() {
    data class Entry(val barrio: String, val comuna: String)

    private data class NameEntry(val key: String, val entry: Entry)

    private val pairs = HashMap<String, Entry>(24000)
    private val overrides = HashMap<String, Entry>(8000)
    private val names = ArrayList<NameEntry>()
    private val viaCrosses = HashMap<String, MutableList<String>>(6000)
    private val viaStats = HashMap<String, MutableList<Pair<Entry, Int>>>(6000)

    constructor(context: Context) : this() {
        try {
            context.assets.open("cali_barrios_index.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                load(lines)
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo cargar el índice de barrios", e)
        }
    }

    /** Constructor para pruebas unitarias, sin Android. */
    internal constructor(lines: Sequence<String>) : this() {
        load(lines)
    }

    private fun load(lines: Sequence<String>) {
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
                "V" -> if (p.size >= 5) {
                    val count = p[4].toIntOrNull() ?: 1
                    viaStats.getOrPut(p[1]) { ArrayList() }.add(Entry(p[2], p[3]) to count)
                }
            }
        }
        Log.i(
            TAG,
            "BarrioIndex: ${pairs.size} esquinas, ${overrides.size} placas, " +
                "${names.size} barrios, ${viaStats.size} vías"
        )
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
        val p = AddressParser.parse(text)
        if (p != null) {
            if (p.plate != null) {
                overrides[p.via + "|" + p.cross + "|" + p.plate]?.let { return it }
            }
            if (p.cross != null) {
                pairs[p.via + "|" + p.cross]?.let { return it }
                nearestCross(p)?.let { return it }
            }
            viaFallback(p, text)?.let { return it }
        }
        return findName(text)?.entry
    }

    private fun nearestCross(p: AddressParser.Parsed): Entry? {
        val want = AddressParser.numericPrefix(p.cross ?: return null) ?: return null
        var bestCross: String? = null
        var bestDist = Double.MAX_VALUE
        for (cross in viaCrosses[p.via] ?: return null) {
            val v = AddressParser.numericPrefix(cross) ?: continue
            val d = abs(v - want)
            if (d < bestDist) {
                bestDist = d
                bestCross = cross
            }
        }
        if (bestCross == null || bestDist > MAX_NEAREST_CROSS) return null
        return pairs[p.via + "|" + bestCross]
    }

    /**
     * Cuando la vía existe pero el cruce no está indexado (por ejemplo, una
     * dirección de Calle 49 Norte con Carrera 8 Norte), se usa el barrio
     * mayoritario de esa vía. Solo se acepta si la comuna del texto coincide,
     * para no asignar un barrio equivocado.
     */
    private fun viaFallback(p: AddressParser.Parsed, raw: String): Entry? {
        val stats = viaStats[p.via] ?: return null
        val comuna = AddressParser.comunaHint(raw)
        val filtered = if (comuna != null) stats.filter { it.first.comuna == comuna } else stats
        if (filtered.isEmpty()) return null
        // Sin comuna, solo es seguro si toda la vía pertenece a un único barrio.
        if (comuna == null && filtered.map { it.first.barrio }.distinct().size > 1) return null
        val top = filtered.maxOf { it.second }
        val best = filtered.filter { it.second == top }
        // Si hay empate entre barrios, no se inventa una respuesta.
        if (best.map { it.first.barrio }.distinct().size > 1) return null
        return best.first().first
    }

    private fun findName(text: String): NameEntry? {
        val n = AddressParser.normalize(text)
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
        names.add(NameEntry(AddressParser.normalize(display), entry))
        val cleaned = display.replace(PREFIX, " ").replace(SPACES, " ").trim()
        if (cleaned != display && cleaned.length >= MIN_NAME_LENGTH) {
            names.add(NameEntry(AddressParser.normalize(cleaned), entry))
        }
    }

    private fun isWordBoundary(s: String, at: Int, len: Int): Boolean {
        val before = if (at > 0) s[at - 1] else ' '
        val after = if (at + len < s.length) s[at + len] else ' '
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }

    companion object {
        private const val TAG = "BO2Barrio"
        private const val MIN_NAME_LENGTH = 4
        private const val MAX_NEAREST_CROSS = 12.0

        private val ADDRESS_RE = Regex("""(?i)\b(calle|carrera|avenida|diagonal|transversal|autopista|cl|ci|c1|cra|kr|av|dg|tv)\b\.?\s*\d""")
        private val HASH_RE = Regex("""#\s*\d""")
        private val COMUNA_RE = Regex("""(?i)\bcomuna\s*\d{1,2}""")
        private val PURE_COMUNA = Regex("""(?i)^comuna\s*\d{1,2}$""")
        private val PREFIX = Regex("""(?i)\b(urbanización|urbanizacion|sector|barrio|ciudadela|unidad residencial|urb\.?)\b""")
        private val SPACES = Regex("""\s+""")
    }
}

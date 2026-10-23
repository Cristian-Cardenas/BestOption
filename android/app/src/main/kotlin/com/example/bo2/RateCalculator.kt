package com.example.bo2

/** Cálculos de tarifa compartidos por el overlay y las pruebas. */
internal object RateCalculator {
    /**
     * COP por kilómetro del viaje. Se usa solo la distancia del trayecto
     * (no la distancia de recogida).
     */
    fun perKm(price: Double, tripKm: Double?): Double? =
        if (tripKm != null && tripKm > 0.0 && price > 0.0) price / tripKm else null

    /** menor al mínimo = MALO · entre mínimo y máximo = REGULAR · mayor al máximo = BUENO */
    fun classify(rate: Double, minNormal: Double, maxNormal: Double): String = when {
        rate > maxNormal -> "BUENO"
        rate < minNormal -> "MALO"
        else -> "REGULAR"
    }
}

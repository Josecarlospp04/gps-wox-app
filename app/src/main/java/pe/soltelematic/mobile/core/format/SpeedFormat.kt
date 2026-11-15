package pe.soltelematic.mobile.core.format

private const val KM_PER_HOUR_LABEL = "KM/H"

// Cualquier variante que el servidor mande para "kilómetros por hora" -- comparado en minúsculas,
// sin importar cómo llegó capitalizado.
private val KM_PER_HOUR_ALIASES = setOf("kph", "km/h", "kmh")

/**
 * Unidad de velocidad tal como se debe escribir en toda la app: cualquier variante de
 * kilómetros por hora ("kph", "KPH", "km/h", sin importar mayúsculas) se normaliza a "KM/H".
 * speed.unit existe en el servidor precisamente porque no siempre es kph (podría venir en mph,
 * nudos, etc.) -- cualquier otra unidad se respeta tal cual la mandó el servidor, nunca se fuerza
 * a KM/H. null o vacío (no visto en datos reales salvo cuando sí es kph) cae a "KM/H" por
 * default, no a una unidad vacía.
 */
fun normalizeSpeedUnit(unit: String?): String {
    val trimmed = unit?.trim()
    return if (trimmed.isNullOrEmpty() || trimmed.lowercase() in KM_PER_HOUR_ALIASES) {
        KM_PER_HOUR_LABEL
    } else {
        trimmed
    }
}

/**
 * Para texto que el servidor ya compone con la unidad pegada al número (el umbral de una alerta,
 * ej. "5 kph", o un stat de historial como "speed_max" -- ahí no hay value/unit por separado que
 * pasarle a [normalizeSpeedUnit], ver AlertEvent.detail / UnitStat.value). Reemplaza el sufijo de
 * unidad si es alguna variante de kph; cualquier otro sufijo (otra unidad, o texto que no termina
 * en una unidad de velocidad) se deja intacto.
 */
fun normalizeSpeedUnitSuffix(text: String): String {
    val trimmedEnd = text.trimEnd()
    val alias = KM_PER_HOUR_ALIASES.firstOrNull { trimmedEnd.endsWith(it, ignoreCase = true) } ?: return text
    return trimmedEnd.dropLast(alias.length) + KM_PER_HOUR_LABEL
}

private const val KPH_PER_MPH = 1.609344
private const val KPH_PER_KNOT = 1.852
private const val KPH_PER_MPS = 3.6
private val MPH_ALIASES = setOf("mph", "mi/h")
private val KNOT_ALIASES = setOf("kn", "kt", "kts", "knot", "knots", "nudo", "nudos")
private val MPS_ALIASES = setOf("m/s", "mps")

/**
 * Velocidad en km/h a partir del valor y la unidad crudos del servidor (speed.value / speed.unit,
 * ver Asset.speedKph: pese al nombre, viene en la unidad del servidor). Unidad ausente o variante de
 * kph = tal cual; mph, nudos y m/s se convierten; una unidad desconocida o un valor ausente da null
 * (la línea del recorrido queda en gris neutro, nunca en una banda inventada).
 */
fun speedToKph(value: Double?, unit: String?): Double? {
    if (value == null) return null
    val normalized = unit?.trim()?.lowercase().orEmpty()
    return when {
        normalized.isEmpty() || normalized in KM_PER_HOUR_ALIASES -> value
        normalized in MPH_ALIASES -> value * KPH_PER_MPH
        normalized in KNOT_ALIASES -> value * KPH_PER_KNOT
        normalized in MPS_ALIASES -> value * KPH_PER_MPS
        else -> null
    }
}

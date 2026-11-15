package pe.soltelematic.mobile.domain.model

/**
 * Banda de velocidad con la que se colorea la línea del recorrido. Los colores son fijos y viven en
 * el tema (SoltelematicColors.speedColor): LOW verde, MEDIUM azul, HIGH naranja, VERY_HIGH rojo.
 */
enum class SpeedBand { LOW, MEDIUM, HIGH, VERY_HIGH }

/**
 * Los tres límites (km/h) que separan las cuatro bandas; el límite inferior de cada banda va
 * incluido: [0, first) LOW, [first, second) MEDIUM, [second, third) HIGH, [third, ∞) VERY_HIGH.
 * Por defecto 20 / 60 / 80. Solo los límites son personalizables (pantalla Cuenta), nunca los colores.
 */
data class SpeedRanges(val first: Int, val second: Int, val third: Int) {

    /** Enteros positivos y estrictamente ascendentes. */
    val isValid: Boolean get() = first > 0 && first < second && second < third

    /** Banda de [speedKph]; null si la velocidad no es un número (se dibuja en gris neutro). */
    fun bandFor(speedKph: Double?): SpeedBand? = when {
        speedKph == null || speedKph.isNaN() || speedKph.isInfinite() -> null
        speedKph < first -> SpeedBand.LOW
        speedKph < second -> SpeedBand.MEDIUM
        speedKph < third -> SpeedBand.HIGH
        else -> SpeedBand.VERY_HIGH
    }

    companion object {
        val DEFAULT = SpeedRanges(first = 20, second = 60, third = 80)

        /**
         * Lo que dejó UserPreferencesDataStore: si falta algún valor o el conjunto no es válido
         * (p. ej. un valor guardado por una versión anterior o a mano) se usa DEFAULT completo,
         * nunca una mezcla de valores guardados y por defecto.
         */
        fun fromStored(first: Int?, second: Int?, third: Int?): SpeedRanges {
            if (first == null || second == null || third == null) return DEFAULT
            return SpeedRanges(first, second, third).takeIf { it.isValid } ?: DEFAULT
        }

        /** Valida lo que escribió el usuario en los tres campos de Cuenta. */
        fun parse(first: String, second: String, third: String): SpeedRangesParse {
            val numbers = listOf(first, second, third).map { it.trim().toIntOrNull() }
            if (numbers.any { it == null }) return SpeedRangesParse.Invalid(SpeedRangesError.NOT_A_NUMBER)
            val (a, b, c) = numbers.map { it!! }
            if (a <= 0 || b <= 0 || c <= 0) return SpeedRangesParse.Invalid(SpeedRangesError.NOT_POSITIVE)
            if (!(a < b && b < c)) return SpeedRangesParse.Invalid(SpeedRangesError.NOT_ASCENDING)
            return SpeedRangesParse.Valid(SpeedRanges(a, b, c))
        }
    }
}

enum class SpeedRangesError { NOT_A_NUMBER, NOT_POSITIVE, NOT_ASCENDING }

sealed interface SpeedRangesParse {
    data class Valid(val ranges: SpeedRanges) : SpeedRangesParse
    data class Invalid(val error: SpeedRangesError) : SpeedRangesParse
}

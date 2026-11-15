package pe.soltelematic.mobile.domain.model

import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/**
 * Evento de la plataforma que trae GET history entre los tramos (status="event", ver
 * HistoryItemDto). El servidor solo manda el nombre ya traducido en `title` -- sin tipo, id ni
 * alerta (verificado contra volcados reales) --, así que el tipo se infiere del título con
 * [classifyRouteEventTitle]. time sale de start.time.timestamp (epoch UTC correcto, a diferencia del
 * de devices/map); point, de start.coordinates.
 *
 * Se conservan TODOS los eventos del día (exceso de velocidad, combustible, geocercas...); en el mapa
 * solo se dibujan los de tipo distinto de OTHER.
 */
data class RouteEvent(
    val type: RouteEventType,
    val time: Instant?,
    val point: GeoPoint?,
    val title: String
)

/** Los únicos tipos que se dibujan hoy; todo lo demás es OTHER y se ignora en el mapa. */
enum class RouteEventType { IGNITION_ON, IGNITION_OFF, OTHER }

/**
 * TABLA ÚNICA de títulos reconocidos (lo que el servidor escribe en `title`). La coincidencia no
 * distingue mayúsculas ni tildes y busca el texto dentro del título (puede traer sufijos, como el
 * umbral entre paréntesis de "Exceso de velocidad (5 kph)").
 *
 * Confirmados en el servidor de producción (español): "Encendido conectado" e "Ignición apagada".
 *
 * PENDIENTE: los equivalentes en inglés del archivo de traducciones de GPSWOX no se pudieron
 * confirmar desde este repositorio ni desde los volcados (el servidor solo está en español). Cuando
 * se vea un servidor en inglés, añadir aquí sus títulos reales; no se adivinan.
 */
private val ROUTE_EVENT_TITLES: Map<RouteEventType, List<String>> = mapOf(
    RouteEventType.IGNITION_ON to listOf("Encendido conectado"),
    RouteEventType.IGNITION_OFF to listOf("Ignición apagada")
)

private val NORMALIZED_ROUTE_EVENT_TITLES: Map<RouteEventType, List<String>> =
    ROUTE_EVENT_TITLES.mapValues { (_, titles) -> titles.map { it.normalizeForTitleMatch() } }

/** Tipo del evento según su título; cualquier título no reconocido (o ausente) es OTHER. */
fun classifyRouteEventTitle(title: String?): RouteEventType {
    val text = title?.normalizeForTitleMatch()?.takeIf { it.isNotEmpty() } ?: return RouteEventType.OTHER
    return NORMALIZED_ROUTE_EVENT_TITLES.entries
        .firstOrNull { (_, needles) -> needles.any { text.contains(it) } }
        ?.key
        ?: RouteEventType.OTHER
}

// Sin tildes y en minúsculas con Locale.ROOT (con el locale del teléfono, en turco la "I" bajaría
// a "ı" y "IGNICIÓN" no coincidiría) -- mismo criterio que AlertEventType.
internal fun String.normalizeForTitleMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .trim()

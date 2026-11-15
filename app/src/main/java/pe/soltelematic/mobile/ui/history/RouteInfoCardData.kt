package pe.soltelematic.mobile.ui.history

import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.RouteEventType
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerKey
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Qué representa el icono tocado en el mapa (título de la tarjeta de información). */
enum class InfoCardKind { IGNITION_ON, IGNITION_OFF, STOP, ROUTE_START, ROUTE_END }

/**
 * Contenido de la tarjeta de información de un icono del mapa. end y durationRaw solo se llenan en una
 * parada (inicio y fin); durationRaw es "HH:MM:SS", listo para formatDurationCompact. point es de donde
 * sale el lugar (dirección).
 */
data class InfoCardData(
    val kind: InfoCardKind,
    val start: Instant?,
    val end: Instant?,
    val durationRaw: String?,
    val point: GeoPoint?
)

/** La tarjeta abierta: qué icono la abrió, su contenido y el estado de su dirección. */
data class InfoCardState(
    val key: RouteMarkerKey,
    val data: InfoCardData,
    val address: AddressResolution
)

/**
 * Datos de la tarjeta para el icono [key], o null si ya no existe en la ruta (p. ej. tras un
 * refresco). Partida/llegada: la hora y el punto de salida son los del tramo "drive" y, si el día
 * empieza/termina en una parada (ida y vuelta, ver toRouteMapData), los de esa parada -- salida = su
 * fin, llegada = su inicio.
 */
fun HistoryRoute.infoCardFor(key: RouteMarkerKey): InfoCardData? {
    when (key.role) {
        RouteMarkerRole.IGNITION_ON, RouteMarkerRole.IGNITION_OFF -> {
            val event = key.eventIndex?.let { events.getOrNull(it) } ?: return null
            val kind = when (event.type) {
                RouteEventType.IGNITION_ON -> InfoCardKind.IGNITION_ON
                RouteEventType.IGNITION_OFF -> InfoCardKind.IGNITION_OFF
                RouteEventType.OTHER -> return null
            }
            return InfoCardData(kind, event.time, null, null, event.point)
        }
        RouteMarkerRole.STOP -> {
            val stop = key.legIndex?.let { legs.getOrNull(it) } as? HistoryStopLeg ?: return null
            val duration = stop.stats.firstOrNull { it.key == "duration" }?.value
                ?: durationRawBetween(stop.start.time, stop.end.time)
            return InfoCardData(InfoCardKind.STOP, stop.start.time, stop.end.time, duration, stop.start.point)
        }
        RouteMarkerRole.ROUTE_START -> {
            val leg = key.legIndex?.let { legs.getOrNull(it) } ?: return null
            return when (leg) {
                is HistoryDriveLeg -> InfoCardData(InfoCardKind.ROUTE_START, leg.start.time, null, null, leg.start.point)
                is HistoryStopLeg -> InfoCardData(InfoCardKind.ROUTE_START, leg.end.time, null, null, leg.start.point)
            }
        }
        RouteMarkerRole.ROUTE_END -> {
            val leg = key.legIndex?.let { legs.getOrNull(it) } ?: return null
            return when (leg) {
                is HistoryDriveLeg -> InfoCardData(InfoCardKind.ROUTE_END, leg.end.time, null, null, leg.end.point)
                is HistoryStopLeg -> InfoCardData(InfoCardKind.ROUTE_END, leg.start.time, null, null, leg.start.point)
            }
        }
    }
}

/** "HH:MM:SS" entre dos instantes; null si falta alguno o el orden es inverso. */
internal fun durationRawBetween(start: Instant?, end: Instant?): String? {
    if (start == null || end == null) return null
    val seconds = Duration.between(start, end).seconds
    if (seconds < 0) return null
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

private val CARD_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
private val CARD_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
private val CARD_SHORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)

/** "14/11/2026"; zona del teléfono, igual que el resto de horas de la app. */
fun formatCardDate(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
    instant?.let { CARD_DATE_FORMAT.format(it.atZone(zone)) } ?: "-"

/** "08:08:28". */
fun formatCardTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
    instant?.let { CARD_TIME_FORMAT.format(it.atZone(zone)) } ?: "-"

/**
 * Hora de fin de una parada: solo la hora, o "dd/MM HH:mm:ss" si cae en otro día que el inicio (un
 * rango de Historial de varios días puede traer una parada que cruza la medianoche).
 */
fun formatCardEndTime(start: Instant?, end: Instant?, zone: ZoneId = ZoneId.systemDefault()): String {
    if (end == null) return "-"
    val sameDay = start != null && start.atZone(zone).toLocalDate() == end.atZone(zone).toLocalDate()
    val time = formatCardTime(end, zone)
    return if (sameDay || start == null) time else "${CARD_SHORT_DATE_FORMAT.format(end.atZone(zone))} $time"
}

/** Coordenadas como último recurso cuando el servidor no devuelve dirección. */
fun formatCoordinates(point: GeoPoint): String =
    String.format(Locale.US, "%.6f, %.6f", point.lat, point.lng)

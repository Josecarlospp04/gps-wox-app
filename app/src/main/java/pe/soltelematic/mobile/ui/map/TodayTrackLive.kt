package pe.soltelematic.mobile.ui.map

import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.ui.history.RouteMapData
import pe.soltelematic.mobile.ui.history.distanceMeters
import java.time.Instant
import java.time.LocalDate

// Lógica pura de la actualización en vivo del modo "Recorrido de hoy" (etapa 3): fusión de los
// puntos que llegan por el sondeo con el recorrido completo que trae getTodayRoute.

/** Un punto solo se añade si la unidad se movió MÁS de esto respecto al último (ruido de GPS parado). */
const val MIN_LIVE_POINT_DISTANCE_METERS = 10.0

/** Posición recibida por el sondeo, aún no cubierta por ninguna respuesta de getTodayRoute. */
data class ProvisionalPoint(val point: GeoPoint, val time: Instant)

/** Última hora que cubre el recorrido (fin de cualquier tramo o última posición de un trayecto). */
fun HistoryRoute.coveredUntil(): Instant? =
    legs.flatMap { leg ->
        listOfNotNull(leg.end.time, (leg as? HistoryDriveLeg)?.positions?.lastOrNull()?.time)
    }.maxOrNull()

/** Último punto del recorrido: de donde arranca la polilínea provisional. */
fun HistoryRoute.lastPoint(): GeoPoint? = when (val leg = legs.lastOrNull()) {
    is HistoryDriveLeg -> leg.positions.lastOrNull()?.point ?: leg.end.point
    is HistoryStopLeg -> leg.start.point
    null -> null
}

/**
 * Añade [point] al final de los provisionales si: hay recorrido cargado, es posterior a lo que ya
 * cubre el recorrido y al último provisional (no duplica ni retrocede) y está a más de
 * MIN_LIVE_POINT_DISTANCE_METERS del último punto (el último provisional o, sin ninguno, el final
 * del recorrido). Si no, devuelve el mismo estado.
 */
fun TodayTrackState.withLivePoint(point: GeoPoint, time: Instant): TodayTrackState {
    val current = route ?: return this
    if (status != TodayTrackStatus.READY) return this
    val covered = current.coveredUntil()
    if (covered != null && !time.isAfter(covered)) return this
    val last = provisional.lastOrNull()
    if (last != null && !time.isAfter(last.time)) return this
    val reference = last?.point ?: current.lastPoint() ?: return this
    if (distanceMeters(reference, point) <= MIN_LIVE_POINT_DISTANCE_METERS) return this
    return copy(provisional = provisional + ProvisionalPoint(point, time))
}

/**
 * Aplica un refresco completo de getTodayRoute SIN tocar cámara ni panel: no cambia
 * frameRequestId/centerRequestId/legFocusRequestId ni panelExpanded (salvo que ya no haya
 * recorrido). Descarta los provisionales que la respuesta ya cubre (por hora). Conserva el tramo
 * seleccionado y las direcciones solo de los tramos que siguen existiendo; si [newMapData] es igual
 * al actual se conserva la instancia, para que el mapa no recalcule la polilínea.
 *
 * Mientras la carga inicial o un reintento están en curso (LOADING/ERROR) no hace nada: ese camino
 * lo resuelve loadTodayTrack.
 */
fun TodayTrackState.withRefreshedRoute(newRoute: HistoryRoute, newMapData: RouteMapData): TodayTrackState {
    if (status == TodayTrackStatus.LOADING || status == TodayTrackStatus.ERROR) return this
    val newStatus = if (newRoute.legs.isEmpty()) TodayTrackStatus.EMPTY else TodayTrackStatus.READY
    val covered = newRoute.coveredUntil()
    val keptProvisional = when {
        newStatus == TodayTrackStatus.EMPTY -> emptyList()
        covered == null -> provisional
        else -> provisional.filter { it.time.isAfter(covered) }
    }
    return copy(
        status = newStatus,
        route = newRoute,
        mapData = if (newMapData == mapData) mapData else newMapData,
        provisional = keptProvisional,
        selectedLegIndex = selectedLegIndex?.takeIf { sameLeg(route, newRoute, it) },
        addresses = addresses.filterKeys { sameLeg(route, newRoute, it) },
        panelExpanded = panelExpanded && newStatus == TodayTrackStatus.READY
    )
}

// Mismo tramo = misma clase y mismo inicio (punto y hora): un tramo en curso solo cambia su fin.
private fun sameLeg(old: HistoryRoute?, new: HistoryRoute, index: Int): Boolean {
    val before = old?.legs?.getOrNull(index) ?: return false
    val after = new.legs.getOrNull(index) ?: return false
    return before::class == after::class && before.start == after.start
}

/** Polilínea provisional lista para dibujar: arranca en el final del recorrido; vacía sin provisionales. */
fun TodayTrackState.provisionalPath(): List<GeoPoint> {
    if (provisional.isEmpty()) return emptyList()
    val anchor = route?.lastPoint() ?: return emptyList()
    return listOf(anchor) + provisional.map { it.point }
}

/** Se cruzó la medianoche desde que se cargó este día. */
fun TodayTrackState.isStaleDay(today: LocalDate): Boolean = dayDate != today

/** Nuevo día: mismo modo y unidad, recorrido vacío y cargando; sin plegar/desplegar nada heredado. */
fun TodayTrackState.forNewDay(today: LocalDate): TodayTrackState = TodayTrackState(
    assetId = assetId,
    dayDate = today,
    frameRequestId = frameRequestId,
    centerRequestId = centerRequestId,
    legFocusRequestId = legFocusRequestId
)

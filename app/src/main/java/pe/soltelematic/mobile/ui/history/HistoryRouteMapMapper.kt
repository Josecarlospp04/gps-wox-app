package pe.soltelematic.mobile.ui.history

import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryLeg
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerData
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import pe.soltelematic.mobile.ui.map.engine.RoutePoint
import pe.soltelematic.mobile.ui.map.engine.RoutePolyline
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteMapData(
    val polylines: List<RoutePolyline>,
    val markers: List<RouteMarkerData>
)

// Umbral para considerar "el mismo sitio" el punto de partida y el de llegada de un día ida y
// vuelta. Mayor que la deriva típica de GPS estando parado (unos metros, ver nota del Bloque 1),
// suficiente para no confundir dos sitios genuinamente distintos que quedan cerca.
private const val SAME_LOCATION_THRESHOLD_METERS = 100.0

/**
 * legIndex de cada marcador es la posición del tramo en HistoryRoute.legs -- lo que permite el
 * vínculo bidireccional con la línea de tiempo (Bloque 3/4).
 *
 * El marcador de parada usa siempre start.point de la propia parada, nunca un promedio con end:
 * la deriva del GPS estando detenido no es movimiento real, y promediar inventaría un punto donde
 * la unidad nunca estuvo (confirmado con el usuario). Todas las paradas se listan, sin filtrar
 * por duración.
 *
 * ROUTE_START/ROUTE_END solo se agregan como marcador aparte cuando el primer/último tramo del
 * día es un "drive": si el día empieza o termina en una parada, esa parada ya ancla ese extremo
 * de la ruta -- un pin superpuesto en el mismo punto sería redundante.
 *
 * Excepción confirmada con el usuario: si el día empieza Y termina en una parada en el mismo
 * sitio (ida y vuelta), esas dos paradas son indistinguibles como dos pines STOP idénticos en la
 * misma posición -- en ese caso puntual, la primera se pinta como ROUTE_START y la última como
 * ROUTE_END en vez de STOP, para que el usuario distinga partida de llegada. Siguen siendo
 * paradas en la línea de tiempo -- esto solo cambia el rol visual del pin en el mapa.
 */
fun HistoryRoute.toRouteMapData(): RouteMapData {
    val firstLeg = legs.firstOrNull()
    val lastLeg = legs.lastOrNull()
    val roundTrip = isRoundTripAtSameStop(firstLeg, lastLeg)

    val stopMarkers = legs.withIndex().mapNotNull { (index, leg) ->
        if (leg !is HistoryStopLeg) return@mapNotNull null
        val point = leg.start.point ?: return@mapNotNull null
        val role = when {
            roundTrip && index == 0 -> RouteMarkerRole.ROUTE_START
            roundTrip && index == legs.lastIndex -> RouteMarkerRole.ROUTE_END
            else -> RouteMarkerRole.STOP
        }
        RouteMarkerData(legIndex = index, position = point, role = role)
    }

    val startMarker = if (!roundTrip) {
        (firstLeg as? HistoryDriveLeg)?.start?.point?.let { point ->
            RouteMarkerData(legIndex = 0, position = point, role = RouteMarkerRole.ROUTE_START)
        }
    } else null

    val endMarker = if (!roundTrip) {
        (lastLeg as? HistoryDriveLeg)?.end?.point?.let { point ->
            RouteMarkerData(legIndex = legs.lastIndex, position = point, role = RouteMarkerRole.ROUTE_END)
        }
    } else null

    val polylines = legs.withIndex().mapNotNull { (index, leg) ->
        if (leg !is HistoryDriveLeg || leg.positions.size < 2) return@mapNotNull null
        RoutePolyline(legIndex = index, points = leg.positions.map { RoutePoint(it.point, it.colorHex) })
    }

    return RouteMapData(
        polylines = polylines,
        markers = stopMarkers + listOfNotNull(startMarker, endMarker)
    )
}

/**
 * A dónde mover la cámara al seleccionar legIndex (marcador tocado en el mapa o fila tocada en
 * la línea de tiempo). Point para una parada: centrar y hacer zoom en su marcador ya alcanza para
 * "ver dónde se detuvo" -- prioriza el marcador ya calculado (markers) sobre start.point a mano
 * porque un tramo de borde (ROUTE_START/ROUTE_END, ver arriba) puede tener su pin en end.point,
 * y hay que centrar en el mismo punto que ve el pin, no en otro. Bounds para un viaje: un solo
 * punto no sirve para "ver el recorrido completo", así que se encuadra sobre todo el polyline del
 * tramo (confirmado con el usuario) -- sale directo de leg.positions, no de markers (los drives
 * interiores del día no tienen marcador propio).
 */
sealed interface CameraTarget {
    data class Point(val point: GeoPoint) : CameraTarget
    data class Bounds(val points: List<GeoPoint>) : CameraTarget
}

fun RouteMapData.cameraTargetFor(route: HistoryRoute, legIndex: Int): CameraTarget? {
    val leg = route.legs.getOrNull(legIndex) ?: return null
    if (leg is HistoryDriveLeg && leg.positions.size >= 2) {
        return CameraTarget.Bounds(leg.positions.map { it.point })
    }
    val point = markers.firstOrNull { it.legIndex == legIndex }?.position ?: leg.start.point
    return point?.let { CameraTarget.Point(it) }
}

/**
 * Un punto reproducible de la línea de tiempo animada (Bloque de reproducción). legIndex es la
 * posición del HistoryDriveLeg dueño de este punto en HistoryRoute.legs -- mismo campo que ya usan
 * los marcadores/polylines, permite resaltar el tramo correspondiente en HistoryTimeline mientras
 * se reproduce, sin inventar un segundo esquema de índices.
 */
data class HistoryPlaybackPoint(val legIndex: Int, val position: HistoryPosition)

/**
 * Todas las posiciones GPS originales de los viajes del día, concatenadas en orden cronológico --
 * las mismas que ya trae HistoryRoute.legs[].positions (sin pasar por RouteSimplifier/PolyUtil.
 * simplify, que solo vive dentro de GoogleRouteMapEngine para dibujar el polyline estático y nunca
 * toca el modelo de dominio). Se usan tal cual, sin simplificar, porque la reproducción necesita la
 * velocidad y el paso real entre puntos, no una geometría reducida para dibujar más rápido.
 *
 * Las paradas no aportan puntos (no traen traza GPS, solo start/end) -- el marcador de reproducción
 * salta directo del último punto de un viaje al primero del siguiente, la parada en sí no se anima.
 */
fun HistoryRoute.toPlaybackPoints(): List<HistoryPlaybackPoint> =
    legs.withIndex().flatMap { (index, leg) ->
        if (leg !is HistoryDriveLeg) emptyList() else leg.positions.map { HistoryPlaybackPoint(index, it) }
    }

// Distancia mínima entre el punto evaluado y el punto usado para calcularle el rumbo (ver
// toBearings más abajo). El servidor no manda rumbo en el historial (HistoryTransformer solo
// entrega id/t/s/c/lat/lng por posición), así que se calcula acá con atan2 entre dos posiciones --
// pero contra el punto INMEDIATO siguiente no alcanza: con la unidad detenida el GPS sigue
// derivando unos pocos metros de un fix al siguiente por ruido de la señal, y ese ruido produce un
// atan2 que salta de un extremo a otro sin que la unidad haya girado nada. Comparar contra el
// primer punto que esté a esta distancia o más filtra ese ruido sin perder los giros reales (que sí
// desplazan al vehículo bastante más que esto en el tiempo entre dos fixes consecutivos).
private const val MIN_BEARING_DISTANCE_METERS = 15.0

/**
 * Rumbo inicial (0..360, 0 = norte, sentido horario) de [from] hacia [to] -- fórmula estándar de
 * "initial bearing" sobre una esfera vía atan2, para cubrir los cuatro cuadrantes sin ambigüedad.
 */
fun bearingDegrees(from: GeoPoint, to: GeoPoint): Float {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val deltaLng = Math.toRadians(to.lng - from.lng)
    val y = sin(deltaLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLng)
    val degrees = Math.toDegrees(atan2(y, x))
    return ((degrees + 360.0) % 360.0).toFloat()
}

/**
 * Un rumbo por punto de reproducción (lista paralela a esta), calculado UNA sola vez por
 * recorrido cargado -- nunca en cada tick de la reproducción. El loop de
 * HistoryViewModel.startPlayback avanza hasta 40 veces por segundo (ver BASE_STEP_DELAY_MS /
 * VISUAL_UPDATE_INTERVAL_MS ahí, y el comentario de jank medido en esta pantalla), y
 * GoogleRouteMapEngine ya tuvo que resolver un problema parecido con Douglas-Peucker corriendo en
 * cada tick del marcador (ver renderableRuns/selectedPolyline ahí): recalcular atan2 sobre miles
 * de puntos en cada frame repetiría el mismo error. Acá se paga una sola vez, al cargar la ruta.
 *
 * Se calcula por tramo (agrupado por legIndex, ya contiguos en esta lista -- ver
 * HistoryRoute.toPlaybackPoints), nunca cruzando de un tramo a otro: el marcador salta de golpe
 * del último punto de un viaje al primero del siguiente (sin traza real entre medio, puede haber
 * una parada de horas ahí), así que un rumbo calculado contra el punto de otro tramo no
 * describiría ningún giro real de la unidad.
 */
fun List<HistoryPlaybackPoint>.toBearings(): List<Float> {
    if (isEmpty()) return emptyList()
    val bearings = FloatArray(size)
    var runStart = 0
    for (i in 1..size) {
        if (i == size || this[i].legIndex != this[runStart].legIndex) {
            fillRunBearings(runStart, i, bearings)
            runStart = i
        }
    }
    return bearings.toList()
}

private fun List<HistoryPlaybackPoint>.fillRunBearings(start: Int, end: Int, out: FloatArray) {
    var lastValid: Float? = null
    for (i in start until end) {
        val origin = this[i].position.point
        val targetIndex = ((i + 1) until end).firstOrNull {
            distanceMeters(origin, this[it].position.point) >= MIN_BEARING_DISTANCE_METERS
        }
        val bearing = targetIndex?.let { bearingDegrees(origin, this[it].position.point) }
        // Sin ningún punto por delante a distancia suficiente (fin del tramo, o una parada
        // acercándose con puntos muy juntos): conserva el último rumbo válido en vez de saltar a
        // 0 -- 0 (norte) no tendría ningún significado acá, sería un giro inventado que no ocurrió.
        // Al principio de un tramo, antes de tener algún rumbo válido calculado todavía, no queda
        // otra que asumir norte por un instante: en la práctica el primer punto de un "drive" ya
        // tiene movimiento real por delante casi siempre, así que ese caso es la excepción, no la
        // regla.
        out[i] = bearing ?: lastValid ?: 0f
        if (bearing != null) lastValid = bearing
    }
}

private fun isRoundTripAtSameStop(firstLeg: HistoryLeg?, lastLeg: HistoryLeg?): Boolean {
    if (firstLeg !is HistoryStopLeg || lastLeg !is HistoryStopLeg || firstLeg === lastLeg) return false
    val start = firstLeg.start.point ?: return false
    val end = lastLeg.start.point ?: return false
    return distanceMeters(start, end) <= SAME_LOCATION_THRESHOLD_METERS
}

internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
    return 2 * earthRadiusMeters * asin(sqrt(h))
}

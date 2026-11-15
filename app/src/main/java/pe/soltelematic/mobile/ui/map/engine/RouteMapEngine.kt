package pe.soltelematic.mobile.ui.map.engine

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.soltelematic.mobile.domain.model.AssetIcon
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.domain.model.SpeedRanges

/**
 * Contrato separado de MapEngine (Sprint 1): esa abstracción es específica de clustering de
 * assets en vivo (ClusterManager, iconos por unidad) y no tiene sentido forzarle conceptos de
 * ruta (polyline, marcadores de parada/inicio/fin) que nunca usa. Misma filosofía de todos
 * modos -- aísla el SDK de mapas de la pantalla de Historial (ver GoogleRouteMapEngine, la única
 * implementación hoy).
 */

/**
 * Un punto del polyline de un "drive". speedKph es la velocidad del punto (positions[].s, en km/h):
 * el motor la convierte en banda de color con los SpeedRanges vigentes, así cambiar los límites
 * recolorea sin recalcular el mapa ni volver a pedir el historial. positions[].c (el color del
 * servidor) se ignora: siempre llega #0000FF. null = velocidad desconocida (gris neutro).
 */
data class RoutePoint(val point: GeoPoint, val speedKph: Double?)

/**
 * legIndex es la posición del tramo "drive" en HistoryRoute.legs -- permite al engine resaltar
 * en verde el polyline completo del tramo seleccionado (en vez de sus colores por velocidad)
 * cuando legIndex == selectedLegIndex, sin que el mapper tenga que reconstruir nada.
 */
data class RoutePolyline(val legIndex: Int, val points: List<RoutePoint>)

/**
 * ROUTE_START / ROUTE_END: insignias "A" / "B". STOP: parada. IGNITION_ON / IGNITION_OFF: puntos verde
 * y rojo de los eventos de motor encendido / apagado (ver RouteEvent).
 */
enum class RouteMarkerRole { ROUTE_START, ROUTE_END, STOP, IGNITION_ON, IGNITION_OFF }

val RouteMarkerRole.isEvent: Boolean
    get() = this == RouteMarkerRole.IGNITION_ON || this == RouteMarkerRole.IGNITION_OFF

/**
 * Clave propia de un marcador, la que se reporta al tocarlo: el rol y de dónde sale -- legIndex (tramo
 * de HistoryRoute.legs) para paradas y partida/llegada, eventIndex (HistoryRoute.events) para eventos.
 */
data class RouteMarkerKey(val role: RouteMarkerRole, val legIndex: Int?, val eventIndex: Int?)

/** A qué lleva tocar un marcador: seleccionar un tramo o abrir un evento. */
sealed interface RouteMarkerTarget {
    data class Leg(val legIndex: Int) : RouteMarkerTarget
    data class Event(val eventIndex: Int) : RouteMarkerTarget
}

/** Enrutado del toque por tipo de marcador; null si la clave está incompleta. */
fun RouteMarkerKey.target(): RouteMarkerTarget? =
    if (role.isEvent) eventIndex?.let { RouteMarkerTarget.Event(it) } else legIndex?.let { RouteMarkerTarget.Leg(it) }

/**
 * legIndex referencia la posición del tramo (HistoryRoute.legs) que este marcador representa --
 * es lo que permite el vínculo bidireccional con la línea de tiempo (Bloque 3/4): tocar el
 * marcador selecciona esa fila, seleccionar la fila resalta este marcador. Es null en los eventos,
 * que no son un tramo: ellos llevan eventIndex (posición en HistoryRoute.events).
 */
data class RouteMarkerData(
    val legIndex: Int?,
    val position: GeoPoint,
    val role: RouteMarkerRole,
    val eventIndex: Int? = null
) {
    val key: RouteMarkerKey get() = RouteMarkerKey(role, legIndex, eventIndex)
}

interface RouteMapEngine {

    @Composable
    fun rememberCameraController(): MapCameraController

    @Composable
    fun Content(
        modifier: Modifier,
        cameraController: MapCameraController,
        polylines: List<RoutePolyline>,
        markers: List<RouteMarkerData>,
        selectedLegIndex: Int?,
        // Contrato: nunca abrir un InfoWindow/popup del SDK. El único efecto de tocar un marcador es
        // reportar su clave (la tarjeta de información la dibuja la pantalla, no el motor).
        onMarkerClick: (RouteMarkerKey) -> Unit,
        // Toque en el mapa fuera de cualquier marcador (cierra la tarjeta de información). Sin valor
        // por defecto, mismo motivo que playbackPoint.
        onMapClick: () -> Unit,
        // Posición actual del marcador de reproducción (ver HistoryViewModel.playback), null si no
        // hay reproducción en curso -- la ruta completa (polylines de arriba) se sigue dibujando
        // igual, este marcador solo se superpone encima, nunca la reemplaza ni la dibuja de a poco.
        //
        // Sin valor por defecto a propósito: un default en un miembro @Composable de una interfaz
        // no genera el bridge $default correctamente (AbstractMethodError en runtime al llamarlo a
        // través del tipo de interfaz -- confirmado en dispositivo). Todo caller, incluidos los
        // previews, pasa estos dos parámetros explícitos.
        playbackPoint: GeoPoint?,
        // Rumbo del punto actual de reproducción (ver HistoryRouteMapMapper.toBearings), en
        // grados 0..360 con 0 = norte -- el servidor no lo manda en el historial, se calcula en la
        // app. Se ignora si playbackPoint es null (no hay reproducción en curso). Sin valor por
        // defecto, mismo motivo que playbackPoint: ver nota de abajo.
        playbackBearing: Float,
        // Icono real de la unidad (el mismo AssetIcon que ya resuelve el mapa en vivo desde Room,
        // ver AssetRepository) -- null mientras el asset todavía no llegó a Room o no se encontró,
        // tratado igual que "sin icono propio". Junto con playbackBearing decide cómo se dibuja el
        // marcador de reproducción (ver GoogleRouteMapEngine):
        //   A) url != null && courseDegrees != null (icono "rotating"): el PNG de la unidad,
        //      rotado con playbackBearing.
        //   B) url != null && courseDegrees == null (icono "icon", pin de gota -- nunca rota, ver
        //      AssetIcon.courseDegrees): el PNG fijo, más una flecha propia aparte que sí rota.
        //   C) url == null (o el PNG todavía no cargó): una flecha propia sola, color primary --
        //      reemplaza al círculo plano que había antes y que no comunicaba dirección.
        unitIcon: AssetIcon?,
        // Se dispara la primera vez que el usuario arrastra el mapa a mano (GESTURE, nunca por
        // centerOn/moveInstantly propios del engine) -- HistoryScreen lo usa para dejar de mover
        // la cámara detrás del marcador en cuanto el usuario le pelea el gesto.
        onCameraGesture: () -> Unit,
        // Espejo de la preferencia global (UserPreferencesDataStore.mapType, compartida con el
        // mapa en vivo) -- ver HistoryUiState.mapType. Sin valor por defecto, mismo motivo que
        // playbackPoint arriba: un default acá no genera el bridge $default en un miembro
        // @Composable de interfaz.
        mapType: MapType,
        // Límites de las bandas de color de la línea por velocidad (preferencia de Cuenta). Sin valor
        // por defecto, mismo motivo que playbackPoint.
        speedRanges: SpeedRanges
    )
}

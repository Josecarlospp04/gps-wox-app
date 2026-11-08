package pe.soltelematic.mobile.ui.map

import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.ui.history.AddressResolution
import pe.soltelematic.mobile.ui.history.CameraTarget
import pe.soltelematic.mobile.ui.history.RouteMapData
import pe.soltelematic.mobile.ui.history.cameraTargetFor
import pe.soltelematic.mobile.ui.map.engine.MapMarkerData

enum class TodayTrackStatus { LOADING, READY, EMPTY, ERROR }

/**
 * Modo "Recorrido de hoy" del mapa principal (null en MapUiState = fuera del modo). Vive aparte de
 * selectedAssetId/focusedAssetId a propósito: en el modo nunca hay hoja, cola ni seguimiento de
 * cámara, así que esos campos quedan en null y el comportamiento de tocar una unidad no se toca.
 *
 * frameRequestId / centerRequestId suben con cada pedido (como MapUiState.focusRequestId): MapScreen
 * mueve la cámara una vez por valor, nunca por recomposición.
 *
 * Panel inferior (etapa 2): panelExpanded = lista de tramos desplegada; selectedLegIndex = tramo
 * resaltado en mapa y lista (mismo índice que HistoryRoute.legs, igual que en Historial);
 * legFocusRequestId sube con cada tramo tocado (la cámara se mueve una vez por valor);
 * addresses = direcciones de las paradas por legIndex, igual que HistoryUiState.addresses.
 */
data class TodayTrackState(
    val assetId: Int,
    val status: TodayTrackStatus = TodayTrackStatus.LOADING,
    val route: HistoryRoute? = null,
    val mapData: RouteMapData? = null,
    val frameRequestId: Int = 0,
    val centerRequestId: Int = 0,
    val panelExpanded: Boolean = false,
    val selectedLegIndex: Int? = null,
    val legFocusRequestId: Int = 0,
    val addresses: Map<Int, AddressResolution> = emptyMap()
)

/** Solo se despliega la lista cuando hay recorrido (READY); plegar siempre es posible. */
fun TodayTrackState.withPanelExpanded(expanded: Boolean): TodayTrackState =
    if (expanded && status != TodayTrackStatus.READY) this else copy(panelExpanded = expanded)

/**
 * Tocar un tramo de la lista: lo resalta, pliega el panel (desplegado taparía buena parte del mapa
 * donde se va a encuadrar) y pide mover la cámara una vez.
 */
fun TodayTrackState.withLegSelected(legIndex: Int): TodayTrackState {
    if (route?.legs?.getOrNull(legIndex) == null) return this
    return copy(selectedLegIndex = legIndex, panelExpanded = false, legFocusRequestId = legFocusRequestId + 1)
}

/**
 * Atrás: panel desplegado -> lo pliega (devuelve el estado nuevo); plegado -> null, que significa
 * "salir del modo".
 */
fun TodayTrackState.afterBack(): TodayTrackState? =
    if (panelExpanded) copy(panelExpanded = false) else null

/**
 * Puntos a encuadrar para el tramo seleccionado, con el mismo criterio que Historial
 * (cameraTargetFor): un punto para una parada, todo el trazado para un trayecto. Null sin tramo
 * seleccionado o sin recorrido.
 */
fun TodayTrackState.selectedLegFramePoints(): List<GeoPoint>? {
    val index = selectedLegIndex ?: return null
    val currentRoute = route ?: return null
    return when (val target = mapData?.cameraTargetFor(currentRoute, index)) {
        is CameraTarget.Point -> listOf(target.point)
        is CameraTarget.Bounds -> target.points
        null -> null
    }
}

/** Solo la unidad seguida; sin modo ([trackedAssetId] null) la lista completa, tal cual. */
fun List<MapMarkerData>.onlyTracked(trackedAssetId: Int?): List<MapMarkerData> =
    if (trackedAssetId == null) this else filter { it.id == trackedAssetId }

/**
 * Puntos a encuadrar: todo el recorrido (polilíneas + paradas) y la posición actual de la unidad.
 * Sin recorrido (estado vacío / cargando) queda solo la posición de la unidad, si la tiene.
 */
fun TodayTrackState.framePoints(assetPosition: GeoPoint?): List<GeoPoint> {
    val data = mapData
    val routePoints = data?.polylines?.flatMap { polyline -> polyline.points.map { it.point } }.orEmpty() +
        data?.markers?.map { it.position }.orEmpty()
    return routePoints + listOfNotNull(assetPosition)
}

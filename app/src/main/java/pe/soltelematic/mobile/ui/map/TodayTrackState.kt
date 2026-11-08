package pe.soltelematic.mobile.ui.map

import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.ui.history.RouteMapData
import pe.soltelematic.mobile.ui.map.engine.MapMarkerData

enum class TodayTrackStatus { LOADING, READY, EMPTY, ERROR }

/**
 * Modo "Recorrido de hoy" del mapa principal (null en MapUiState = fuera del modo). Vive aparte de
 * selectedAssetId/focusedAssetId a propósito: en el modo nunca hay hoja, cola ni seguimiento de
 * cámara, así que esos campos quedan en null y el comportamiento de tocar una unidad no se toca.
 *
 * frameRequestId / centerRequestId suben con cada pedido (como MapUiState.focusRequestId): MapScreen
 * mueve la cámara una vez por valor, nunca por recomposición.
 */
data class TodayTrackState(
    val assetId: Int,
    val status: TodayTrackStatus = TodayTrackStatus.LOADING,
    val route: HistoryRoute? = null,
    val mapData: RouteMapData? = null,
    val frameRequestId: Int = 0,
    val centerRequestId: Int = 0
)

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

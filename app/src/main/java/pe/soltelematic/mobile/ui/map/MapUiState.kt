package pe.soltelematic.mobile.ui.map

import pe.soltelematic.mobile.domain.model.Asset
import pe.soltelematic.mobile.domain.model.AssetFilter
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.Geofence
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.domain.model.UnitStat

const val MAX_SEARCH_SUGGESTIONS = 6

data class MapUiState(
    val assets: List<Asset> = emptyList(),
    val searchQuery: String = "",
    val activeFilter: AssetFilter = AssetFilter.ALL,
    val hasBlockedAssets: Boolean = false,
    val selectedAssetId: Int? = null,
    // Unidad resaltada (anillo del marcador, ver GoogleMapEngine.selectionRing) sin abrir la hoja
    // inferior -- el "Ver en mapa" de la ficha (ver MapViewModel.focusOnAsset) usa esto en vez de
    // selectedAssetId, porque el usuario ya viene de la ficha y no hace falta volver a abrirla acá.
    val focusedAssetId: Int? = null,
    // La cámara sigue a la unidad enfocada (highlightedAssetId) en cada sondeo. Se apaga con un
    // gesto del usuario sobre el mapa (la selección, la hoja y la cola no cambian) y al quitar el
    // enfoque; se enciende al enfocar de nuevo (marcador, Unidades, "Ver en mapa").
    val isFollowing: Boolean = false,
    // Sube con cada pedido de enfoque, incluso sobre la misma unidad: MapScreen encuadra (cola +
    // unidad) una vez por cada valor, nunca por sondeo.
    val focusRequestId: Int = 0,
    val isRefreshing: Boolean = false,
    val unseenEventsCount: Int = 0,
    val geofences: List<Geofence> = emptyList(),
    // Reflejo de UserPreferencesDataStore.showGeofences, apagado por defecto (ver MapViewModel).
    val showGeofences: Boolean = false,
    // Reflejo de UserPreferencesDataStore.mapType, NORMAL por defecto (ver MapViewModel). Solo
    // afecta el mapa en vivo -- el mapa del Historial (GoogleRouteMapEngine) no lo lee.
    val mapType: MapType = MapType.NORMAL,
    // Reflejo de UserPreferencesDataStore.showTail, activado por defecto (ver MapViewModel). Apagado:
    // no se dibuja la cola ni se encuadra con ella.
    val showTail: Boolean = true,
    // Distancia/conducción/detenido + dirección de la hoja inferior: no vienen en devices/map
    // (ver Asset), así que llegan después de abrir la hoja (device/{id} history + geocodificación,
    // mismo camino que AssetDetailViewModel) -- la hoja nunca espera a esto para mostrarse.
    val isSelectedAssetStatsLoading: Boolean = false,
    val selectedAssetStats: List<UnitStat> = emptyList(),
    val isSelectedAssetAddressLoading: Boolean = false,
    val selectedAssetAddress: String? = null,
    // null = mapa normal, fuera de modo dibujo. Ver GeofenceCreationState.
    val geofenceCreation: GeofenceCreationState? = null,
    // null = hoja de borrado cerrada. Ver GeofenceDeletionState.
    val geofenceDeletion: GeofenceDeletionState? = null,
    // null = vista normal con toda la flota. Ver TodayTrackState.
    val todayTrack: TodayTrackState? = null,
    // Sube al salir del modo "Recorrido de hoy": MapScreen encuadra toda la flota una vez por valor,
    // ya con el padding de la vista normal aplicado (ver MapScreen).
    val fleetFitRequestId: Int = 0
) {
    // Sugerencias bajo el buscador mientras se escribe (máx. MAX_SEARCH_SUGGESTIONS): las mismas
    // unidades que el filtro deja visibles en el mapa, así que respetan el chip activo.
    val searchSuggestions: List<Asset>
        get() = if (searchQuery.isBlank()) emptyList() else visibleAssets.take(MAX_SEARCH_SUGGESTIONS)

    val trackedAsset: Asset?
        get() = todayTrack?.let { track -> assets.firstOrNull { it.id == track.assetId } }

    val visibleAssets: List<Asset>
        get() = assets
            .filter(activeFilter::matches)
            .filter { asset ->
                searchQuery.isBlank() || asset.name?.contains(searchQuery, ignoreCase = true) == true
            }

    val selectedAsset: Asset?
        get() = selectedAssetId?.let { id -> assets.firstOrNull { it.id == id } }

    // Anillo de resaltado del mapa: sigue selectedAssetId mientras la hoja está abierta, y cae a
    // focusedAssetId cuando el foco viene de "Ver en mapa" sin hoja (ver MapScreen.selectedMarkerId).
    val highlightedAssetId: Int?
        get() = selectedAssetId ?: focusedAssetId

    val highlightedAsset: Asset?
        get() = highlightedAssetId?.let { id -> assets.firstOrNull { it.id == id } }

    val visibleGeofences: List<Geofence>
        get() = if (showGeofences) geofences else emptyList()
}

enum class GeofenceDrawType { POLYGON, CIRCLE }

/**
 * Estilo plano (como AssetDetailUiState), no una jerarquía sellada: el borrador se actualiza
 * incrementalmente con cada tap/cambio de slider, y copy() sobre campos planos es más simple que
 * reconstruir un sealed type en cada paso.
 */
data class GeofenceCreationState(
    val type: GeofenceDrawType? = null,
    val polygonVertices: List<GeoPoint> = emptyList(),
    val circleCenter: GeoPoint? = null,
    val circleRadiusMeters: Double = GeofenceRadiusRange.DEFAULT_METERS,
    val showForm: Boolean = false,
    val name: String = "",
    val colorHex: String = GeofenceColorPalette.default,
    val speedLimitInput: String = "",
    val isSaving: Boolean = false,
    val fieldErrors: Map<String, List<String>> = emptyMap(),
    val hasGeneralError: Boolean = false
) {
    val canConfirmShape: Boolean
        get() = when (type) {
            GeofenceDrawType.POLYGON -> polygonVertices.size >= 3
            GeofenceDrawType.CIRCLE -> circleCenter != null && circleRadiusMeters > 0
            null -> false
        }
}

/**
 * Resultado de guardar, para mostrar una sola vez (Snackbar en MapScreen) -- mismo patrón que
 * CommandResultEvent en AssetDetailUiState. Los errores de campo (422) NO viajan acá: viven en
 * GeofenceCreationState.fieldErrors, persistentes, para el formulario.
 */
sealed class GeofenceCreateEvent {
    data object Success : GeofenceCreateEvent()
    data object GeneralError : GeofenceCreateEvent()
}

/**
 * Estado de la hoja de borrado -- la lista misma vive en MapUiState.geofences (sin refetch, ver
 * GeofenceDeleteSheet), esto solo trackea qué fila tiene el diálogo de confirmación abierto y
 * cuál tiene el DELETE en curso. Nunca hay más de un borrado en vuelo a la vez.
 */
data class GeofenceDeletionState(
    val pendingDeleteId: Int? = null,
    val deletingId: Int? = null
)

/** Resultado de borrar, para mostrar una sola vez (Snackbar en MapScreen) -- mismo patrón que
 * GeofenceCreateEvent. Un error no cierra la hoja: el usuario puede reintentar. */
sealed class GeofenceDeleteEvent {
    data object Success : GeofenceDeleteEvent()
    data object Error : GeofenceDeleteEvent()
}

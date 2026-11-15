package pe.soltelematic.mobile.ui.history

import pe.soltelematic.mobile.core.result.ApiError
import pe.soltelematic.mobile.domain.model.AssetIcon
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.domain.model.SpeedRanges


/**
 * addresses es por legIndex, no por coordenada -- eso vive en el caché de sesión de
 * HistoryViewModel. Acá es puramente el estado de la fila: ausente = todavía no visible en
 * pantalla (no se ha pedido nada), Loading = pedida y en vuelo, Resolved = terminó (address
 * puede ser null si el servidor no encontró nada).
 */
data class HistoryUiState(
    val dateRange: HistoryDateRange = HistoryDateRange.today(),
    val isLoading: Boolean = true,
    val route: HistoryRoute? = null,
    val mapData: RouteMapData? = null,
    val error: ApiError? = null,
    // Espejo de la preferencia global (UserPreferencesDataStore.mapType), la misma que usa
    // MapScreen: el historial no guarda un tipo propio, solo observa el compartido. NORMAL como
    // valor inicial mientras el DataStore emite el primero.
    val mapType: MapType = MapType.NORMAL,
    // Reflejo de UserPreferencesDataStore.speedRanges (límites de color de la línea por velocidad).
    val speedRanges: SpeedRanges = SpeedRanges.DEFAULT,
    val selectedLegIndex: Int? = null,
    val addresses: Map<Int, AddressResolution> = emptyMap(),
    // Puntos GPS originales (sin simplificar, ver HistoryRouteMapMapper.toPlaybackPoints) de todos
    // los viajes del día, ya en memoria desde la misma respuesta de getRoute -- la reproducción no
    // dispara ninguna llamada de red propia.
    val playbackPoints: List<HistoryPlaybackPoint> = emptyList(),
    // Rumbo por punto, lista paralela a playbackPoints -- ver HistoryRouteMapMapper.toBearings.
    // Calculado una sola vez junto con playbackPoints al cargar la ruta, nunca en cada tick.
    val playbackBearings: List<Float> = emptyList(),
    // Icono de la unidad que se está viendo, para el marcador de reproducción (ver
    // RouteMapEngine.Content) -- viene de Room vía AssetRepository (mismo dato que ya usa el mapa
    // en vivo), no de una llamada de red propia de Historial. Null hasta que ese observeAssets()
    // emita al menos una vez, o si la unidad no aparece ahí -- el engine lo trata igual que "sin
    // icono propio" (caso C).
    val unitIcon: AssetIcon? = null,
    val playback: HistoryPlaybackState = HistoryPlaybackState()
)

sealed interface AddressResolution {
    data object Loading : AddressResolution
    data class Resolved(val address: String?) : AddressResolution
}

/**
 * currentIndex indexa playbackPoints (no HistoryRoute.legs) -- 0..playbackPoints.lastIndex.
 * speedMultiplier es siempre uno de 1/2/4/8 (ver HistoryViewModel.onSpeedMultiplierCycled), nunca
 * un valor libre.
 */
data class HistoryPlaybackState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val speedMultiplier: Int = 1
)

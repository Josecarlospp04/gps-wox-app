package pe.soltelematic.mobile.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.domain.model.Asset
import pe.soltelematic.mobile.domain.model.AssetFilter
import pe.soltelematic.mobile.domain.model.AssetStatusType
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.ui.components.AssetFilterChipsRow
import pe.soltelematic.mobile.ui.components.AssetSearchBar
import pe.soltelematic.mobile.ui.map.engine.GeofenceDraftPreview
import pe.soltelematic.mobile.ui.map.engine.MapCameraController
import pe.soltelematic.mobile.ui.map.engine.MapEngine
import pe.soltelematic.mobile.ui.map.engine.MapMarkerData
import pe.soltelematic.mobile.ui.map.engine.MapTailData
import pe.soltelematic.mobile.ui.map.engine.RouteOverlay
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors
import pe.soltelematic.mobile.ui.theme.SoltelematicColors
import pe.soltelematic.mobile.ui.theme.SoltelematicElevation
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

@OptIn(FlowPreview::class)
@Composable
fun MapScreen(
    onOpenAssetDetail: (Int) -> Unit,
    onOpenHistory: (Int) -> Unit,
    onOpenEvents: () -> Unit,
    viewModel: MapViewModel = koinViewModel(),
    mapEngine: MapEngine = koinInject()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    val cameraController = mapEngine.rememberCameraController()

    // Foco pedido desde la ficha ("Ver en mapa", ver MapFocusRequestBus). Va en un LaunchedEffect y
    // NO en el init del ViewModel: puede haber dos MapViewModel vivos a la vez y solo la pantalla
    // visible tiene efecto activo. Declarado ANTES del colector de autoFitCamera a propósito: el
    // encuadre de flota es un SharedFlow sin replay y no debe colarse antes del foco.
    LaunchedEffect(Unit) {
        viewModel.consumeFocusRequests()
    }

    // Altura que la hoja inferior tapa del mapa: borde superior de la hoja (ventana) y borde
    // inferior del mapa (ventana). Ver mapContentPadding.
    var sheetTopPx by remember { mutableStateOf<Float?>(null) }
    var mapBottomPx by remember { mutableIntStateOf(0) }
    // Padding inferior que el mapa YA tiene aplicado (se escribe en un SideEffect tras componer
    // mapEngine.Content): el encuadre espera a esto, no solo a que la hoja esté medida.
    var appliedOverlapPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.autoFitCamera.collect { positions -> cameraController.fitAll(positions) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val geofenceCreatedMessage = stringResource(R.string.map_geofence_created_message)
    val geofenceErrorMessage = stringResource(R.string.map_geofence_generic_error)
    val geofenceDeletedMessage = stringResource(R.string.map_geofence_delete_success_message)
    val geofenceDeleteErrorMessage = stringResource(R.string.map_geofence_delete_error_message)

    LaunchedEffect(Unit) {
        viewModel.geofenceCreateEvent.collect { event ->
            val message = when (event) {
                GeofenceCreateEvent.Success -> geofenceCreatedMessage
                GeofenceCreateEvent.GeneralError -> geofenceErrorMessage
            }
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.geofenceDeleteEvent.collect { event ->
            val message = when (event) {
                GeofenceDeleteEvent.Success -> geofenceDeletedMessage
                GeofenceDeleteEvent.Error -> geofenceDeleteErrorMessage
            }
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // Cancela el modo dibujo en vez de salir de la pantalla. No hace falta lógica adicional para
    // cuando el formulario está abierto: ModalBottomSheet ya consume el back para cerrarse
    // mientras está visible (ver CreateGeofenceFormSheet), así que este handler solo se alcanza
    // cuando no hay ninguna hoja tapándolo.
    BackHandler(enabled = uiState.geofenceCreation != null) {
        viewModel.onCancelGeofenceCreation()
    }

    // Modo "Recorrido de hoy": Atrás pliega el panel si está desplegado y, si no, sale del modo (ver
    // TodayTrackState.afterBack). No coexiste con el modo dibujo (entrar a dibujar sale del modo,
    // ver MapViewModel).
    val todayTrack = uiState.todayTrack
    val trackedAssetId = todayTrack?.assetId
    val isTracking = todayTrack != null
    BackHandler(enabled = isTracking && uiState.geofenceCreation == null) {
        viewModel.onTodayTrackBack()
    }

    // Sugerencias del buscador: se ocultan al tocar el mapa y reaparecen al volver a escribir.
    var suggestionsVisible by remember { mutableStateOf(true) }
    var searchBarHeightPx by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    // Alto real de la barra de búsqueda + chips (o del toolbar de dibujo, ver más abajo) y ancho
    // real de la columna de FABs, medidos con onSizeChanged (no una constante a ojo): ambos ya
    // incluyen su propio windowInsetsPadding + padding(16.dp) de abajo, así que las safe insets
    // quedan cubiertas sin duplicar ese cálculo. Se le pasan a mapEngine.Content como
    // contentPadding para que ni los controles del SDK ni el encuadre (fitAll) dejen marcadores
    // debajo de esos overlays.
    var topOverlayHeightPx by remember { mutableIntStateOf(0) }
    var fabColumnWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Parte de la hoja que cae sobre el mapa (0 sin hoja). El padding del SDK además deja el logo
    // de Google por encima de la hoja.
    val sheetOverlapPx = sheetTopPx?.let { top -> (mapBottomPx - top).toInt().coerceAtLeast(0) } ?: 0
    val mapContentPadding = remember(topOverlayHeightPx, fabColumnWidthPx, sheetOverlapPx, density) {
        with(density) {
            PaddingValues(top = topOverlayHeightPx.toDp(), end = fabColumnWidthPx.toDp(), bottom = sheetOverlapPx.toDp())
        }
    }

    // Al enfocar una unidad (marcador, Unidades o "Ver en mapa"): encuadre de cola + posición dentro
    // de la zona visible (ver MapCameraController.frame). Llave = pedido de enfoque, no la
    // posición: los sondeos no re-encuadran, solo siguen (efecto de abajo).
    val focusRequestId = uiState.focusRequestId
    LaunchedEffect(focusRequestId) {
        if (focusRequestId == 0) return@LaunchedEffect
        val sheetOpen = viewModel.uiState.value.selectedAssetId != null
        // Antes de animar: esperar a que el padding ya aplicado al mapa (recomposición) refleje el
        // estado de la hoja -- asentada y medida si está abierta, en 0 si no; si no, el encuadre usa
        // el padding viejo. Con tope de espera para no quedar colgado si la hoja no reporta.
        withTimeoutOrNull(2000) {
            snapshotFlow { appliedOverlapPx }.first { if (sheetOpen) it > 0 else it == 0 }
        }
        withFrameNanos { }
        val state = viewModel.uiState.value
        // Pedido ya superado (p. ej. al volver a la pantalla, consumeFocusRequests lo reemplazó):
        // el efecto con la llave nueva ya viene en camino.
        if (state.focusRequestId != focusRequestId) return@LaunchedEffect
        val asset = state.highlightedAsset ?: return@LaunchedEffect
        // Sin cola (ajuste de Cuenta) solo cuenta la unidad: frame() con un punto la centra con
        // DEFAULT_ZOOM dentro de la zona visible.
        cameraController.frame(if (state.showTail) asset.framePoints() else listOfNotNull(asset.position))
    }

    // Modo "Recorrido de hoy": encuadra el recorrido completo (o solo la unidad mientras carga / sin
    // recorrido) una vez por pedido -- llave = unidad + frameRequestId, nunca la posición. Espera a
    // que el panel inferior esté medido y asentado (su borde superior ya es padding del mapa) y,
    // dos cuadros, a que el overlay superior y ese padding se apliquen; si no, el encuadre usaba el
    // padding viejo.
    val trackFrameId = todayTrack?.frameRequestId ?: 0
    LaunchedEffect(trackedAssetId, trackFrameId) {
        if (trackedAssetId == null) return@LaunchedEffect
        awaitPanelSettled { sheetTopPx }
        withFrameNanos { }
        withFrameNanos { }
        val state = viewModel.uiState.value
        val track = state.todayTrack ?: return@LaunchedEffect
        if (track.assetId != trackedAssetId || track.frameRequestId != trackFrameId) return@LaunchedEffect
        cameraController.frame(track.framePoints(state.trackedAsset?.position))
    }

    // Tramo tocado en el panel: el panel ya se plegó (ver withLegSelected); se espera a que su
    // borde nuevo se asiente y se encuadra el tramo (punto para una parada, trazado para un
    // trayecto) dentro de la zona visible. Una vez por pedido.
    val legFocusId = todayTrack?.legFocusRequestId ?: 0
    LaunchedEffect(trackedAssetId, legFocusId) {
        if (trackedAssetId == null || legFocusId == 0) return@LaunchedEffect
        awaitPanelSettled { sheetTopPx }
        withFrameNanos { }
        withFrameNanos { }
        val track = viewModel.uiState.value.todayTrack ?: return@LaunchedEffect
        if (track.assetId != trackedAssetId || track.legFocusRequestId != legFocusId) return@LaunchedEffect
        track.selectedLegFramePoints()?.let(cameraController::frame)
    }

    // Tocar la unidad seguida: centra la cámara en ella conservando el zoom actual.
    val trackCenterId = todayTrack?.centerRequestId ?: 0
    LaunchedEffect(trackedAssetId, trackCenterId) {
        if (trackedAssetId == null || trackCenterId == 0) return@LaunchedEffect
        viewModel.uiState.value.trackedAsset?.position?.let(cameraController::followTo)
    }

    // Al salir del modo: encuadre de toda la flota, ya con el padding de la vista normal. El valor
    // inicial se captura al componer: volver a esta pantalla (cambio de pestaña) no repite el
    // encuadre de una salida anterior.
    val fleetFitId = uiState.fleetFitRequestId
    val initialFleetFitId = remember { fleetFitId }
    LaunchedEffect(fleetFitId) {
        if (fleetFitId == initialFleetFitId) return@LaunchedEffect
        // El panel ya salió de composición: esperar a que el mapa pierda su padding inferior.
        withTimeoutOrNull(1000) {
            snapshotFlow { appliedOverlapPx }.first { it == 0 }
        }
        withFrameNanos { }
        withFrameNanos { }
        cameraController.fitAll(viewModel.uiState.value.assets.mapNotNull { it.position })
    }

    // Seguimiento: en cada sondeo que mueve a la unidad enfocada, la cámara la sigue manteniendo el
    // zoom. No se anima si la posición no cambió ni al cambiar de unidad (eso lo encuadra el efecto
    // de arriba); tampoco al volver a seguir, cuando solo cambia isFollowing.
    val highlightedId = uiState.highlightedAssetId
    val highlightedPosition = uiState.highlightedAsset?.position
    val isFollowing = uiState.isFollowing
    val lastHighlighted = remember { LastHighlighted() }
    LaunchedEffect(highlightedId, highlightedPosition, isFollowing) {
        val previousId = lastHighlighted.id
        val previousPosition = lastHighlighted.position
        lastHighlighted.id = highlightedId
        lastHighlighted.position = highlightedPosition
        if (!isFollowing || highlightedId == null || highlightedPosition == null) return@LaunchedEffect
        if (previousId != highlightedId || previousPosition == highlightedPosition) return@LaunchedEffect
        cameraController.followTo(highlightedPosition)
    }

    // remember(uiState.visibleAssets, solColors): Asset es data class, así que dos listas con el
    // mismo contenido son iguales -- un refresco que no cambia nada no reconstruye los
    // marcadores. solColors entra también como llave porque el color de estado se resuelve acá
    // (statusColorArgb, ver MapMarkerData): un cambio de tema claro/oscuro debe reconstruir los
    // marcadores para que MarkerIconCache regenere sus bitmaps con el color correcto.
    val solColors = LocalSoltelematicColors.current
    // En el modo "Recorrido de hoy" solo se dibuja la unidad seguida, sin importar chip ni texto del
    // buscador (ese texto sirve para elegir otra unidad, no para ocultar la seguida).
    val markerAssets = if (isTracking) uiState.assets else uiState.visibleAssets
    val allMarkers = remember(markerAssets, solColors) {
        markerAssets.mapNotNull { asset ->
            val position = asset.position ?: return@mapNotNull null
            MapMarkerData(
                id = asset.id,
                position = position,
                title = asset.name.orEmpty(),
                iconUrl = asset.icon.url,
                statusColorArgb = asset.status.type.toMarkerStatusColor(solColors).toArgb(),
                dimmed = asset.status.type == AssetStatusType.OFFLINE
            )
        }
    }
    val markers = remember(allMarkers, trackedAssetId) { allMarkers.onlyTracked(trackedAssetId) }

    // Misma instancia mientras no cambie el recorrido: el engine memoiza la simplificación por
    // identidad de la lista de polilíneas.
    val trackSelectedLeg = todayTrack?.selectedLegIndex
    val routeOverlay = remember(todayTrack?.mapData, trackSelectedLeg) {
        todayTrack?.mapData?.let {
            RouteOverlay(polylines = it.polylines, markers = it.markers, selectedLegIndex = trackSelectedLeg)
        }
    }

    val visibleFilters = remember(uiState.hasBlockedAssets) {
        if (uiState.hasBlockedAssets) AssetFilter.entries.toList() else AssetFilter.entries - AssetFilter.BLOCKED
    }

    // Puramente de presentación (cuántas unidades caen en cada chip): reutiliza
    // AssetFilter.matches, ya usado por MapUiState.visibleAssets, así que el criterio de cada
    // chip sigue siendo el único definido ahí -- esto solo formatea el número visible.
    val filterCounts = remember(uiState.assets) {
        AssetFilter.entries.associateWith { filter -> uiState.assets.count(filter::matches) }
    }

    val geofenceCreation = uiState.geofenceCreation
    val isDrawingGeofence = geofenceCreation != null

    // Cola SOLO de la unidad resaltada (hoja abierta o foco de "Ver en mapa"). Se recalcula con
    // cada sondeo porque sale de uiState.assets.
    val brandColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val highlightedAsset = uiState.highlightedAsset
    val showTail = uiState.showTail
    val tailData = remember(highlightedAsset, brandColorArgb, isDrawingGeofence, showTail) {
        if (!showTail || isDrawingGeofence || highlightedAsset == null || highlightedAsset.tail.isEmpty()) return@remember null
        val points = highlightedAsset.framePoints()
        val color = highlightedAsset.tailColorHex
            ?.takeIf { it.isNotBlank() }
            ?.let { hex -> runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() }
            ?: brandColorArgb
        MapTailData(points, color)
    }

    SideEffect { appliedOverlapPx = sheetOverlapPx }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> mapBottomPx = (coords.positionInWindow().y + coords.size.height).toInt() }
    ) {
        mapEngine.Content(
            modifier = Modifier.fillMaxSize(),
            cameraController = cameraController,
            // Ocultos por completo en modo dibujo (no solo deshabilitados): descarga visual y
            // garantiza que todo tap llegue a onMapClick, sin que un ícono de unidad se robe el
            // toque -- ver spec, sección "Durante el modo dibujo".
            markers = if (isDrawingGeofence) emptyList() else markers,
            selectedMarkerId = uiState.highlightedAssetId,
            myLocationEnabled = hasLocationPermission,
            geofences = uiState.visibleGeofences,
            onMarkerClick = when {
                isDrawingGeofence -> { _ -> }
                // Único marcador visible en el modo: tocarlo centra la cámara, no abre la hoja.
                isTracking -> { _ -> viewModel.onTrackedAssetTapped() }
                else -> viewModel::onAssetSelected
            },
            onMapClick = { point ->
                if (isDrawingGeofence) {
                    viewModel.onGeofenceMapTapped(point)
                } else {
                    suggestionsVisible = false
                    // En el modo no hay hoja ni enfoque que cerrar: tocar el mapa vacío no sale.
                    if (!isTracking) viewModel.onBottomSheetDismissed()
                }
            },
            contentPadding = mapContentPadding,
            mapType = uiState.mapType,
            draft = geofenceCreation?.let { creation ->
                when (creation.type) {
                    GeofenceDrawType.POLYGON -> GeofenceDraftPreview.Polygon(creation.polygonVertices)
                    GeofenceDrawType.CIRCLE -> GeofenceDraftPreview.Circle(creation.circleCenter, creation.circleRadiusMeters)
                    null -> null
                }
            },
            tail = tailData,
            route = routeOverlay,
            onCameraGesture = viewModel::onCameraGesture
        )

        // El mapa dibuja a pantalla completa (enableEdgeToEdge en MainActivity), pero estos
        // overlays son interactivos: sin windowInsetsPadding quedan bajo la barra de estado /
        // barra de navegación del sistema -- en algunos dispositivos eso no es solo estético,
        // el sistema le gana el toque a la app en esa franja (ver FAB de debug del Bloque 7).
        Column(
            // onSizeChanged primero en la cadena (no al final): así mide el tamaño final del
            // nodo, después de que windowInsetsPadding y padding(16dp) ya sumaron lo suyo, en vez
            // del tamaño del contenido interno sin esos márgenes.
            modifier = Modifier
                .onSizeChanged { size -> topOverlayHeightPx = size.height }
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(SoltelematicSpacing.lg)
        ) {
            if (geofenceCreation != null) {
                GeofenceDrawToolbar(
                    state = geofenceCreation,
                    onTypeSelected = viewModel::onGeofenceTypeSelected,
                    onUndo = viewModel::onUndoLastGeofenceVertex,
                    onClear = viewModel::onClearGeofenceDraft,
                    onRadiusChanged = viewModel::onGeofenceCircleRadiusChanged,
                    onCancel = viewModel::onCancelGeofenceCreation,
                    onConfirm = viewModel::onConfirmGeofenceShape
                )
            } else {
                // Una sola pieza (ver mockup): antes la campana y el avatar de cuenta flotaban aparte.
                // El avatar se fue del todo (ahora vive en el bottom nav, ver SoltelematicNavHost) --
                // la campana se queda, ahora dentro del mismo Surface que el buscador.
                MapSearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = { query ->
                        suggestionsVisible = true
                        viewModel.onSearchQueryChange(query)
                    },
                    onSearch = {
                        focusManager.clearFocus()
                        viewModel.onSearchSubmit()
                    },
                    onOpenEvents = onOpenEvents,
                    showUnreadDot = uiState.unseenEventsCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { size -> searchBarHeightPx = size.height }
                )
                Spacer(modifier = Modifier.height(SoltelematicSpacing.sm))
                if (todayTrack != null) {
                    TodayTrackChip(
                        unitName = uiState.trackedAsset?.name.orEmpty(),
                        onExit = viewModel::onExitTodayTrack,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    AssetFilterChipsRow(
                        filters = visibleFilters,
                        activeFilter = uiState.activeFilter,
                        counts = filterCounts,
                        onFilterSelected = viewModel::onFilterSelected
                    )
                }
            }
        }

        // Sugerencias del buscador: overlay aparte (no dentro de la columna superior) para que su
        // altura no entre en topOverlayHeightPx -- si no, cada tecla movería el padding del mapa.
        val suggestions = uiState.searchSuggestions
        if (!isDrawingGeofence && suggestionsVisible && suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(SoltelematicSpacing.lg)
            ) {
                Spacer(modifier = Modifier.height(with(density) { searchBarHeightPx.toDp() } + SoltelematicSpacing.xs))
                SearchSuggestionsList(
                    assets = suggestions,
                    onSelect = { id ->
                        focusManager.clearFocus()
                        viewModel.onTrackTodaySelected(id)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Panel del modo: su borde superior (ya asentado) entra en sheetTopPx -> padding del mapa.
        if (todayTrack != null && !isDrawingGeofence) {
            TodayTrackPanel(
                track = todayTrack,
                onExpandedChange = viewModel::onTodayPanelExpandedChange,
                onLegClick = viewModel::onTodayLegSelected,
                onStopRowVisible = viewModel::onTodayStopRowVisible,
                onRetry = viewModel::onTodayTrackRetry,
                onOpenHistory = { onOpenHistory(todayTrack.assetId) },
                onSheetTopChanged = { sheetTopPx = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // En el modo, los FAB suben con el panel plegado y se ocultan con el panel desplegado (una
        // lista alta no deja sitio para 4 botones entre ella y la barra superior).
        val panelLift = if (isTracking) with(density) { sheetOverlapPx.toDp() } else 0.dp
        val fabsVisible = !isDrawingGeofence && todayTrack?.panelExpanded != true
        if (fabsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = panelLift)
                    .onSizeChanged { size -> fabColumnWidthPx = size.width }
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(SoltelematicSpacing.lg)
            ) {
                MapFab(
                    onClick = {
                        viewModel.onStopFollowing()
                        // En el modo, "ajustar" vuelve a encuadrar el recorrido de hoy.
                        if (isTracking) viewModel.onTrackReframe() else cameraController.fitAll(markers.map { it.position })
                    },
                    icon = Icons.Filled.ZoomOutMap,
                    contentDescription = stringResource(R.string.map_fit_all)
                )
                Spacer(modifier = Modifier.height(SoltelematicSpacing.md))
                MapFab(
                    onClick = {
                        if (hasLocationPermission) {
                            viewModel.onStopFollowing()
                            centerOnMyLocation(context, cameraController)
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    icon = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.map_center_my_location)
                )
                Spacer(modifier = Modifier.height(SoltelematicSpacing.md))
                GeofencesFab(
                    showGeofences = uiState.showGeofences,
                    onToggleVisibility = viewModel::onToggleGeofencesVisibility,
                    onStartCreation = viewModel::onStartGeofenceCreation,
                    onStartDeletion = viewModel::onStartGeofenceDeletion
                )
                Spacer(modifier = Modifier.height(SoltelematicSpacing.md))
                MapTypeFab(
                    mapType = uiState.mapType,
                    onMapTypeSelected = viewModel::onMapTypeSelected
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = panelLift)
        )
    }

    if (!isDrawingGeofence) {
        uiState.selectedAsset?.let { asset ->
            AssetBottomSheet(
                asset = asset,
                stats = uiState.selectedAssetStats,
                isStatsLoading = uiState.isSelectedAssetStatsLoading,
                address = uiState.selectedAssetAddress,
                isAddressLoading = uiState.isSelectedAssetAddressLoading,
                onDismiss = viewModel::onBottomSheetClosed,
                onOpenDetail = { onOpenAssetDetail(asset.id) },
                onOpenHistory = { onOpenHistory(asset.id) },
                onSheetTopChanged = { sheetTopPx = it }
            )
        }
    }

    geofenceCreation?.let { creation ->
        if (creation.showForm) {
            CreateGeofenceFormSheet(
                state = creation,
                onNameChanged = viewModel::onGeofenceNameChanged,
                onColorSelected = viewModel::onGeofenceColorSelected,
                onSpeedLimitChanged = viewModel::onGeofenceSpeedLimitInputChanged,
                onDismiss = viewModel::onGeofenceFormDismissed,
                onSave = viewModel::onSaveGeofence
            )
        }
    }

    uiState.geofenceDeletion?.let { deletion ->
        GeofenceDeleteSheet(
            geofences = uiState.geofences,
            deletion = deletion,
            onDeleteRequested = viewModel::onGeofenceDeleteRequested,
            onDeleteConfirmed = viewModel::onGeofenceDeleteConfirmed,
            onDeleteCancelled = viewModel::onGeofenceDeleteCancelled,
            onDismiss = viewModel::onGeofenceDeletionDismissed
        )
    }
}

/**
 * Espera a que el borde superior del panel inferior esté medido y no cambie durante un rato (el
 * panel publica ya asentado, pero un plegado/desplegado reciente tarda unos cuadros). Con tope de
 * espera para no colgar el encuadre si el panel nunca reporta.
 */
@OptIn(FlowPreview::class)
private suspend fun awaitPanelSettled(top: () -> Float?) {
    withTimeoutOrNull(1500) {
        snapshotFlow(top).debounce(300).first { it != null }
    }
}

/** Última unidad enfocada y posición vistas por el efecto de seguimiento; no son estado de UI. */
private class LastHighlighted {
    var id: Int? = null
    var position: GeoPoint? = null
}

/**
 * Una sola pieza (ver mockup, Bloque de rediseño): estrella de marca a la izquierda, placeholder
 * en el medio (una sola línea, ver maxLines abajo -- antes se partía en dos), campana + punto rojo
 * de no vistos a la derecha, todo dentro del mismo Surface. El avatar de cuenta que vivía acá se
 * fue por completo al bottom nav (ver SoltelematicNavHost).
 */
@Composable
private fun MapSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenEvents: () -> Unit,
    showUnreadDot: Boolean,
    modifier: Modifier = Modifier
) {
    // Chrome compartido con Unidades (ver ui/components/AssetListControls.kt) -- acá se le agrega
    // la campana + punto de no vistos, que solo tiene sentido en el mapa.
    AssetSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        modifier = modifier,
        onSearch = onSearch,
        trailingContent = {
            Box {
                IconButton(onClick = onOpenEvents) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.events_bell_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showUnreadDot) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(SoltelematicSpacing.xs)
                            .size(SoltelematicSpacing.sm)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                }
            }
        }
    )
}

@Composable
private fun MapFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    active: Boolean = false
) {
    FloatingActionButton(
        onClick = onClick,
        shape = SoltelematicShapes.medium,
        containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = SoltelematicElevation.e2,
            pressedElevation = SoltelematicElevation.e2
        )
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * FAB + DropdownMenu anclado, mismo patrón que MapTypeFab: "Mostrar geocercas" (con check si
 * showGeofences está activo) + "Crear geocerca". El tinte del FAB sigue reflejando showGeofences
 * (no si el menú está abierto) -- esa señal no se pierde a simple vista con el cambio de un tap
 * directo a un menú.
 */
@Composable
private fun GeofencesFab(
    showGeofences: Boolean,
    onToggleVisibility: () -> Unit,
    onStartCreation: () -> Unit,
    onStartDeletion: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MapFab(
            onClick = { expanded = true },
            icon = if (showGeofences) Icons.Filled.Layers else Icons.Filled.LayersClear,
            contentDescription = stringResource(R.string.map_geofence_fab_content_description),
            active = showGeofences
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_toggle_geofences)) },
                trailingIcon = {
                    if (showGeofences) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = {
                    onToggleVisibility()
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_geofence_create_menu_item)) },
                onClick = {
                    onStartCreation()
                    expanded = false
                }
            )
            // Nunca deshabilitado aunque la lista esté vacía (ver GeofenceDeleteSheet): un ítem de
            // menú apagado no explica por qué, abrir la hoja y ver el estado vacío sí.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_geofence_delete_menu_item)) },
                onClick = {
                    onStartDeletion()
                    expanded = false
                }
            )
        }
    }
}

/**
 * FAB + DropdownMenu anclado (no bottom sheet): con solo 4 opciones cortas, un menú anclado
 * mantiene la relación espacial con el botón que lo abrió y no exige el chrome de una hoja
 * inferior (scrim a pantalla completa, drag handle) -- ese componente ya existe en esta pantalla
 * (AssetBottomSheet) para contenido más largo y desplazable, no para un picker de 4 filas.
 */
@Composable
private fun MapTypeFab(mapType: MapType, onMapTypeSelected: (MapType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MapFab(
            onClick = { expanded = true },
            icon = Icons.Filled.Map,
            contentDescription = stringResource(R.string.map_type_button_content_description),
            active = expanded
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MapType.entries.forEach { option ->
                val selected = option == mapType
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.toLabel(),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        onMapTypeSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MapType.toLabel(): String = when (this) {
    MapType.NORMAL -> stringResource(R.string.map_type_normal)
    MapType.SATELLITE -> stringResource(R.string.map_type_satellite)
    MapType.HYBRID -> stringResource(R.string.map_type_hybrid)
    MapType.TERRAIN -> stringResource(R.string.map_type_terrain)
}

// Mismo mapeo de 4 colores que SummaryTab.statusPillColors/EventCard.toColors -- se duplica acá
// (no vale la pena un archivo util por 4 líneas, convención del proyecto) en vez de tomar el
// colorHex crudo del servidor: la píldora del mapa usa el color de ESTADO, no el que manda cada
// unidad.
private fun AssetStatusType.toMarkerStatusColor(colors: SoltelematicColors): Color = when (this) {
    AssetStatusType.ONLINE -> colors.statusMoving
    AssetStatusType.ENGINE, AssetStatusType.ACK -> colors.statusIdle
    AssetStatusType.BLOCKED -> colors.statusAlert
    AssetStatusType.OFFLINE, AssetStatusType.UNKNOWN -> colors.statusOffline
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

// El permiso ya se valida antes de llamar (botón o resultado del launcher), pero el lint no
// puede verlo a través de esa indirección.
@SuppressLint("MissingPermission")
private fun centerOnMyLocation(context: Context, cameraController: MapCameraController) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val location = locationManager.allProviders
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
    location?.let { cameraController.centerOn(GeoPoint(it.latitude, it.longitude)) }
}

/**
 * Puntos a encuadrar y a dibujar: la cola (antigua -> reciente) más la posición actual si difiere
 * del último punto, para que la línea llegue al marcador aunque el servidor aún no la haya añadido.
 * Sin cola, solo la posición.
 */
private fun Asset.framePoints(): List<GeoPoint> {
    val current = position
    return when {
        current == null -> tail
        tail.isEmpty() -> listOf(current)
        current != tail.last() -> tail + current
        else -> tail
    }
}

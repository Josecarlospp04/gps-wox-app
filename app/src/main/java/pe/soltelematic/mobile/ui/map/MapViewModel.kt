package pe.soltelematic.mobile.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.soltelematic.mobile.core.navigation.MapFocusRequestBus
import pe.soltelematic.mobile.core.network.RealtimePoller
import pe.soltelematic.mobile.core.network.SocketRealtimeClient
import pe.soltelematic.mobile.core.network.UnseenEventsPoller
import pe.soltelematic.mobile.core.result.ApiError
import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.core.storage.UserPreferencesDataStore
import pe.soltelematic.mobile.domain.model.Asset
import pe.soltelematic.mobile.domain.model.AssetFilter
import pe.soltelematic.mobile.domain.model.AssetStatusType
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.GeofenceCreateRequest
import pe.soltelematic.mobile.domain.model.GeofenceShape
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.domain.repository.AssetDetailRepository
import pe.soltelematic.mobile.domain.repository.AssetRepository
import pe.soltelematic.mobile.domain.repository.GeofencesRepository
import pe.soltelematic.mobile.ui.history.AddressResolution
import pe.soltelematic.mobile.ui.history.toRouteMapData

class MapViewModel(
    private val assetRepository: AssetRepository,
    private val realtimePoller: RealtimePoller,
    private val socketRealtimeClient: SocketRealtimeClient,
    private val unseenEventsPoller: UnseenEventsPoller,
    private val geofencesRepository: GeofencesRepository,
    private val userPreferences: UserPreferencesDataStore,
    // Mismo repositorio que ya usa AssetDetailViewModel para "HOY" -- la hoja inferior del mapa
    // reutiliza device/{id}+history y la geocodificación en vez de duplicar ese camino de red.
    private val assetDetailRepository: AssetDetailRepository,
    private val mapFocusRequestBus: MapFocusRequestBus
) : ViewModel() {

    // Caché de direcciones por coordenada exacta, mismo patrón que HistoryViewModel/EventsViewModel:
    // vive mientras viva este ViewModel (una entrada al mapa), no persiste entre reaperturas.
    private val addressCache = mutableMapOf<GeoPoint, String?>()

    private var statsJob: Job? = null
    private var addressJob: Job? = null
    private var trackJob: Job? = null

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    // Se piden una sola vez por apertura de mapa (no en cada ciclo de polling): geocercas cambian
    // rara vez. El flag evita repetir la llamada cada vez que el interruptor pasa a "on".
    private var geofencesRequested = false

    // Encuadre automático UNA SOLA VEZ, cuando lleguen las primeras posiciones -- nunca en cada
    // ciclo de polling (cada 7s), o la cámara se movería sola mientras el usuario mira el mapa.
    // Vive como var de instancia (no en MapUiState): este ViewModel está scoped a la entrada del
    // NavHost para Destination.Map, y el logout hace popUpTo(...){inclusive=true} sobre todo el
    // grafo (ver SoltelematicNavHost), así que un nuevo login siempre trae una instancia nueva de
    // MapViewModel con este flag en false -- VERIFICADO en dispositivo (logout -> login -> el
    // mapa vuelve a encuadrar solo), no solo razonado sobre el papel.
    private var hasAutoFitted = false

    private val _autoFitCamera = MutableSharedFlow<List<GeoPoint>>(extraBufferCapacity = 1)
    val autoFitCamera: SharedFlow<List<GeoPoint>> = _autoFitCamera.asSharedFlow()

    // Foco pedido desde la ficha de la unidad (ver MapFocusRequestBus/focusOnAsset). Si todavía no
    // hay posiciones cargadas cuando se pide el foco (instancia de mapa recién creada, ver
    // SoltelematicNavHost -- onNavigateToMap empuja una entrada nueva de Destination.Map), queda acá
    // hasta que la primera carga de assets resuelva esa unidad.
    private var pendingFocusAssetId: Int? = null
    private var pendingFocusOpenSheet = false

    private val _geofenceCreateEvent = MutableSharedFlow<GeofenceCreateEvent>(extraBufferCapacity = 1)
    val geofenceCreateEvent: SharedFlow<GeofenceCreateEvent> = _geofenceCreateEvent.asSharedFlow()

    private val _geofenceDeleteEvent = MutableSharedFlow<GeofenceDeleteEvent>(extraBufferCapacity = 1)
    val geofenceDeleteEvent: SharedFlow<GeofenceDeleteEvent> = _geofenceDeleteEvent.asSharedFlow()

    init {
        // El foco de "Ver en mapa" NO se consume acá: puede haber dos instancias de este ViewModel
        // vivas a la vez y la vieja se lo llevaría -- ver consumeFocusRequests y MapFocusRequestBus.
        viewModelScope.launch {
            assetRepository.observeAssets().collect { assets ->
                _uiState.update {
                    it.copy(
                        assets = assets,
                        hasBlockedAssets = assets.any { asset -> asset.status.type == AssetStatusType.BLOCKED }
                    )
                }
                triggerAutoFitIfNeeded()
                resolvePendingFocusIfNeeded(assets)
            }
        }
        viewModelScope.launch {
            unseenEventsPoller.unseenCount.collect { count ->
                _uiState.update { it.copy(unseenEventsCount = count) }
            }
        }
        viewModelScope.launch {
            // Colecciona el Flow en vez de leerlo una vez con .first(): si el día de mañana el
            // interruptor también vive en un panel de ajustes, este ViewModel se mantiene
            // sincronizado sin código adicional -- no depende de ser la única fuente de escritura.
            userPreferences.showGeofences.collect { enabled ->
                _uiState.update { it.copy(showGeofences = enabled) }
                if (enabled) loadGeofencesIfNeeded()
            }
        }
        viewModelScope.launch {
            userPreferences.mapType.collect { type ->
                _uiState.update { it.copy(mapType = type) }
            }
        }
        viewModelScope.launch {
            userPreferences.showTail.collect { enabled ->
                _uiState.update { it.copy(showTail = enabled) }
            }
        }
        refresh()
        // Bloque C: devices/map (arriba) para la carga inicial, polling para lo que sigue. El
        // socket queda registrado pero SOCKET_REALTIME_ENABLED lo apaga -- start() no hace nada
        // hasta que se active esa bandera.
        realtimePoller.start(viewModelScope)
        socketRealtimeClient.start(viewModelScope)
        unseenEventsPoller.start(viewModelScope)
    }

    override fun onCleared() {
        socketRealtimeClient.stop()
    }

    fun onToggleGeofencesVisibility() {
        viewModelScope.launch {
            userPreferences.setShowGeofences(!_uiState.value.showGeofences)
        }
    }

    fun onMapTypeSelected(type: MapType) {
        viewModelScope.launch {
            userPreferences.setMapType(type)
        }
    }

    // Toda la flota (state.assets) salvo que el usuario ya haya tocado un filtro o buscado algo
    // antes de que llegue el primer refresco (arranque lento): ahí se respeta esa intención y se
    // encuadra solo lo visible (state.visibleAssets), en vez de saltar a toda la flota e ignorar
    // lo que el usuario acaba de filtrar.
    private suspend fun triggerAutoFitIfNeeded() {
        if (hasAutoFitted) return
        // Hay un foco pendiente (ver focusOnAsset): encuadrar toda la flota acá competiría por la
        // cámara con el centrado que pide ese foco -- esa carrera es justo lo que antes hacía que
        // "Ver en mapa" recentrara sin cambiar el zoom (el fitAll de la flota ganaba). Se marca
        // igual hasAutoFitted para que un ciclo de polling posterior no reintente el encuadre y
        // deshaga el foco ya resuelto.
        if (pendingFocusAssetId != null) {
            hasAutoFitted = true
            return
        }
        val state = _uiState.value
        val hasActiveFilterOrSearch = state.activeFilter != AssetFilter.ALL || state.searchQuery.isNotBlank()
        val candidateAssets = if (hasActiveFilterOrSearch) state.visibleAssets else state.assets
        val positions = candidateAssets.mapNotNull { it.position }
        if (positions.isEmpty()) return
        hasAutoFitted = true
        _autoFitCamera.emit(positions)
    }

    private fun loadGeofencesIfNeeded() {
        if (geofencesRequested) return
        geofencesRequested = true
        viewModelScope.launch {
            when (val result = geofencesRepository.getGeofences()) {
                is ApiResult.Success -> _uiState.update { it.copy(geofences = result.data) }
                // Contexto, no información crítica: un fallo acá no degrada el mapa (ver plan).
                is ApiResult.Error -> Unit
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onFilterSelected(filter: AssetFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    /**
     * La hoja se abre YA con esto (lo que ya trae Asset, de devices/map) -- distancia/conducción/
     * detenido/dirección llegan después y solo rellenan sus propios placeholders (ver
     * loadSelectedAssetStats/loadSelectedAssetAddress), nunca bloquean ni retrasan la apertura.
     */
    fun onAssetSelected(id: Int) {
        if (_uiState.value.selectedAssetId == id) {
            // Segundo toque sobre la unidad cuya hoja ya está abierta: no recarga la hoja, solo
            // vuelve a seguirla y a encuadrarla.
            _uiState.update { it.copy(isFollowing = true, focusRequestId = it.focusRequestId + 1) }
            return
        }
        _uiState.update {
            it.copy(
                selectedAssetId = id,
                // Un toque directo sobre un marcador reemplaza cualquier foco pendiente de "Ver en
                // mapa" -- solo debe quedar resaltada la unidad que el usuario acaba de tocar.
                focusedAssetId = null,
                isFollowing = true,
                focusRequestId = it.focusRequestId + 1,
                isSelectedAssetStatsLoading = true,
                selectedAssetStats = emptyList(),
                isSelectedAssetAddressLoading = true,
                selectedAssetAddress = null
            )
        }
        loadSelectedAssetStats(id)
        loadSelectedAssetAddress(id)
    }

    /**
     * Se llama desde un LaunchedEffect de MapScreen, NO desde init/viewModelScope: la pestaña Mapa y
     * el Mapa apilado por "Ver en mapa" tienen cada uno su MapViewModel vivo al mismo tiempo, y un
     * colector en viewModelScope de la instancia tapada se llevaba el foco. El efecto de la pantalla
     * tapada sí se cancela al salir de composición, así que solo la visible consume. Ver
     * MapFocusRequestBus (las dos trampas).
     */
    suspend fun consumeFocusRequests() {
        mapFocusRequestBus.request.collect { request ->
            if (request != null) {
                // Se limpia apenas se consume: volver después a la pestaña Mapa (o que esta misma
                // pantalla reinicie su efecto) no debe volver a centrar sobre esta unidad.
                mapFocusRequestBus.clear()
                focusOnAsset(request.assetId, request.openSheet)
            }
        }
    }

    /**
     * Entrada desde MapFocusRequestBus (ver consumeFocusRequests): a diferencia de tocar un
     * marcador, esto NO abre la hoja inferior -- el usuario ya viene de la ficha de la unidad, así
     * que solo hace falta centrar la cámara (con zoom cercano, ver MapCameraController.centerOn) y
     * resaltar el marcador (focusedAssetId, ver MapUiState.highlightedAssetId). También puede
     * llegar con el mapa recién creado (sin assets todavía, ver SoltelematicNavHost) o mirando otra
     * zona, así que sí hace falta mover la cámara explícitamente en vez de confiar en el encuadre
     * automático.
     */
    private fun focusOnAsset(id: Int, openSheet: Boolean) {
        // "Ver en mapa" / Unidades mandan sobre el modo "Recorrido de hoy": se sale de él primero.
        if (_uiState.value.todayTrack != null) onExitTodayTrack()
        pendingFocusOpenSheet = openSheet
        revealAssetIfHidden(id)
        val position = _uiState.value.assets.firstOrNull { it.id == id }?.position
        if (openSheet) {
            // Desde Unidades: misma selección que tocar el marcador (hoja + cola + encuadre, ver
            // MapScreen).
            if (position != null) {
                pendingFocusAssetId = null
                onAssetSelected(id)
            } else {
                pendingFocusAssetId = id
            }
            return
        }
        if (position != null) {
            pendingFocusAssetId = null
            focusWithoutSheet(id)
        } else {
            pendingFocusAssetId = id
        }
    }

    /**
     * Enfoque sin hoja: cola + seguimiento de la unidad. Cierra la hoja que hubiera abierta -- la
     * ficha se abre desde "Ver detalle" con la hoja puesta y al volver (popBackStack) ese estado
     * seguía vivo en este ViewModel, así que "Ver en mapa" terminaba con la hoja encima.
     */
    private fun focusWithoutSheet(id: Int) {
        statsJob?.cancel()
        addressJob?.cancel()
        _uiState.update {
            it.copy(
                selectedAssetId = null,
                focusedAssetId = id,
                isFollowing = true,
                focusRequestId = it.focusRequestId + 1,
                isSelectedAssetStatsLoading = false,
                selectedAssetStats = emptyList(),
                isSelectedAssetAddressLoading = false,
                selectedAssetAddress = null
            )
        }
    }

    /** Deja de seguir sin tocar selección, hoja ni cola. */
    fun onStopFollowing() {
        if (_uiState.value.isFollowing) _uiState.update { it.copy(isFollowing = false) }
    }

    /** Gesto del usuario sobre el mapa (arrastrar, pellizcar, rotar). */
    fun onCameraGesture() = onStopFollowing()

    /**
     * Cerrar la hoja (fondo, deslizar o Atrás) conserva la unidad enfocada: queda como tras "Ver en
     * mapa" (cola + seguimiento, sin hoja). Quitar el enfoque es tocar el mapa vacío
     * (onBottomSheetDismissed).
     */
    fun onBottomSheetClosed() {
        val id = _uiState.value.selectedAssetId ?: return
        statsJob?.cancel()
        addressJob?.cancel()
        _uiState.update {
            it.copy(
                selectedAssetId = null,
                focusedAssetId = id,
                isSelectedAssetStatsLoading = false,
                selectedAssetStats = emptyList(),
                isSelectedAssetAddressLoading = false,
                selectedAssetAddress = null
            )
        }
    }

    /**
     * Hay una sola instancia de mapa (la pestaña), así que su chip de filtro o su búsqueda pueden
     * estar activos al llegar un foco: si ocultarían la unidad se vuelve a "Todas" y se limpia la
     * búsqueda; si no la ocultan, no se tocan. Sin la unidad cargada todavía no hay nada que
     * evaluar (se reintenta al resolver el foco pendiente).
     */
    private fun revealAssetIfHidden(id: Int) {
        val state = _uiState.value
        val asset = state.assets.firstOrNull { it.id == id } ?: return
        if (asset !in state.visibleAssets) {
            _uiState.update { it.copy(activeFilter = AssetFilter.ALL, searchQuery = "") }
        }
    }

    private fun resolvePendingFocusIfNeeded(assets: List<Asset>) {
        val id = pendingFocusAssetId ?: return
        if (assets.firstOrNull { it.id == id }?.position == null) return
        pendingFocusAssetId = null
        revealAssetIfHidden(id)
        if (pendingFocusOpenSheet) {
            onAssetSelected(id)
        } else {
            focusWithoutSheet(id)
        }
    }

    fun onBottomSheetDismissed() {
        statsJob?.cancel()
        addressJob?.cancel()
        _uiState.update {
            it.copy(
                selectedAssetId = null,
                // Mismo botón que "cerrar hoja" para el caso sin hoja: tocar el mapa vacío tras un
                // foco de "Ver en mapa" también debe apagar su resaltado (ver onMapClick).
                focusedAssetId = null,
                isFollowing = false,
                isSelectedAssetStatsLoading = false,
                selectedAssetStats = emptyList(),
                isSelectedAssetAddressLoading = false,
                selectedAssetAddress = null
            )
        }
    }

    // --- Recorrido de hoy ---

    /**
     * Entrar al modo (o cambiar de unidad estando en él) desde una sugerencia del buscador. Cierra
     * hoja/foco/seguimiento -- en el modo no hay ninguno -- y limpia la búsqueda: ya cumplió su
     * trabajo y, al salir, no debe dejar el mapa filtrado por el nombre de esta unidad.
     */
    fun onTrackTodaySelected(id: Int) {
        if (_uiState.value.assets.none { it.id == id }) return
        statsJob?.cancel()
        addressJob?.cancel()
        _uiState.update {
            it.copy(
                selectedAssetId = null,
                focusedAssetId = null,
                isFollowing = false,
                isSelectedAssetStatsLoading = false,
                selectedAssetStats = emptyList(),
                isSelectedAssetAddressLoading = false,
                selectedAssetAddress = null,
                searchQuery = "",
                todayTrack = TodayTrackState(assetId = id)
            )
        }
        loadTodayTrack(id)
    }

    /** Enter en el buscador: solo selecciona si hay una única sugerencia. */
    fun onSearchSubmit() {
        val only = _uiState.value.searchSuggestions.singleOrNull() ?: return
        onTrackTodaySelected(only.id)
    }

    fun onTodayTrackRetry() {
        val track = _uiState.value.todayTrack ?: return
        _uiState.update { it.copy(todayTrack = track.copy(status = TodayTrackStatus.LOADING)) }
        loadTodayTrack(track.assetId)
    }

    /** Tocar la unidad seguida (único marcador visible en el modo): centra la cámara en ella. */
    fun onTrackedAssetTapped() {
        _uiState.update { state ->
            val track = state.todayTrack ?: return@update state
            state.copy(todayTrack = track.copy(centerRequestId = track.centerRequestId + 1))
        }
    }

    /** "Ajustar zoom" dentro del modo: vuelve a encuadrar el recorrido completo. */
    fun onTrackReframe() {
        _uiState.update { state ->
            val track = state.todayTrack ?: return@update state
            // El resaltado de un tramo no sobrevive a "ver todo el recorrido".
            state.copy(todayTrack = track.copy(frameRequestId = track.frameRequestId + 1, selectedLegIndex = null))
        }
    }

    fun onTodayPanelExpandedChange(expanded: Boolean) = updateTrack { it.withPanelExpanded(expanded) }

    /** Fila de tramo tocada en el panel: resalta, pliega el panel y pide encuadrar (ver withLegSelected). */
    fun onTodayLegSelected(legIndex: Int) = updateTrack { it.withLegSelected(legIndex) }

    /** Atrás en el modo: pliega el panel si está desplegado; si no, sale. */
    fun onTodayTrackBack() {
        val track = _uiState.value.todayTrack ?: return
        val next = track.afterBack()
        if (next == null) onExitTodayTrack() else updateTrack { next }
    }

    /** Misma mecánica que HistoryViewModel.onStopRowVisible: solo se pide al entrar la fila en pantalla. */
    fun onTodayStopRowVisible(legIndex: Int, point: GeoPoint) {
        val track = _uiState.value.todayTrack ?: return
        if (track.addresses.containsKey(legIndex)) return
        if (addressCache.containsKey(point)) {
            setTodayAddress(track.assetId, legIndex, point, AddressResolution.Resolved(addressCache.getValue(point)))
            return
        }
        setTodayAddress(track.assetId, legIndex, point, AddressResolution.Loading)
        viewModelScope.launch {
            val resolved = when (val result = assetDetailRepository.getAddress(point.lat, point.lng)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> null
            }
            addressCache[point] = resolved
            setTodayAddress(track.assetId, legIndex, point, AddressResolution.Resolved(resolved))
        }
    }

    private fun updateTrack(transform: (TodayTrackState) -> TodayTrackState) {
        _uiState.update { state ->
            val track = state.todayTrack ?: return@update state
            state.copy(todayTrack = transform(track))
        }
    }

    // Descarta la respuesta si llegó tarde: otra unidad, otro recorrido o la fila ya no es esa parada.
    private fun setTodayAddress(assetId: Int, legIndex: Int, point: GeoPoint, resolution: AddressResolution) {
        updateTrack { track ->
            val leg = track.route?.legs?.getOrNull(legIndex)
            if (track.assetId != assetId || (leg as? HistoryStopLeg)?.start?.point != point) {
                track
            } else {
                track.copy(addresses = track.addresses + (legIndex to resolution))
            }
        }
    }

    fun onExitTodayTrack() {
        if (_uiState.value.todayTrack == null) return
        trackJob?.cancel()
        _uiState.update { it.copy(todayTrack = null, fleetFitRequestId = it.fleetFitRequestId + 1) }
    }

    private fun loadTodayTrack(id: Int) {
        trackJob?.cancel()
        trackJob = viewModelScope.launch {
            when (val result = assetDetailRepository.getTodayRoute(id)) {
                is ApiResult.Success -> {
                    val route = result.data
                    // Mapeo fuera del hilo principal: un día largo trae miles de posiciones.
                    val mapData = withContext(Dispatchers.Default) { route.toRouteMapData() }
                    _uiState.update { state ->
                        val track = state.todayTrack ?: return@update state
                        // Llegó tarde: el usuario ya cambió de unidad o salió del modo.
                        if (track.assetId != id) return@update state
                        state.copy(
                            todayTrack = track.copy(
                                status = if (route.legs.isEmpty()) TodayTrackStatus.EMPTY else TodayTrackStatus.READY,
                                route = route,
                                mapData = mapData,
                                selectedLegIndex = null,
                                addresses = emptyMap(),
                                frameRequestId = track.frameRequestId + 1
                            )
                        )
                    }
                }
                is ApiResult.Error -> _uiState.update { state ->
                    val track = state.todayTrack ?: return@update state
                    if (track.assetId != id) return@update state
                    state.copy(todayTrack = track.copy(status = TodayTrackStatus.ERROR, frameRequestId = track.frameRequestId + 1))
                }
            }
        }
    }

    private fun loadSelectedAssetStats(id: Int) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            when (val result = assetDetailRepository.getTodayStats(id)) {
                is ApiResult.Success -> _uiState.update { state ->
                    // Puede llegar tarde, después de que el usuario ya tocó otro marcador o
                    // cerró la hoja -- no pisar un estado que ya no corresponde a esta unidad.
                    if (state.selectedAssetId == id) {
                        state.copy(isSelectedAssetStatsLoading = false, selectedAssetStats = result.data)
                    } else {
                        state
                    }
                }
                // Sin error explícito: los stats se quedan en "-" (ver AssetBottomSheet), nunca
                // un bloqueo -- mismo criterio que AssetDetailViewModel.loadTodayStats.
                is ApiResult.Error -> _uiState.update { state ->
                    if (state.selectedAssetId == id) state.copy(isSelectedAssetStatsLoading = false) else state
                }
            }
        }
    }

    private fun loadSelectedAssetAddress(id: Int) {
        addressJob?.cancel()
        val position = _uiState.value.assets.firstOrNull { it.id == id }?.position
        if (position == null) {
            _uiState.update { it.copy(isSelectedAssetAddressLoading = false) }
            return
        }
        if (addressCache.containsKey(position)) {
            val cached = addressCache.getValue(position)
            _uiState.update { state ->
                if (state.selectedAssetId == id) {
                    state.copy(isSelectedAssetAddressLoading = false, selectedAssetAddress = cached)
                } else {
                    state
                }
            }
            return
        }
        addressJob = viewModelScope.launch {
            val resolved = when (val result = assetDetailRepository.getAddress(position.lat, position.lng)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> null
            }
            addressCache[position] = resolved
            _uiState.update { state ->
                if (state.selectedAssetId == id) {
                    state.copy(isSelectedAssetAddressLoading = false, selectedAssetAddress = resolved)
                } else {
                    state
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            assetRepository.refresh()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    // --- Creación de geocercas ---

    fun onStartGeofenceCreation() {
        if (_uiState.value.todayTrack != null) onExitTodayTrack()
        onBottomSheetDismissed()
        _uiState.update { it.copy(geofenceCreation = GeofenceCreationState()) }
    }

    fun onCancelGeofenceCreation() {
        _uiState.update { it.copy(geofenceCreation = null) }
    }

    fun onGeofenceTypeSelected(type: GeofenceDrawType) {
        _uiState.update { it.copy(geofenceCreation = it.geofenceCreation?.copy(type = type)) }
    }

    /** Ignorado si el formulario ya está abierto (creation.showForm) -- la forma queda fija en
     * cuanto el usuario pasa al formulario, un toque detrás del sheet no debe seguir editándola. */
    fun onGeofenceMapTapped(point: GeoPoint) {
        _uiState.update { state ->
            val creation = state.geofenceCreation ?: return@update state
            if (creation.showForm) return@update state
            val updated = when (creation.type) {
                GeofenceDrawType.POLYGON -> creation.copy(polygonVertices = creation.polygonVertices + point)
                // Siempre reemplaza (no solo si era null): permite recorregir el centro con otro
                // toque antes de confirmar la forma.
                GeofenceDrawType.CIRCLE -> creation.copy(circleCenter = point)
                null -> creation
            }
            state.copy(geofenceCreation = updated)
        }
    }

    fun onUndoLastGeofenceVertex() {
        _uiState.update { state ->
            val creation = state.geofenceCreation ?: return@update state
            state.copy(geofenceCreation = creation.copy(polygonVertices = creation.polygonVertices.dropLast(1)))
        }
    }

    fun onClearGeofenceDraft() {
        _uiState.update { state ->
            val creation = state.geofenceCreation ?: return@update state
            state.copy(geofenceCreation = creation.copy(polygonVertices = emptyList()))
        }
    }

    fun onGeofenceCircleRadiusChanged(meters: Double) {
        _uiState.update { it.copy(geofenceCreation = it.geofenceCreation?.copy(circleRadiusMeters = meters)) }
    }

    fun onConfirmGeofenceShape() {
        _uiState.update { state ->
            val creation = state.geofenceCreation ?: return@update state
            if (!creation.canConfirmShape) return@update state
            state.copy(geofenceCreation = creation.copy(showForm = true))
        }
    }

    /** Vuelve al modo dibujo con la forma intacta -- no descarta nada. Para eso está
     * onCancelGeofenceCreation, en el toolbar de dibujo. */
    fun onGeofenceFormDismissed() {
        _uiState.update { it.copy(geofenceCreation = it.geofenceCreation?.copy(showForm = false)) }
    }

    fun onGeofenceNameChanged(name: String) {
        _uiState.update { it.copy(geofenceCreation = it.geofenceCreation?.copy(name = name)) }
    }

    fun onGeofenceColorSelected(colorHex: String) {
        _uiState.update { it.copy(geofenceCreation = it.geofenceCreation?.copy(colorHex = colorHex)) }
    }

    fun onGeofenceSpeedLimitInputChanged(input: String) {
        if (input.isNotEmpty() && !input.all(Char::isDigit)) return
        _uiState.update { it.copy(geofenceCreation = it.geofenceCreation?.copy(speedLimitInput = input)) }
    }

    private fun GeofenceCreationState.toShapeOrNull(): GeofenceShape? = when (type) {
        GeofenceDrawType.POLYGON ->
            if (polygonVertices.size >= 3) GeofenceShape.Polygon(polygonVertices) else null
        GeofenceDrawType.CIRCLE -> circleCenter?.let { center ->
            if (circleRadiusMeters > 0) GeofenceShape.Circle(center, circleRadiusMeters) else null
        }
        null -> null
    }

    fun onSaveGeofence() {
        val creation = _uiState.value.geofenceCreation ?: return
        val shape = creation.toShapeOrNull() ?: return
        if (creation.name.isBlank() || creation.isSaving) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(geofenceCreation = it.geofenceCreation?.copy(isSaving = true, fieldErrors = emptyMap(), hasGeneralError = false))
            }
            val request = GeofenceCreateRequest(
                name = creation.name,
                shape = shape,
                colorHex = creation.colorHex,
                speedLimit = creation.speedLimitInput.toIntOrNull()
            )
            when (val result = geofencesRepository.createGeofence(request)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(geofences = it.geofences + result.data, geofenceCreation = null) }
                    // Se enciende sola tras crear una geocerca, aunque el usuario la haya apagado
                    // antes: si no, el usuario crea algo y no ve nada. Nunca se toca al ENTRAR en
                    // modo dibujo (ver onStartGeofenceCreation) -- solo tras un guardado exitoso,
                    // no hay que confundir "activar la capa" con "el usuario pidió verla".
                    userPreferences.setShowGeofences(true)
                    _geofenceCreateEvent.emit(GeofenceCreateEvent.Success)
                }
                is ApiResult.Error -> when (val error = result.error) {
                    is ApiError.ValidationError -> _uiState.update {
                        it.copy(geofenceCreation = it.geofenceCreation?.copy(isSaving = false, fieldErrors = error.fieldErrors))
                    }
                    else -> {
                        _uiState.update {
                            it.copy(geofenceCreation = it.geofenceCreation?.copy(isSaving = false, hasGeneralError = true))
                        }
                        _geofenceCreateEvent.emit(GeofenceCreateEvent.GeneralError)
                    }
                }
            }
        }
    }

    // --- Borrado de geocercas ---

    fun onStartGeofenceDeletion() {
        onBottomSheetDismissed()
        // Mismo fetch (una sola vez, guardado por geofencesRequested) que dispara el interruptor
        // de visibilidad -- si el usuario nunca lo encendió, MapUiState.geofences seguiría vacío
        // y la hoja mostraría "sin geocercas" aunque sí existan en el servidor.
        loadGeofencesIfNeeded()
        _uiState.update { it.copy(geofenceDeletion = GeofenceDeletionState()) }
    }

    fun onGeofenceDeletionDismissed() {
        _uiState.update { it.copy(geofenceDeletion = null) }
    }

    fun onGeofenceDeleteRequested(id: Int) {
        _uiState.update { it.copy(geofenceDeletion = it.geofenceDeletion?.copy(pendingDeleteId = id)) }
    }

    fun onGeofenceDeleteCancelled() {
        _uiState.update { it.copy(geofenceDeletion = it.geofenceDeletion?.copy(pendingDeleteId = null)) }
    }

    fun onGeofenceDeleteConfirmed() {
        val deletion = _uiState.value.geofenceDeletion ?: return
        val id = deletion.pendingDeleteId ?: return
        if (deletion.deletingId != null) return // ya hay un borrado en curso

        _uiState.update {
            it.copy(geofenceDeletion = it.geofenceDeletion?.copy(pendingDeleteId = null, deletingId = id))
        }
        viewModelScope.launch {
            when (val result = geofencesRepository.deleteGeofence(id)) {
                is ApiResult.Success -> {
                    _uiState.update { state ->
                        val remaining = state.geofences.filterNot { it.id == id }
                        state.copy(
                            geofences = remaining,
                            // Sin nada más que borrar, la hoja se cierra sola; si queda algo, se
                            // mantiene abierta para poder seguir borrando sin reabrir el menú.
                            geofenceDeletion = if (remaining.isEmpty()) null else state.geofenceDeletion?.copy(deletingId = null)
                        )
                    }
                    _geofenceDeleteEvent.emit(GeofenceDeleteEvent.Success)
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(geofenceDeletion = it.geofenceDeletion?.copy(deletingId = null)) }
                    _geofenceDeleteEvent.emit(GeofenceDeleteEvent.Error)
                }
            }
        }
    }
}

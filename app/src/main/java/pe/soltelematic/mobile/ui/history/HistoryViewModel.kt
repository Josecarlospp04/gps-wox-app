package pe.soltelematic.mobile.ui.history

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.core.storage.UserPreferencesDataStore
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.repository.AssetDetailRepository
import pe.soltelematic.mobile.domain.repository.AssetRepository
import java.time.LocalDate

// Paso base a 1x -- 2x/4x/8x lo dividen. A 200ms/punto un recorrido de ~200 puntos (un día
// típico, ver RouteSimplifier/GoogleRouteMapEngine) tarda ~40s a 1x y ~5s a 8x: perceptible sin
// ser una espera larga. No se usa el tiempo real entre HistoryPosition.time consecutivos porque
// un corte de señal GPS entre dos puntos puede ser de varios minutos -- reproducirlo tal cual
// dejaría el marcador "congelado" esperando ese hueco en vez de avanzar a un ritmo utilizable.
private const val BASE_STEP_DELAY_MS = 200L

// Tasa MÁXIMA a la que el marcador/cámara/textos se actualizan de forma visible, desacoplada del
// ritmo real de avance (BASE_STEP_DELAY_MS/multiplicador). A 8x el avance real es cada ~25ms
// (40Hz) -- nadie percibe puntos individuales a esa velocidad, y pedirle a Compose que recomponga
// la barra + mueva la cámara 40 veces por segundo es lo que medimos como jank severo (ver
// investigación previa). internalPlaybackIndex de abajo sigue avanzando al ritmo real sin pasar
// por _uiState en cada vuelta -- este cap solo limita cuánto de eso se vuelve visible.
private const val VISUAL_UPDATE_INTERVAL_MS = 1000L / 30 // ~33ms, 30Hz

/** assetId por parámetro de Koin, igual que AssetDetailViewModel -- ver ViewModelModule. */
class HistoryViewModel(
    private val assetId: Int,
    initialDay: LocalDate,
    private val assetDetailRepository: AssetDetailRepository,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val assetRepository: AssetRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState(dateRange = HistoryDateRange.forDay(initialDay)))
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    // Caché de direcciones por coordenada exacta: vive mientras viva este ViewModel (una entrada
    // a Historial = una instancia nueva, ver ViewModelModule), "dentro de la sesión" de esta
    // visita a la pantalla -- no persiste entre unidades ni entre reaperturas.
    private val addressCache = mutableMapOf<GeoPoint, String?>()

    private var loadJob: Job? = null
    private var playbackJob: Job? = null

    // Fuente de verdad del avance real durante la reproducción -- vive fuera de _uiState a
    // propósito, así startPlayback() puede incrementarlo cada BASE_STEP_DELAY_MS/multiplicador
    // sin que cada incremento dispare una recomposición. onScrub() también lo escribe, para que
    // el loop en curso continúe desde el punto arrastrado en vez de pisarlo en el siguiente tick.
    // @Volatile: el loop corre en Dispatchers.Default (ver startPlayback) mientras onScrub()
    // escribe desde el hilo principal -- sin esto, el hilo del loop podría tardar en ver el
    // arrastre.
    @Volatile
    private var internalPlaybackIndex = 0
    private var lastVisualEmitAtMs = 0L

    // Igual patrón que RealtimePoller (Bloque C del mapa en vivo): DefaultLifecycleObserver sobre
    // ProcessLifecycleOwner, no Lifecycle.currentStateFlow (pide una versión de lifecycle-runtime-
    // ktx más nueva que la que trae el proyecto). onStop cubre tanto "la app pasa a segundo plano"
    // como "el usuario cambia a otra app" -- pausePlayback() dentro deja isPlaying=false, así que
    // al volver el botón ya muestra "reproducir", no un ícono de pausa mintiendo sobre un job que
    // ya no corre.
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = pausePlayback()
    }

    init {
        loadRoute()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        // Espejo de la preferencia global (compartida con MapScreen): el historial solo observa,
        // nunca escribe -- ver HistoryUiState.mapType.
        viewModelScope.launch {
            userPreferencesDataStore.mapType.collect { type ->
                _uiState.update { it.copy(mapType = type) }
            }
        }
        // Icono de la unidad para el marcador de reproducción (ver HistoryUiState.unitIcon) --
        // Room ya lo tiene desde el mapa en vivo/refresh de la flota, así que esto no dispara
        // ninguna llamada de red propia de Historial, solo observa lo que ya hay.
        viewModelScope.launch {
            assetRepository.observeAssets().collect { assets ->
                val icon = assets.firstOrNull { it.id == assetId }?.icon
                _uiState.update { it.copy(unitIcon = icon) }
            }
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        playbackJob?.cancel()
    }

    fun onRetry() = loadRoute()

    /** Botón de "salir" durante la carga: cancela la petición en vuelo, no la deja corriendo de fondo. */
    fun cancelLoad() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = false) }
    }

    fun onLegSelected(legIndex: Int) {
        _uiState.update { it.copy(selectedLegIndex = legIndex) }
    }

    /** Hoy / Ayer / 7 días: rango ya resuelto, solo hace falta recargar con él. */
    fun onDateRangeSelected(range: HistoryDateRange) {
        pausePlayback()
        _uiState.update {
            it.copy(
                dateRange = range,
                selectedLegIndex = null,
                addresses = emptyMap(),
                playback = HistoryPlaybackState()
            )
        }
        loadRoute()
    }

    fun onPlayPauseToggled() {
        if (_uiState.value.playback.isPlaying) pausePlayback() else startPlayback()
    }

    /** Arrastre del scrubber: salta el índice directo, sin pausar ni reanudar la reproducción. */
    fun onScrub(index: Int) {
        val points = _uiState.value.playbackPoints
        if (points.isEmpty()) return
        val clamped = index.coerceIn(0, points.lastIndex)
        // Si el loop de startPlayback sigue corriendo, debe continuar desde acá en su próximo
        // tick, no desde el valor viejo que tenía antes del arrastre.
        internalPlaybackIndex = clamped
        _uiState.update { it.copy(playback = it.playback.copy(currentIndex = clamped)) }
    }

    /** Chip 1x -> 2x -> 4x -> 8x -> 1x. El loop en curso relee speedMultiplier en cada paso (ver
     * startPlayback), así que el cambio de ritmo se nota de inmediato sin reiniciar el job. */
    fun onSpeedMultiplierCycled() {
        _uiState.update {
            val next = when (it.playback.speedMultiplier) {
                1 -> 2
                2 -> 4
                4 -> 8
                else -> 1
            }
            it.copy(playback = it.playback.copy(speedMultiplier = next))
        }
    }

    private fun startPlayback() {
        val points = _uiState.value.playbackPoints
        if (points.size < 2) return
        // Si ya llegó al final, "reproducir" vuelve a arrancar desde el principio -- igual
        // criterio que cualquier reproductor de video/audio.
        val startIndex = if (_uiState.value.playback.currentIndex >= points.lastIndex) 0 else _uiState.value.playback.currentIndex
        internalPlaybackIndex = startIndex
        _uiState.update { it.copy(playback = it.playback.copy(isPlaying = true, currentIndex = startIndex)) }
        playbackJob?.cancel()
        // Dispatchers.Default, no el Main.immediate por defecto de viewModelScope: delay() sobre
        // Main se posterga detrás del trabajo de Compose/Choreographer del hilo principal --
        // medido en dispositivo, un delay(25) pedido ahí tardaba ~53ms reales en dispararse. En
        // Default (pool de hilos, sin Handler ni Choreographer de por medio) el avance real sí se
        // acerca al multiplicador pedido. _uiState.update es seguro de escribir desde acá --
        // MutableStateFlow no está atado a ningún hilo en particular.
        playbackJob = viewModelScope.launch(Dispatchers.Default) {
            lastVisualEmitAtMs = SystemClock.elapsedRealtime()
            while (isActive) {
                val multiplier = _uiState.value.playback.speedMultiplier
                delay(BASE_STEP_DELAY_MS / multiplier)
                internalPlaybackIndex++
                if (internalPlaybackIndex > points.lastIndex) {
                    // Acotar ANTES de pausar: pausePlayback() sincroniza currentIndex con
                    // internalPlaybackIndex tal cual esté (ver más abajo), así que si se dejara en
                    // lastIndex+1 acá, el contador final mostraría "353/352" en vez de "352/352".
                    internalPlaybackIndex = points.lastIndex
                    pausePlayback()
                    break
                }
                // Desacoplado a propósito (ver VISUAL_UPDATE_INTERVAL_MS): el avance de acá arriba
                // ya corrió al ritmo real sin esperar esto. A velocidades altas varios incrementos
                // de internalPlaybackIndex caen en la misma ventana de 33ms y se saltan sin emitir
                // -- el próximo flush muestra directo el punto más reciente, no cada uno.
                val now = SystemClock.elapsedRealtime()
                if (now - lastVisualEmitAtMs >= VISUAL_UPDATE_INTERVAL_MS) {
                    lastVisualEmitAtMs = now
                    emitVisualIndex(internalPlaybackIndex)
                }
            }
        }
    }

    private fun emitVisualIndex(index: Int) {
        _uiState.update { it.copy(playback = it.playback.copy(currentIndex = index)) }
    }

    private fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update {
            if (!it.playback.isPlaying) {
                it
            } else {
                // Sincroniza con el avance real: el último valor mostrado puede estar hasta
                // VISUAL_UPDATE_INTERVAL_MS atrás del internalPlaybackIndex real -- al pausar sí
                // importa mostrar el punto exacto, ya no hay más ticks por venir que lo corrijan.
                it.copy(playback = it.playback.copy(isPlaying = false, currentIndex = internalPlaybackIndex))
            }
        }
    }

    /** "Elegir": from/to crudos del selector, custom() aplica el tope de 31 días. */
    fun onCustomDateRangeSelected(from: LocalDate, to: LocalDate) {
        onDateRangeSelected(HistoryDateRange.custom(from, to))
    }

    /**
     * Se llama cuando una fila de parada entra en composición en HistoryTimeline (LaunchedEffect
     * por fila) -- nunca de una vez al abrir la pantalla. Un día con 20 paradas no dispara 20
     * peticiones si el usuario no llega a desplazarse hasta todas.
     */
    fun onStopRowVisible(legIndex: Int, point: GeoPoint) {
        if (_uiState.value.addresses.containsKey(legIndex)) return // ya pedida o resuelta para esta fila

        if (addressCache.containsKey(point)) {
            val cached = addressCache.getValue(point)
            _uiState.update { it.copy(addresses = it.addresses + (legIndex to AddressResolution.Resolved(cached))) }
            return
        }

        _uiState.update { it.copy(addresses = it.addresses + (legIndex to AddressResolution.Loading)) }
        viewModelScope.launch {
            val resolved = when (val result = assetDetailRepository.getAddress(point.lat, point.lng)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> null
            }
            addressCache[point] = resolved
            _uiState.update { it.copy(addresses = it.addresses + (legIndex to AddressResolution.Resolved(resolved))) }
        }
    }

    private fun loadRoute() {
        pausePlayback() // defensivo: un reintento en vuelo no debe dejar un job corriendo contra playbackPoints de un route viejo
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val range = _uiState.value.dateRange
            val result = assetDetailRepository.getRoute(
                id = assetId,
                from = range.from.atStartOfDay(),
                to = range.to.atTime(23, 59, 59)
            )
            when (result) {
                is ApiResult.Success -> {
                    // toBearings() recorre todos los puntos una vez acá, al terminar la carga --
                    // nunca dentro del loop de startPlayback (ver el comentario de esa función,
                    // corre hasta 40 veces por segundo).
                    val points = result.data.toPlaybackPoints()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            route = result.data,
                            mapData = result.data.toRouteMapData(),
                            playbackPoints = points,
                            playbackBearings = points.toBearings()
                        )
                    }
                }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.error) }
            }
        }
    }
}

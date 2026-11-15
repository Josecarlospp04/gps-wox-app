package pe.soltelematic.mobile.ui.history

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.core.result.ApiError
import pe.soltelematic.mobile.domain.model.AssetIcon
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.ui.map.RouteInfoCard
import pe.soltelematic.mobile.ui.map.engine.RouteMapEngine
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerKey
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors
import pe.soltelematic.mobile.ui.theme.SoltelematicMinTouchTarget
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HEADER_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es-PE"))
private val ERROR_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val EmptyStateIconSize = 40.dp
private val CalendarButtonBorderWidth = 1.dp

/**
 * TopAppBar con volver, subtítulo de rango + botón de calendario, carga cancelable, error con
 * reintento, vacío si el día no tuvo actividad, y contenido con mapa (arriba) + línea de tiempo
 * (abajo) -- ver HistoryContent.
 *
 * El botón de calendario del header y la pill "Personalizado" de HistoryDateRangeBar abren el
 * mismo HistoryDateRangePickerDialog -- por eso showDatePicker vive acá, no dentro de la barra de
 * pills, un solo diálogo con dos disparadores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    assetId: Int,
    // Día con el que abre (la fecha del selector); hoy salvo que se llegue desde la Última ruta.
    initialDay: LocalDate,
    onBack: () -> Unit,
    // El rango lo decide quien llama (SoltelematicNavHost): las gráficas son del mismo periodo que
    // el usuario tenía elegido acá, no de uno propio -- ver HistoryTimeline.onOpenParameterCharts.
    onOpenParameterCharts: (from: LocalDate, to: LocalDate) -> Unit,
    viewModel: HistoryViewModel = koinViewModel(parameters = { parametersOf(assetId, initialDay) }),
    routeMapEngine: RouteMapEngine = koinInject()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = uiState.dateRange.toDisplayText(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.asset_detail_back)
                            )
                        }
                    },
                    actions = {
                        HistoryCalendarButton(onClick = { showDatePicker = true })
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                HistoryDateRangeBar(
                    dateRange = uiState.dateRange,
                    onPresetSelected = viewModel::onDateRangeSelected,
                    onOpenCustomPicker = { showDatePicker = true }
                )
            }
        }
    ) { innerPadding ->
        if (showDatePicker) {
            HistoryDateRangePickerDialog(
                initialRange = uiState.dateRange,
                onDismiss = { showDatePicker = false },
                onConfirm = { from, to ->
                    showDatePicker = false
                    viewModel.onCustomDateRangeSelected(from, to)
                }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            val route = uiState.route
            val error = uiState.error
            when {
                uiState.isLoading -> HistoryLoadingState(
                    onCancel = {
                        viewModel.cancelLoad()
                        onBack()
                    }
                )
                error != null -> HistoryErrorState(error = error, onRetry = viewModel::onRetry)
                route != null && route.legs.isEmpty() -> HistoryEmptyState(
                    dateText = uiState.dateRange.toDisplayText(),
                    onPickAnotherDate = { showDatePicker = true }
                )
                route != null -> HistoryContent(
                    route = route,
                    mapData = uiState.mapData,
                    selectedLegIndex = uiState.selectedLegIndex,
                    addresses = uiState.addresses,
                    onLegSelected = viewModel::onLegSelected,
                    infoCard = uiState.infoCard,
                    onMarkerTapped = viewModel::onMarkerTapped,
                    onInfoCardDismissed = viewModel::onInfoCardDismissed,
                    onStopRowVisible = viewModel::onStopRowVisible,
                    playbackPoints = uiState.playbackPoints,
                    playbackBearings = uiState.playbackBearings,
                    unitIcon = uiState.unitIcon,
                    playback = uiState.playback,
                    onPlayPauseClick = viewModel::onPlayPauseToggled,
                    onScrub = viewModel::onScrub,
                    onSpeedMultiplierClick = viewModel::onSpeedMultiplierCycled,
                    onOpenParameterCharts = { onOpenParameterCharts(uiState.dateRange.from, uiState.dateRange.to) },
                    routeMapEngine = routeMapEngine,
                    mapType = uiState.mapType,
                    speedRanges = uiState.speedRanges
                )
            }
        }
    }
}

@Composable
private fun HistoryCalendarButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = SoltelematicSpacing.sm)
            .size(SoltelematicMinTouchTarget)
            .border(CalendarButtonBorderWidth, MaterialTheme.colorScheme.outlineVariant, SoltelematicShapes.small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.CalendarMonth,
            contentDescription = stringResource(R.string.history_open_date_picker),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryLoadingState(onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.lg)
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        // Una consulta de varios días puede tardar -- siempre hay una salida, no solo esperar a
        // que termine o falle. cancelLoad() corta la corrutina en vuelo, no la deja de fondo.
        OutlinedButton(onClick = onCancel, shape = SoltelematicShapes.small) {
            Text(stringResource(R.string.history_cancel), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Anatomía única de estado vacío/error de la pantalla (ver mockup): ícono monocromo 40dp, título
 * corto, una línea de explicación, máximo una acción.
 */
@Composable
private fun HistoryEmptyState(dateText: String, onPickAnotherDate: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
        modifier = Modifier.padding(SoltelematicSpacing.xl)
    ) {
        Icon(
            Icons.Filled.EventBusy,
            contentDescription = null,
            tint = LocalSoltelematicColors.current.inkFaint,
            modifier = Modifier.size(EmptyStateIconSize)
        )
        Text(
            text = stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.history_empty_message, dateText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onPickAnotherDate,
            shape = SoltelematicShapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.padding(top = SoltelematicSpacing.sm)
        ) {
            Text(stringResource(R.string.history_empty_action), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HistoryErrorState(error: ApiError, onRetry: () -> Unit) {
    // Compuesto una sola vez por instancia de error (no en cada recomposición): suficiente para
    // mostrar "a qué hora falló" sin tener que sumar un campo de timestamp al ViewModel.
    val failedAt = remember(error) { Instant.now() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
        modifier = Modifier.padding(SoltelematicSpacing.xl)
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = LocalSoltelematicColors.current.statusAlert,
            modifier = Modifier.size(EmptyStateIconSize)
        )
        Text(
            text = stringResource(R.string.history_error_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.history_error_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.history_error_detail, error.toErrorCode(), ERROR_TIME_FORMAT.format(failedAt)),
            style = MaterialTheme.typography.labelSmall,
            color = LocalSoltelematicColors.current.inkFaint
        )
        Button(
            onClick = onRetry,
            shape = SoltelematicShapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.padding(top = SoltelematicSpacing.sm)
        ) {
            Text(stringResource(R.string.asset_detail_retry), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HistoryContent(
    route: HistoryRoute,
    mapData: RouteMapData?,
    selectedLegIndex: Int?,
    addresses: Map<Int, AddressResolution>,
    onLegSelected: (Int) -> Unit,
    infoCard: InfoCardState?,
    onMarkerTapped: (RouteMarkerKey) -> Unit,
    onInfoCardDismissed: () -> Unit,
    onStopRowVisible: (Int, GeoPoint) -> Unit,
    playbackPoints: List<HistoryPlaybackPoint>,
    playbackBearings: List<Float>,
    unitIcon: AssetIcon?,
    playback: HistoryPlaybackState,
    onPlayPauseClick: () -> Unit,
    onScrub: (Int) -> Unit,
    onSpeedMultiplierClick: () -> Unit,
    onOpenParameterCharts: () -> Unit,
    routeMapEngine: RouteMapEngine,
    mapType: MapType,
    speedRanges: SpeedRanges
) {
    val cameraController = routeMapEngine.rememberCameraController()

    // Sigue al marcador de reproducción mientras isPlaying y nadie tocó el mapa a mano. Un
    // arrastre real (onCameraGesture, ver GoogleRouteMapEngine) lo apaga hasta el próximo play
    // explícito -- no hay botón de "volver a seguir" (ver conversación con el usuario).
    var followPlaybackCamera by remember { mutableStateOf(true) }
    LaunchedEffect(playback.isPlaying) {
        if (playback.isPlaying) followPlaybackCamera = true
    }
    LaunchedEffect(playback.currentIndex) {
        if (!playback.isPlaying || !followPlaybackCamera) return@LaunchedEffect
        val point = playbackPoints.getOrNull(playback.currentIndex)?.position?.point ?: return@LaunchedEffect
        cameraController.moveInstantly(point)
    }

    // Sin esto la cámara se queda en la posición por defecto del SDK (vista mundial) hasta que el
    // usuario toca algo -- se ajusta una sola vez al entrar, a todos los puntos de la ruta
    // (paradas + polylines de cada viaje), igual que "Ajustar zoom a todas" en el mapa en vivo.
    LaunchedEffect(Unit) {
        val points = (mapData?.markers?.map { it.position } ?: emptyList()) +
            (mapData?.polylines?.flatMap { polyline -> polyline.points.map { it.point } } ?: emptyList())
        if (points.isNotEmpty()) cameraController.fitAll(points)
    }

    // Vínculo bidireccional: seleccionar un tramo (desde un marcador tocado o desde una fila de
    // la lista, ambos llaman a onLegSelected) mueve la cámara -- a un punto+zoom si es una parada
    // (ya alcanza para ver dónde se detuvo), o a los bounds del tramo completo si es un viaje (un
    // solo punto no sirve para ver el recorrido, confirmado con el usuario). Ver CameraTarget.
    LaunchedEffect(selectedLegIndex) {
        val index = selectedLegIndex ?: return@LaunchedEffect
        when (val target = mapData?.cameraTargetFor(route, index)) {
            is CameraTarget.Point -> cameraController.centerOn(target.point)
            is CameraTarget.Bounds -> cameraController.fitAll(target.points)
            null -> Unit
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // La tarjeta de información va dentro del área del mapa, abajo: queda justo encima de la línea
        // de tiempo.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
        ) {
        routeMapEngine.Content(
            modifier = Modifier.fillMaxSize(),
            cameraController = cameraController,
            polylines = mapData?.polylines ?: emptyList(),
            markers = mapData?.markers ?: emptyList(),
            selectedLegIndex = selectedLegIndex,
            onMarkerClick = onMarkerTapped,
            onMapClick = onInfoCardDismissed,
            playbackPoint = playbackPoints.getOrNull(playback.currentIndex)?.position?.point,
            playbackBearing = playbackBearings.getOrNull(playback.currentIndex) ?: 0f,
            unitIcon = unitIcon,
            onCameraGesture = { followPlaybackCamera = false },
            mapType = mapType,
            speedRanges = speedRanges
        )
        infoCard?.let { card ->
            RouteInfoCard(
                card = card,
                onClose = onInfoCardDismissed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(SoltelematicSpacing.sm)
            )
        }
        }
        HistoryTimeline(
            legs = route.legs,
            periodStats = route.periodStats,
            selectedLegIndex = selectedLegIndex,
            onLegClick = onLegSelected,
            addresses = addresses,
            onStopRowVisible = onStopRowVisible,
            playbackPoints = playbackPoints,
            playback = playback,
            onPlayPauseClick = onPlayPauseClick,
            onScrub = onScrub,
            onSpeedMultiplierClick = onSpeedMultiplierClick,
            onOpenParameterCharts = onOpenParameterCharts,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
        )
    }
}

private fun HistoryDateRange.toDisplayText(): String = if (from == to) {
    HEADER_DATE_FORMAT.format(from)
} else {
    "${HEADER_DATE_FORMAT.format(from)} – ${HEADER_DATE_FORMAT.format(to)}"
}

// Mismos mensajes genéricos que AssetDetailScreen.errorMessage -- duplicado deliberado, igual
// criterio que el resto de la app (ver AssetDetailScreen.kt): tres líneas no ameritan compartir
// un archivo util entre dos pantallas.
private fun ApiError.toErrorCode(): String = when (this) {
    ApiError.NoConnection -> "sin_conexion"
    ApiError.Timeout -> "timeout"
    ApiError.Unauthorized -> "401"
    is ApiError.ValidationError -> "validacion"
    is ApiError.Http -> code.toString()
    is ApiError.Unknown -> "desconocido"
}

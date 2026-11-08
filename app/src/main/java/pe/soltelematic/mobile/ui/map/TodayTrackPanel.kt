package pe.soltelematic.mobile.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.ui.history.HistoryLegRow
import pe.soltelematic.mobile.ui.history.RouteSummaryCards
import pe.soltelematic.mobile.ui.theme.SoltelematicBottomSheetShape
import pe.soltelematic.mobile.ui.theme.SoltelematicElevation
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

// Alto máximo de la lista desplegada, como fracción de la pantalla: deja mapa visible arriba.
private const val EXPANDED_LIST_SCREEN_FRACTION = 0.38f
private val DragThreshold = 24.dp
private val HandleWidth = 32.dp
private val HandleHeight = 4.dp

// Tiempo de calma antes de publicar el borde superior: el padding del mapa (y con él la cámara) no
// debe seguir cada cuadro de un re-layout, solo el valor asentado.
private const val TOP_SETTLE_MS = 120L

/**
 * Panel inferior del modo "Recorrido de hoy" (no modal, alineado abajo en MapScreen).
 *
 * Plegado: resumen del día (mismas tarjetas que Historial) o el estado cargando / sin recorrido /
 * error con "Reintentar". Desplegado: además "Ver historial completo" y la lista de tramos con las
 * mismas filas que Historial. Se pliega/despliega arrastrando el asa o tocando la cabecera.
 *
 * [onSheetTopChanged] entrega el borde superior en px de ventana, ya asentado, y null al
 * desaparecer: MapScreen lo convierte en padding inferior del mapa (como AssetBottomSheet).
 */
@OptIn(FlowPreview::class)
@Composable
fun TodayTrackPanel(
    track: TodayTrackState,
    onExpandedChange: (Boolean) -> Unit,
    onLegClick: (Int) -> Unit,
    onStopRowVisible: (Int, GeoPoint) -> Unit,
    onRetry: () -> Unit,
    onOpenHistory: () -> Unit,
    onSheetTopChanged: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    var topPx by remember { mutableFloatStateOf(Float.NaN) }
    LaunchedEffect(Unit) {
        snapshotFlow { topPx }
            .debounce(TOP_SETTLE_MS)
            .collect { top -> if (!top.isNaN()) onSheetTopChanged(top) }
    }
    DisposableEffect(Unit) { onDispose { onSheetTopChanged(null) } }

    val route = track.route
    val expanded = track.panelExpanded && track.status == TodayTrackStatus.READY && route != null

    Surface(
        shape = SoltelematicBottomSheetShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = SoltelematicElevation.e2,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> topPx = coords.positionInWindow().y }
    ) {
        Column(modifier = Modifier.padding(bottom = SoltelematicSpacing.sm)) {
            PanelHeader(
                track = track,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                onRetry = onRetry
            )
            if (expanded) {
                OutlinedButton(
                    onClick = onOpenHistory,
                    shape = SoltelematicShapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SoltelematicSpacing.lg)
                        .padding(top = SoltelematicSpacing.sm)
                ) {
                    Text(stringResource(R.string.today_track_open_history), style = MaterialTheme.typography.labelLarge)
                }
                val listMaxHeight = (LocalConfiguration.current.screenHeightDp * EXPANDED_LIST_SCREEN_FRACTION).dp
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMaxHeight),
                    contentPadding = PaddingValues(
                        start = SoltelematicSpacing.lg,
                        end = SoltelematicSpacing.lg,
                        top = SoltelematicSpacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm)
                ) {
                    itemsIndexed(route.legs) { index, leg ->
                        // Misma mecánica que HistoryTimeline: LazyColumn solo compone filas visibles,
                        // así la dirección se pide únicamente cuando la parada entra en pantalla.
                        if (leg is HistoryStopLeg) {
                            val point = leg.start.point
                            LaunchedEffect(index, point) {
                                if (point != null) onStopRowVisible(index, point)
                            }
                        }
                        HistoryLegRow(
                            leg = leg,
                            selected = index == track.selectedLegIndex,
                            isPlaying = false,
                            addressResolution = track.addresses[index],
                            onClick = { onLegClick(index) }
                        )
                    }
                }
            }
        }
    }
}

/** Asa + resumen/estado: toda esta zona es arrastrable y tocable para plegar/desplegar. */
@Composable
private fun PanelHeader(
    track: TodayTrackState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    val canToggle = track.status == TodayTrackStatus.READY
    val thresholdPx = with(LocalDensity.current) { DragThreshold.toPx() }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragTotal += delta }
    val toggleLabel = stringResource(
        if (expanded) R.string.today_track_panel_collapse else R.string.today_track_panel_expand
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = canToggle,
                onDragStarted = { dragTotal = 0f },
                onDragStopped = {
                    when {
                        dragTotal <= -thresholdPx -> onExpandedChange(true)
                        dragTotal >= thresholdPx -> onExpandedChange(false)
                    }
                }
            )
            .clickable(enabled = canToggle, onClickLabel = toggleLabel) { onExpandedChange(!expanded) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SoltelematicSpacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = HandleWidth, height = HandleHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
        Column(modifier = Modifier.padding(horizontal = SoltelematicSpacing.lg)) {
            when (track.status) {
                TodayTrackStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.today_track_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = SoltelematicSpacing.sm)
                    )
                }
                TodayTrackStatus.EMPTY -> Text(
                    text = stringResource(R.string.today_track_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TodayTrackStatus.ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.today_track_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.today_track_retry)) }
                }
                TodayTrackStatus.READY -> track.route?.let { route ->
                    RouteSummaryCards(legs = route.legs, periodStats = route.periodStats)
                }
            }
        }
    }
}

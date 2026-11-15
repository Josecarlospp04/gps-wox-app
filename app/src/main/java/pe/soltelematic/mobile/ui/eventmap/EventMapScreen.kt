package pe.soltelematic.mobile.ui.eventmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.core.format.normalizeSpeedUnitSuffix
import pe.soltelematic.mobile.domain.model.AlertEvent
import pe.soltelematic.mobile.domain.model.AlertEventType
import pe.soltelematic.mobile.ui.events.AddressResolution
import pe.soltelematic.mobile.ui.events.components.AddressLine
import pe.soltelematic.mobile.ui.events.components.titleDetail
import pe.soltelematic.mobile.ui.events.components.toColors
import pe.soltelematic.mobile.ui.events.components.toIcon
import pe.soltelematic.mobile.ui.map.engine.RouteMapEngine
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerData
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import pe.soltelematic.mobile.ui.theme.SoltelematicElevation
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EVENT_DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault())
private val EventIconCircleSize = 40.dp

/**
 * Mapa de UN evento puntual (no el mapa principal, ver Destination.EventMap): un solo marcador,
 * estático, en la posición en la que ocurrió el evento -- distinta de la posición ACTUAL de la
 * unidad, que ya cambió. RouteMapEngine (no MapEngine) porque no hace falta clustering, geocercas
 * ni "mi ubicación": el mismo motor liviano que ya usa Historial para pintar marcadores propios,
 * sin línea de recorrido (polylines vacío) ni reproducción.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventMapScreen(
    onBack: () -> Unit,
    viewModel: EventMapViewModel = koinViewModel(),
    routeMapEngine: RouteMapEngine = koinInject()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_map_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.asset_detail_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val event = uiState.event
            val cameraController = routeMapEngine.rememberCameraController()

            // Una sola vez, cuando el evento llega (ver LaunchedEffect de arriba) -- no hay
            // reencuadre continuo ni seguimiento, el punto es fijo.
            LaunchedEffect(event) {
                event?.position?.let { cameraController.centerOn(it) }
            }

            routeMapEngine.Content(
                modifier = Modifier.fillMaxSize(),
                cameraController = cameraController,
                polylines = emptyList(),
                markers = event?.position?.let { position ->
                    listOf(RouteMarkerData(legIndex = 0, position = position, role = RouteMarkerRole.STOP))
                } ?: emptyList(),
                // legIndex/selectedLegIndex ambos en 0: no hay tramos que resaltar acá, solo se
                // aprovecha la variante "seleccionada" (más grande) del mismo marcador de Historial
                // para darle énfasis a este único punto.
                selectedLegIndex = 0,
                onMarkerClick = {},
                playbackPoint = null,
                playbackBearing = 0f,
                unitIcon = null,
                onCameraGesture = {},
                mapType = uiState.mapType,
                // Sin polilíneas: los límites de color no se usan en esta pantalla.
                speedRanges = SpeedRanges.DEFAULT
            )

            if (event != null) {
                EventMapDetailsCard(
                    event = event,
                    address = uiState.address,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(SoltelematicSpacing.lg)
                )
            }
        }
    }
}

@Composable
private fun EventMapDetailsCard(event: AlertEvent, address: AddressResolution?, modifier: Modifier = Modifier) {
    Card(
        shape = SoltelematicShapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = SoltelematicElevation.e2),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.md),
            modifier = Modifier.padding(SoltelematicSpacing.lg)
        ) {
            EventMapTypeIcon(event.type)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.xs)
            ) {
                Text(
                    text = event.deviceName ?: stringResource(R.string.asset_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = (event.name ?: stringResource(R.string.events_unnamed_event)) +
                        (event.titleDetail()?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = event.occurredAt.toDateTimeText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Mismo criterio que EventCard: la velocidad solo en eventos de velocidad (en el
                // resto speedText viene como "0 kph" sin significado).
                event.speedText?.takeIf { event.type == AlertEventType.OVERSPEED }?.let { speedText ->
                    Text(
                        text = normalizeSpeedUnitSuffix(speedText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AddressLine(address)
            }
        }
    }
}

@Composable
private fun EventMapTypeIcon(type: AlertEventType) {
    val icon = type.toIcon()
    val (tint, wash) = type.toColors()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(EventIconCircleSize)
            .background(wash, CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

private fun Instant?.toDateTimeText(): String = this?.let { EVENT_DATE_TIME_FORMAT.format(it) } ?: "-"

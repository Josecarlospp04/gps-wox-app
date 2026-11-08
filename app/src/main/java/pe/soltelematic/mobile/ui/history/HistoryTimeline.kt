package pe.soltelematic.mobile.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.core.format.formatDurationCompact
import pe.soltelematic.mobile.core.format.isDurationStat
import pe.soltelematic.mobile.core.format.normalizeSpeedUnit
import pe.soltelematic.mobile.core.format.sumDurationsCompact
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryLeg
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.UnitStat
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors
import pe.soltelematic.mobile.ui.theme.SoltelematicIconSpec
import pe.soltelematic.mobile.ui.theme.SoltelematicMetricTypography
import pe.soltelematic.mobile.ui.theme.SoltelematicMinTouchTarget
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val LegDotSize = 10.dp
private val PlaybackHighlightBorderWidth = 1.5.dp

/**
 * Lista simple en orden cronológico, tal como llega en HistoryRoute.legs -- todas las paradas se
 * muestran, sin filtrar por duración. El vínculo con el mapa (Bloque 2) es por índice: seleccionar
 * una fila acá es exactamente lo mismo que tocar su marcador en el mapa, misma legIndex.
 *
 * La barra de reproducción (Bloque de reproducción) vive como un item más de este mismo
 * LazyColumn, entre el resumen y la lista de tramos -- solo si hay algún punto reproducible
 * (playbackPoints no vacío); un día sin viajes (solo paradas) no la muestra.
 */
@Composable
fun HistoryTimeline(
    legs: List<HistoryLeg>,
    periodStats: List<UnitStat>,
    selectedLegIndex: Int?,
    onLegClick: (Int) -> Unit,
    addresses: Map<Int, AddressResolution>,
    // Se dispara desde un LaunchedEffect por fila -- Compose solo compone las filas visibles (+
    // un margen chico) de un LazyColumn, así que esto ES el mecanismo de "solo pedir cuando la
    // fila entra en pantalla": no hace falta rastrear scroll a mano.
    onStopRowVisible: (Int, GeoPoint) -> Unit,
    playbackPoints: List<HistoryPlaybackPoint>,
    playback: HistoryPlaybackState,
    onPlayPauseClick: () -> Unit,
    onScrub: (Int) -> Unit,
    onSpeedMultiplierClick: () -> Unit,
    // Rango de fechas actual de Historial: las gráficas son del mismo periodo, no de uno propio
    // (ver Destination.ParameterCharts.createRoute).
    onOpenParameterCharts: () -> Unit,
    modifier: Modifier = Modifier
) {
    // legIndex del punto que se está reproduciendo ahora mismo -- null si no hay reproducción o
    // el índice cayó fuera de rango (no debería, currentIndex siempre se acota a playbackPoints).
    val playingLegIndex = playbackPoints.getOrNull(playback.currentIndex)?.legIndex

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = SoltelematicSpacing.sm, horizontal = SoltelematicSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm)
    ) {
        // Primera fila de la lista, no del mapa: el espacio sale de acá, el mapa no se achica
        // (confirmado con el usuario).
        item {
            RouteSummarySection(
                legs = legs,
                periodStats = periodStats,
                onOpenParameterCharts = onOpenParameterCharts
            )
        }

        if (playbackPoints.size >= 2) {
            item {
                HistoryPlaybackBar(
                    currentPoint = playbackPoints.getOrNull(playback.currentIndex),
                    pointIndex = playback.currentIndex,
                    totalPoints = playbackPoints.size,
                    isPlaying = playback.isPlaying,
                    speedMultiplier = playback.speedMultiplier,
                    onPlayPauseClick = onPlayPauseClick,
                    onScrub = onScrub,
                    onSpeedMultiplierClick = onSpeedMultiplierClick
                )
            }
        }

        itemsIndexed(legs) { index, leg ->
            if (leg is HistoryStopLeg) {
                val point = leg.start.point
                LaunchedEffect(index, point) {
                    if (point != null) onStopRowVisible(index, point)
                }
            }
            HistoryLegRow(
                leg = leg,
                selected = index == selectedLegIndex,
                // Un tramo no se resalta dos veces con criterios distintos: la selección manual
                // (fila tocada o marcador tocado) tiene prioridad visual sobre el resaltado de
                // reproducción si coinciden en el mismo tramo.
                isPlaying = index == playingLegIndex && index != selectedLegIndex,
                addressResolution = addresses[index],
                onClick = { onLegClick(index) }
            )
        }
    }
}

/**
 * Distancia/tiempo de conducción/paradas siempre visibles en 3 tarjetas iguales (ver mockup);
 * paradas se cuenta acá (no viene en periodStats, es estructural). "Tiempo de conducción" se
 * calcula sumando la duración de cada HistoryDriveLeg en vez de leer un total del servidor: la
 * clave de total no siempre llega (ver conversación con el usuario), y calculándolo acá el
 * número de arriba siempre cuadra con la suma de los tramos de manejo que se ven abajo. Cualquier
 * otro stat del periodo (p. ej. "fuel_consumption_153", dinámico por sensor -- ver HistoryStatDto)
 * sigue en una sección colapsable aparte, sin asumir campos fijos para esos.
 */
@Composable
private fun RouteSummarySection(legs: List<HistoryLeg>, periodStats: List<UnitStat>, onOpenParameterCharts: () -> Unit) {
    val extraStats = periodStats.filterNot { it.key == "distance" || it.key == "duration" }

    Column(verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm)) {
        RouteSummaryCards(legs = legs, periodStats = periodStats)
        // Antes solo aparecía si había stats extra del servidor -- ahora siempre, porque también
        // aloja el botón "Gráficos" (ver comentario de ExtraStatsSection), sin depender de que la
        // unidad tenga algún sensor extra configurado.
        ExtraStatsSection(stats = extraStats, onOpenParameterCharts = onOpenParameterCharts)
    }
}

/**
 * Las 3 tarjetas del resumen (distancia / tiempo de conducción / paradas). Extraída tal cual de
 * RouteSummarySection para que el panel de "Recorrido de hoy" del mapa principal muestre el mismo
 * resumen con el mismo estilo; Historial se ve idéntico.
 */
@Composable
internal fun RouteSummaryCards(legs: List<HistoryLeg>, periodStats: List<UnitStat>, modifier: Modifier = Modifier) {
    val distance = periodStats.valueFor("distance")
    val drivingTime = sumDurationsCompact(legs.filterIsInstance<HistoryDriveLeg>().map { it.stats.valueFor("duration") })
    val stopCount = legs.count { it is HistoryStopLeg }

    Row(
        horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
        modifier = modifier.fillMaxWidth()
    ) {
        RouteSummaryCard(
            value = distance ?: "-",
            label = stringResource(R.string.history_summary_distance),
            modifier = Modifier.weight(1f)
        )
        RouteSummaryCard(
            value = drivingTime,
            label = stringResource(R.string.history_summary_driving_time),
            modifier = Modifier.weight(1f)
        )
        RouteSummaryCard(
            value = stopCount.toString(),
            label = stringResource(R.string.history_summary_stops),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun RouteSummaryCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = SoltelematicShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SoltelematicSpacing.md, horizontal = SoltelematicSpacing.sm)
        ) {
            Text(text = value, style = SoltelematicMetricTypography.medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Ver Historial > "Más datos": stats dinámicos del periodo + acceso a las gráficas de parámetros
 * (voltaje, batería, señal, satélites, velocidad, combustible -- ver ParameterChartsScreen) del
 * mismo rango de fechas elegido en Historial. El botón vive acá adentro, no como acción propia
 * del TopAppBar, para no sumar un ícono más ahí (confirmado con el usuario).
 */
@Composable
private fun ExtraStatsSection(stats: List<UnitStat>, onOpenParameterCharts: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "periodSummaryArrow")

    Surface(
        shape = SoltelematicShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SoltelematicSpacing.md)
            ) {
                Text(
                    text = stringResource(R.string.history_summary_more_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(arrowRotation)
                )
            }
            AnimatedVisibility(visible = expanded) {
                // stats es dinámico -- claves distintas por sensor de combustible instalado, no
                // se asumen campos fijos (mismo criterio que HistoryStatDto, ver Bloque 1). Se
                // pinta title/value tal cual llega el servidor.
                Column(
                    verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SoltelematicSpacing.md, vertical = SoltelematicSpacing.sm)
                ) {
                    stats.forEach { stat ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stat.title ?: stat.key ?: "-",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            // Dinámico (ver comentario de arriba): cualquier duración que el
                            // servidor agregue mañana también pierde los segundos, sin listar
                            // claves a mano -- ver core/format/DurationFormat.kt.isDurationStat.
                            Text(
                                text = if (isDurationStat(stat.key, stat.value)) {
                                    formatDurationCompact(stat.value)
                                } else {
                                    stat.value ?: "-"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenParameterCharts,
                        shape = SoltelematicShapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(SoltelematicIconSpec.small)
                        )
                        Spacer(modifier = Modifier.width(SoltelematicSpacing.xs))
                        Text(stringResource(R.string.history_summary_open_charts), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Fila de info (velocidad | contador de puntos | hora) + fila de controles (play/pausa, scrubber,
 * multiplicador). currentPoint puede ser null solo en el instante entre "la ruta cargó" y "el
 * primer valor de playback llegó" -- en la práctica nunca pasa (currentIndex arranca en 0 y
 * playbackPoints ya viene poblado cuando este item se muestra, ver HistoryTimeline), pero se cubre
 * igual para no forzar un !! contra el estado del ViewModel.
 */
@Composable
private fun HistoryPlaybackBar(
    currentPoint: HistoryPlaybackPoint?,
    pointIndex: Int,
    totalPoints: Int,
    isPlaying: Boolean,
    speedMultiplier: Int,
    onPlayPauseClick: () -> Unit,
    onScrub: (Int) -> Unit,
    onSpeedMultiplierClick: () -> Unit
) {
    Surface(
        shape = SoltelematicShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.xs),
            modifier = Modifier
                .fillMaxWidth()
                .padding(SoltelematicSpacing.md)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = currentPoint.speedDisplayText(),
                    style = SoltelematicMetricTypography.small,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.history_playback_point_counter_format, pointIndex + 1, totalPoints),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalSoltelematicColors.current.inkFaint
                )
                Text(
                    text = currentPoint?.position?.time.toTimeText(),
                    style = SoltelematicMetricTypography.small,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm)) {
                PlaybackPlayPauseButton(isPlaying = isPlaying, onClick = onPlayPauseClick)
                // Sin `steps`: con 200+ puntos, SliderState guarda un tickFraction por paso y
                // SliderDefaults.Track vuelve a agrupar/dibujar TODOS esos puntos en su Canvas en
                // cada recomposición -- con currentIndex cambiando hasta 30 veces por segundo
                // durante la reproducción, eso resultó ser el costo real detrás del jank medido a
                // velocidades altas (no la cámara ni el marcador, ver investigación). onScrub ya
                // redondea a entero, así que el efecto de "saltar a cualquier punto" es el mismo
                // sin pagar el dibujo de marcas -- a esta densidad de puntos tampoco se notaría el
                // "snap" visual entre uno y otro.
                Slider(
                    value = pointIndex.toFloat(),
                    onValueChange = { onScrub(it.roundToInt()) },
                    valueRange = 0f..(totalPoints - 1).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
                SpeedMultiplierChip(multiplier = speedMultiplier, onClick = onSpeedMultiplierClick)
            }
        }
    }
}

/** Círculo primary de 48dp -- mismo criterio de "botón propio" que HistoryCalendarButton en HistoryScreen.kt. */
@Composable
private fun PlaybackPlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SoltelematicMinTouchTarget)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (isPlaying) R.string.history_playback_pause else R.string.history_playback_play
            ),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * Chip propio en vez de AssistChip/SuggestionChip de Material3: esos traen una altura fija menor a
 * 48dp (ver AssistChipDefaults.Height), y acá el mínimo táctil es requisito. Mismo patrón de Box +
 * clickable que HistoryCalendarButton.
 */
@Composable
private fun SpeedMultiplierChip(multiplier: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = SoltelematicMinTouchTarget)
            .widthIn(min = SoltelematicMinTouchTarget)
            .clip(SoltelematicShapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = SoltelematicSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.history_playback_speed_multiplier_format, multiplier),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** "46 KM/H" -- speedText es el número crudo del servidor (ver HistoryPositionDto.s), sin unidad
 * propia; normalizeSpeedUnit(null) es la función de formato de velocidad ya existente en el resto
 * de la app (ver core/format/SpeedFormat.kt), acá con unit=null porque el servidor no manda una
 * distinta para positions[].s. */
private fun HistoryPlaybackPoint?.speedDisplayText(): String {
    val raw = this?.position?.speedText ?: return "-"
    return "$raw ${normalizeSpeedUnit(null)}"
}

@Composable
internal fun HistoryLegRow(
    leg: HistoryLeg,
    selected: Boolean,
    // true mientras el marcador de reproducción recorre este tramo (ver HistoryTimeline.playingLegIndex)
    // -- un borde primary, no un relleno, para no competir visualmente con el fondo sólido de selected.
    isPlaying: Boolean,
    addressResolution: AddressResolution?,
    onClick: () -> Unit
) {
    Surface(
        shape = SoltelematicShapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SoltelematicMinTouchTarget)
            .let {
                if (isPlaying) {
                    it.border(PlaybackHighlightBorderWidth, MaterialTheme.colorScheme.primary, SoltelematicShapes.medium)
                } else {
                    it
                }
            }
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.md),
            modifier = Modifier
                .fillMaxWidth()
                .padding(SoltelematicSpacing.md)
        ) {
            LegDot(leg)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = leg.displayTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Intervalo explícito de inicio a fin -- una sola hora suelta ("00:44") no deja
                // claro si es el inicio, el fin o parte de la duración (confirmado con el
                // usuario). start/end.time vienen ambos del servidor por tramo, no se calculan.
                Text(
                    text = leg.intervalText(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (leg is HistoryDriveLeg) {
                    val distance = leg.stats.valueFor("distance")
                    if (distance != null) {
                        Text(
                            text = distance,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (leg is HistoryStopLeg) {
                    AddressLine(addressResolution)
                }
            }
            // Duración del tramo -- antes esta columna mostraba la hora de inicio, ahora eso vive
            // explícito en el intervalo de la segunda línea; acá va la duración para que cuadre a
            // simple vista con el intervalo mostrado a la izquierda.
            Text(
                text = formatDurationCompact(leg.stats.valueFor("duration")),
                style = SoltelematicMetricTypography.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Punto de color: verde statusMoving para un viaje, ámbar statusIdle para una parada. */
@Composable
private fun LegDot(leg: HistoryLeg) {
    val color = when (leg) {
        is HistoryDriveLeg -> LocalSoltelematicColors.current.statusMoving
        is HistoryStopLeg -> LocalSoltelematicColors.current.statusIdle
    }
    Box(modifier = Modifier.size(LegDotSize).background(color, CircleShape))
}

/**
 * "HH:mm – HH:mm" tal cual lo manda el servidor por tramo (start/end.time de HistoryEndpoint) --
 * nunca se calcula sumando la duración al inicio, porque el propio tramo ya trae su hora de fin
 * real. "--:--" solo si el servidor no manda ese lado en particular (no debería pasar en la
 * práctica), nunca un valor inventado.
 */
private fun HistoryLeg.intervalText(): String = "${start.time.toTimeText()} – ${end.time.toTimeText()}"

// Reutiliza los strings de dirección de la ficha (Sprint 2A): mismo significado ahí y acá.
// resolution null = todavía no entró en pantalla (LaunchedEffect no ha corrido) -- se muestra
// igual que "cargando", porque para cuando el usuario alcanza a leerlo ya debería haber corrido.
@Composable
private fun AddressLine(resolution: AddressResolution?) {
    val address = (resolution as? AddressResolution.Resolved)?.address
    val text = address ?: stringResource(
        if (resolution == null || resolution is AddressResolution.Loading) {
            R.string.asset_detail_address_loading
        } else {
            R.string.asset_detail_address_unavailable
        }
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontStyle = if (address != null) FontStyle.Normal else FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun List<UnitStat>.valueFor(key: String): String? = firstOrNull { it.key == key }?.value

// Título fijo por tipo de tramo, no el title crudo del servidor ("Detener"/"Conducir", jerga de
// backend) -- mismo criterio que status.title en SummaryTab/AssetBottomSheet: la app define su
// propia etiqueta en vez de mostrar la del servidor tal cual.
@Composable
private fun HistoryLeg.displayTitle(): String = when (this) {
    is HistoryStopLeg -> stringResource(R.string.history_leg_stop_title)
    is HistoryDriveLeg -> stringResource(R.string.history_leg_drive_title)
}

private fun Instant?.toTimeText(): String = this?.let { TIME_FORMAT.format(it) } ?: "--:--"

package pe.soltelematic.mobile.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.core.format.formatDurationCompact
import pe.soltelematic.mobile.ui.history.AddressResolution
import pe.soltelematic.mobile.ui.history.InfoCardKind
import pe.soltelematic.mobile.ui.history.InfoCardState
import pe.soltelematic.mobile.ui.history.formatCardDate
import pe.soltelematic.mobile.ui.history.formatCardEndTime
import pe.soltelematic.mobile.ui.history.formatCardTime
import pe.soltelematic.mobile.ui.history.formatCoordinates
import pe.soltelematic.mobile.ui.theme.SoltelematicElevation
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

private val LabelWidth = 76.dp

/**
 * Tarjeta de información del icono tocado en el mapa (evento de motor, parada, partida o llegada), con
 * botón de cerrar. Compartida por la Última ruta (encima del panel inferior) y Historial (abajo, dentro
 * del área del mapa). Tocar otro icono la reemplaza: quien la muestra solo pasa el estado vigente.
 */
@Composable
fun RouteInfoCard(
    card: InfoCardState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = card.data
    Surface(
        shape = SoltelematicShapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = SoltelematicElevation.e2,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(
                start = SoltelematicSpacing.lg,
                end = SoltelematicSpacing.xs,
                top = SoltelematicSpacing.xs,
                bottom = SoltelematicSpacing.md
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(data.kind.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.route_info_close))
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.xs),
                modifier = Modifier.padding(end = SoltelematicSpacing.md)
            ) {
                if (data.kind == InfoCardKind.STOP) {
                    InfoRow(stringResource(R.string.route_info_date), formatCardDate(data.start))
                    InfoRow(stringResource(R.string.route_info_stop_start), formatCardTime(data.start))
                    InfoRow(stringResource(R.string.route_info_stop_end), formatCardEndTime(data.start, data.end))
                    InfoRow(stringResource(R.string.route_info_duration), formatDurationCompact(data.durationRaw))
                } else {
                    InfoRow(stringResource(R.string.route_info_date), formatCardDate(data.start))
                    InfoRow(stringResource(R.string.route_info_time), formatCardTime(data.start))
                }
                InfoRow(stringResource(R.string.route_info_place), placeText(card))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LabelWidth)
        )
        Spacer(modifier = Modifier.width(SoltelematicSpacing.sm))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Dirección; mientras carga "Obteniendo dirección…"; si el servidor no la devuelve, las coordenadas. */
@Composable
private fun placeText(card: InfoCardState): String = when (val address = card.address) {
    AddressResolution.Loading -> stringResource(R.string.route_info_address_loading)
    is AddressResolution.Resolved -> address.address?.takeIf { it.isNotBlank() }
        ?: card.data.point?.let(::formatCoordinates)
        ?: "-"
}

private fun InfoCardKind.titleRes(): Int = when (this) {
    InfoCardKind.IGNITION_ON -> R.string.route_info_ignition_on
    InfoCardKind.IGNITION_OFF -> R.string.route_info_ignition_off
    InfoCardKind.STOP -> R.string.route_info_stop
    InfoCardKind.ROUTE_START -> R.string.route_info_route_start
    InfoCardKind.ROUTE_END -> R.string.route_info_route_end
}

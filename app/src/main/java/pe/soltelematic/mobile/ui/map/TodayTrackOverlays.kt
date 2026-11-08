package pe.soltelematic.mobile.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.domain.model.Asset
import pe.soltelematic.mobile.ui.theme.SoltelematicElevation
import pe.soltelematic.mobile.ui.theme.SoltelematicMinTouchTarget
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

/**
 * Lista de sugerencias bajo el buscador del mapa (máx. MAX_SEARCH_SUGGESTIONS filas, ver
 * MapUiState.searchSuggestions). Elegir una fila entra en el modo "Recorrido de hoy".
 */
@Composable
fun SearchSuggestionsList(
    assets: List<Asset>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = SoltelematicShapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = SoltelematicElevation.e2,
        modifier = modifier
    ) {
        Column {
            assets.forEachIndexed { index, asset ->
                if (index > 0) HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SoltelematicMinTouchTarget)
                        .clickable { onSelect(asset.id) }
                        .padding(horizontal = SoltelematicSpacing.lg, vertical = SoltelematicSpacing.sm),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = asset.name.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.today_track_suggestion_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Indicador del modo "Recorrido de hoy": nombre de la unidad y botón X para salir. Ocupa el lugar
 * de los chips de filtro mientras dura el modo; el estado del recorrido (cargando / sin recorrido /
 * error) y el resumen viven en TodayTrackPanel.
 */
@Composable
fun TodayTrackChip(
    unitName: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = SoltelematicShapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = SoltelematicElevation.e2,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = SoltelematicSpacing.lg, top = SoltelematicSpacing.xs, bottom = SoltelematicSpacing.xs)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.today_track_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = unitName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.today_track_exit))
            }
        }
    }
}

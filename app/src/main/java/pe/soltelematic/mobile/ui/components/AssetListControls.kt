package pe.soltelematic.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.domain.model.AssetFilter
import pe.soltelematic.mobile.ui.theme.BrandLogo
import pe.soltelematic.mobile.ui.theme.SoltelematicElevation
import pe.soltelematic.mobile.ui.theme.SoltelematicMinTouchTarget
import pe.soltelematic.mobile.ui.theme.SoltelematicPillShape
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

/**
 * Buscador compartido entre Mapa y Unidades (Sprint 5, Bloque 3): misma Surface +
 * OutlinedTextField + ícono de marca + botón de limpiar. trailingContent es el único hueco de
 * personalización -- Mapa le agrega la campana con su punto de no-leídas (ver MapScreen.kt),
 * Unidades no le agrega nada.
 */
@Composable
fun AssetSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Acción del botón "buscar" del teclado (Enter). null = comportamiento de siempre, sin
    // ImeAction.Search -- solo el Mapa lo usa (seleccionar la única unidad sugerida).
    onSearch: (() -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        shape = SoltelematicShapes.small,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = SoltelematicElevation.e2,
        modifier = modifier
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    stringResource(R.string.map_search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = true,
            keyboardOptions = if (onSearch != null) KeyboardOptions(imeAction = ImeAction.Search) else KeyboardOptions.Default,
            keyboardActions = if (onSearch != null) KeyboardActions(onSearch = { onSearch() }) else KeyboardActions.Default,
            // Ícono de marca en vez de una lupa genérica (ver mockup del mapa): mismo criterio acá.
            leadingIcon = { BrandLogo(brand = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_search_clear))
                        }
                    }
                    trailingContent()
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SoltelematicMinTouchTarget)
        )
    }
}

/**
 * Chips de estado compartidos entre Mapa y Unidades: mismo AssetFilter, mismos conteos, mismo
 * criterio de mapeo a status.type (ver AssetFilter.matches), mismo estilo pill.
 */
@Composable
fun AssetFilterChipsRow(
    filters: List<AssetFilter>,
    activeFilter: AssetFilter,
    counts: Map<AssetFilter, Int>,
    onFilterSelected: (AssetFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
        modifier = modifier.horizontalScroll(rememberScrollState())
    ) {
        filters.forEach { filter ->
            val selected = filter == activeFilter
            FilterChip(
                selected = selected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        "${stringResource(filter.labelRes())} ${counts[filter] ?: 0}",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                shape = SoltelematicPillShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

fun AssetFilter.labelRes(): Int = when (this) {
    AssetFilter.ALL -> R.string.map_filter_all
    AssetFilter.ON_ROUTE -> R.string.map_filter_on_route
    AssetFilter.STOPPED -> R.string.map_filter_stopped
    AssetFilter.BLOCKED -> R.string.map_filter_blocked
    AssetFilter.OFFLINE -> R.string.map_filter_offline
}

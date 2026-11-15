package pe.soltelematic.mobile.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.domain.model.SpeedBand
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.domain.model.SpeedRangesError
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors
import pe.soltelematic.mobile.ui.theme.SoltelematicMinTouchTarget
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

private val BarHeight = SoltelematicSpacing.md
private val SwatchSize = SoltelematicSpacing.lg

/**
 * "Colores por velocidad" de Cuenta: franja y leyenda con los cuatro colores fijos y su rango, y los
 * tres límites editables (20 / 60 / 80 km/h por defecto). Un borrador inválido no se guarda: se
 * explica el motivo bajo los campos y "Guardar" queda desactivado. Los colores nunca se editan.
 */
@Composable
fun SpeedColorsSection(
    state: AccountUiState,
    onLimitChanged: (index: Int, text: String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalSoltelematicColors.current
    val ranges = state.previewSpeedRanges
    val fields = state.speedFields

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.account_speed_colors_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.inkFaint,
            modifier = Modifier.padding(bottom = SoltelematicSpacing.sm)
        )
        Text(
            text = stringResource(R.string.account_speed_colors_help),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = SoltelematicSpacing.md)
        )

        // De un vistazo: una franja con los cuatro colores en orden, y debajo cada color con su rango.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .clip(RoundedCornerShape(BarHeight / 2))
        ) {
            SpeedBand.entries.forEach { band ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(BarHeight)
                        .background(colors.speedColor(band))
                )
            }
        }
        Spacer(modifier = Modifier.height(SoltelematicSpacing.md))
        SpeedBand.entries.forEach { band ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SoltelematicSpacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .size(SwatchSize)
                        .background(colors.speedColor(band), CircleShape)
                )
                Spacer(modifier = Modifier.width(SoltelematicSpacing.md))
                Text(
                    text = bandName(band),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = bandRangeText(band, ranges),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(SoltelematicSpacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm), modifier = Modifier.fillMaxWidth()) {
            LimitField(
                label = stringResource(R.string.account_speed_limit_label, 1),
                value = fields.first,
                isError = state.speedError != null,
                onValueChange = { onLimitChanged(0, it) },
                modifier = Modifier.weight(1f)
            )
            LimitField(
                label = stringResource(R.string.account_speed_limit_label, 2),
                value = fields.second,
                isError = state.speedError != null,
                onValueChange = { onLimitChanged(1, it) },
                modifier = Modifier.weight(1f)
            )
            LimitField(
                label = stringResource(R.string.account_speed_limit_label, 3),
                value = fields.third,
                isError = state.speedError != null,
                onValueChange = { onLimitChanged(2, it) },
                modifier = Modifier.weight(1f)
            )
        }
        state.speedError?.let { error ->
            Text(
                text = stringResource(error.messageRes()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = SoltelematicSpacing.sm)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SoltelematicSpacing.md)
        ) {
            Button(
                onClick = onSave,
                enabled = state.canSaveSpeedRanges,
                shape = SoltelematicShapes.small,
                modifier = Modifier.heightIn(min = SoltelematicMinTouchTarget)
            ) {
                Text(stringResource(R.string.account_speed_save), style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onReset,
                enabled = state.canResetSpeedRanges,
                modifier = Modifier.heightIn(min = SoltelematicMinTouchTarget)
            ) {
                Text(stringResource(R.string.account_speed_reset), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LimitField(
    label: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(stringResource(R.string.account_speed_unit)) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = SoltelematicShapes.small,
        modifier = modifier
    )
}

@Composable
private fun bandName(band: SpeedBand): String = stringResource(
    when (band) {
        SpeedBand.LOW -> R.string.account_speed_band_low
        SpeedBand.MEDIUM -> R.string.account_speed_band_medium
        SpeedBand.HIGH -> R.string.account_speed_band_high
        SpeedBand.VERY_HIGH -> R.string.account_speed_band_very_high
    }
)

/** "Menos de 20 km/h", "20 a menos de 60 km/h", ... -- el límite inferior de cada banda va incluido. */
@Composable
private fun bandRangeText(band: SpeedBand, ranges: SpeedRanges): String = when (band) {
    SpeedBand.LOW -> stringResource(R.string.account_speed_range_below, ranges.first)
    SpeedBand.MEDIUM -> stringResource(R.string.account_speed_range_between, ranges.first, ranges.second)
    SpeedBand.HIGH -> stringResource(R.string.account_speed_range_between, ranges.second, ranges.third)
    SpeedBand.VERY_HIGH -> stringResource(R.string.account_speed_range_from, ranges.third)
}

private fun SpeedRangesError.messageRes(): Int = when (this) {
    SpeedRangesError.NOT_A_NUMBER -> R.string.account_speed_error_not_a_number
    SpeedRangesError.NOT_POSITIVE -> R.string.account_speed_error_not_positive
    SpeedRangesError.NOT_ASCENDING -> R.string.account_speed_error_not_ascending
}

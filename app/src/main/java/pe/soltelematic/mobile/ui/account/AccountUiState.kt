package pe.soltelematic.mobile.ui.account

import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.domain.model.SpeedRangesError
import pe.soltelematic.mobile.domain.model.SpeedRangesParse
import pe.soltelematic.mobile.domain.model.VolumeUnit

data class AccountUiState(
    val email: String? = null,
    val serverName: String? = null,
    // Tamaño de lo que ya trae observeAssets() (espejo de Room) al abrir la pantalla -- no dispara
    // refresh de red. null solo si la lectura todavía no ha corrido (ver AccountViewModel.init).
    val unitCount: Int? = null,
    val isLoading: Boolean = true,
    // Reflejo de UserPreferencesDataStore.volumeUnit, litros por defecto (ver AccountViewModel).
    val volumeUnit: VolumeUnit = VolumeUnit.LITERS,
    // Reflejo de UserPreferencesDataStore.showTail, activado por defecto (ver AccountViewModel).
    val showTail: Boolean = true,
    // Límites de color de la línea por velocidad guardados (UserPreferencesDataStore.speedRanges).
    val speedRanges: SpeedRanges = SpeedRanges.DEFAULT,
    // Lo que el usuario está escribiendo en los tres campos; null = sin editar (se muestran los
    // guardados). Es texto: se valida al escribir, y solo un conjunto válido se guarda.
    val speedDraft: SpeedLimitsDraft? = null
) {
    /** Texto que muestran los tres campos: el borrador, o los límites guardados. */
    val speedFields: SpeedLimitsDraft
        get() = speedDraft ?: SpeedLimitsDraft(
            speedRanges.first.toString(),
            speedRanges.second.toString(),
            speedRanges.third.toString()
        )

    private val speedParse: SpeedRangesParse
        get() = SpeedRanges.parse(speedFields.first, speedFields.second, speedFields.third)

    /** Por qué el borrador no es válido; null sin editar o con un borrador válido. */
    val speedError: SpeedRangesError?
        get() = if (speedDraft == null) null else (speedParse as? SpeedRangesParse.Invalid)?.error

    /** Borrador válido y distinto de lo guardado. */
    val canSaveSpeedRanges: Boolean
        get() = speedDraft != null && (speedParse as? SpeedRangesParse.Valid)?.ranges.let { it != null && it != speedRanges }

    /** Hay algo que restaurar: un borrador o límites guardados distintos de los de por defecto. */
    val canResetSpeedRanges: Boolean
        get() = speedDraft != null || speedRanges != SpeedRanges.DEFAULT

    /** Límites que dibuja la leyenda: los del borrador si ya es válido, si no los guardados. */
    val previewSpeedRanges: SpeedRanges
        get() = (speedParse as? SpeedRangesParse.Valid)?.ranges ?: speedRanges
}

/** Los tres campos de límites tal como los escribió el usuario (texto, sin validar). */
data class SpeedLimitsDraft(val first: String, val second: String, val third: String)

package pe.soltelematic.mobile.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.core.storage.UserPreferencesDataStore
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.domain.model.SpeedRangesParse
import pe.soltelematic.mobile.domain.model.VolumeUnit
import pe.soltelematic.mobile.domain.repository.AssetRepository
import pe.soltelematic.mobile.domain.repository.AuthRepository

private const val MAX_SPEED_LIMIT_DIGITS = 3

class AccountViewModel(
    private val authRepository: AuthRepository,
    private val assetRepository: AssetRepository,
    private val userPreferences: UserPreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    init {
        viewModelScope.launch {
            // lastEmail primero: si /user falla sin red, igual se muestra algo con sentido
            // en vez de un campo vacío.
            userPreferences.lastEmail.first()?.let { email ->
                _uiState.update { it.copy(email = email) }
            }
            when (val userResult = authRepository.getCurrentUser()) {
                is ApiResult.Success -> _uiState.update { it.copy(email = userResult.data.email ?: it.email) }
                is ApiResult.Error -> Unit // se queda con lastEmail
            }
            when (val configResult = authRepository.getServerConfig()) {
                is ApiResult.Success -> _uiState.update { it.copy(serverName = configResult.data.serverName) }
                is ApiResult.Error -> Unit
            }
            // Ya cacheado en Room -- observeAssets() es el espejo local, no dispara red nueva.
            val unitCount = assetRepository.observeAssets().first().size
            _uiState.update { it.copy(unitCount = unitCount, isLoading = false) }
        }
        viewModelScope.launch {
            userPreferences.volumeUnit.collect { unit ->
                _uiState.update { it.copy(volumeUnit = unit) }
            }
        }
        viewModelScope.launch {
            userPreferences.showTail.collect { enabled ->
                _uiState.update { it.copy(showTail = enabled) }
            }
        }
        viewModelScope.launch {
            userPreferences.speedRanges.collect { ranges ->
                _uiState.update { it.copy(speedRanges = ranges) }
            }
        }
    }

    /** index 0..2 = límite 1..3. Solo dígitos (máx. 3): el teclado numérico no es una garantía. */
    fun onSpeedLimitChanged(index: Int, raw: String) {
        val digits = raw.filter { it.isDigit() }.take(MAX_SPEED_LIMIT_DIGITS)
        _uiState.update { state ->
            val fields = state.speedFields
            val next = when (index) {
                0 -> fields.copy(first = digits)
                1 -> fields.copy(second = digits)
                else -> fields.copy(third = digits)
            }
            // Igual a lo guardado: ya no hay nada pendiente.
            val saved = state.copy(speedDraft = null).speedFields
            state.copy(speedDraft = next.takeIf { it != saved })
        }
    }

    /** Guarda el borrador solo si es válido; si no, no hace nada (la pantalla ya explica por qué). */
    fun onSpeedLimitsSave() {
        val fields = _uiState.value.speedFields
        val parsed = SpeedRanges.parse(fields.first, fields.second, fields.third) as? SpeedRangesParse.Valid ?: return
        // Se refleja ya en el estado; el flujo del DataStore lo confirma después.
        _uiState.update { it.copy(speedRanges = parsed.ranges, speedDraft = null) }
        viewModelScope.launch { userPreferences.setSpeedRanges(parsed.ranges) }
    }

    fun onSpeedLimitsReset() {
        _uiState.update { it.copy(speedRanges = SpeedRanges.DEFAULT, speedDraft = null) }
        viewModelScope.launch { userPreferences.resetSpeedRanges() }
    }

    fun onShowTailChanged(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setShowTail(enabled)
        }
    }

    fun onVolumeUnitSelected(unit: VolumeUnit) {
        viewModelScope.launch {
            userPreferences.setVolumeUnit(unit)
        }
    }

    fun onLogout() {
        viewModelScope.launch {
            // Primero se revoca en el servidor y se borra la sesión local (siempre, ver
            // AuthRepository.logout); después, lo que ya hacía.
            authRepository.logout()
            // Sin esto, el siguiente usuario que inicie sesión en este dispositivo vería
            // la flota del anterior hasta el próximo refresh.
            assetRepository.clear()
            _loggedOut.emit(Unit)
        }
    }
}

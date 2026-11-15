package pe.soltelematic.mobile.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.domain.model.VolumeUnit

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "soltelematic_user_prefs"
)

private val KEY_LAST_EMAIL = stringPreferencesKey("last_email")
private val KEY_SHOW_GEOFENCES = booleanPreferencesKey("show_geofences")
private val KEY_MAP_TYPE = stringPreferencesKey("map_type")
private val KEY_VOLUME_UNIT = stringPreferencesKey("volume_unit")
private val KEY_SHOW_TAIL = booleanPreferencesKey("show_tail")
private val KEY_SPEED_LIMIT_1 = intPreferencesKey("speed_limit_1")
private val KEY_SPEED_LIMIT_2 = intPreferencesKey("speed_limit_2")
private val KEY_SPEED_LIMIT_3 = intPreferencesKey("speed_limit_3")

/** Preferencias NO sensibles. Tokens nunca van aquí, esos son de SecureTokenStorage. */
class UserPreferencesDataStore(private val context: Context) {

    val lastEmail: Flow<String?> = context.userPreferencesDataStore.data.map { prefs ->
        prefs[KEY_LAST_EMAIL]
    }

    suspend fun saveLastEmail(email: String) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[KEY_LAST_EMAIL] = email
        }
    }

    // Apagado por defecto (Sprint 5): el mapa es la pantalla principal y su información primaria
    // son las unidades -- la capa de geocercas se enciende cuando el usuario la pide, no antes.
    val showGeofences: Flow<Boolean> = context.userPreferencesDataStore.data.map { prefs ->
        prefs[KEY_SHOW_GEOFENCES] ?: false
    }

    suspend fun setShowGeofences(enabled: Boolean) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[KEY_SHOW_GEOFENCES] = enabled
        }
    }

    // Normal por defecto (mismo criterio que showGeofences: la app arranca con la vista más
    // liviana). runCatching cubre un valor guardado por una versión futura/pasada de MapType
    // que ya no exista -- cae a NORMAL en vez de crashear.
    val mapType: Flow<MapType> = context.userPreferencesDataStore.data.map { prefs ->
        prefs[KEY_MAP_TYPE]?.let { raw -> runCatching { MapType.valueOf(raw) }.getOrNull() } ?: MapType.NORMAL
    }

    suspend fun setMapType(type: MapType) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[KEY_MAP_TYPE] = type.name
        }
    }

    // Litros por defecto: el mercado donde opera la flota hoy. runCatching cubre el mismo caso que
    // mapType (valor guardado por una versión que ya no tenga ese enum).
    val volumeUnit: Flow<VolumeUnit> = context.userPreferencesDataStore.data.map { prefs ->
        prefs[KEY_VOLUME_UNIT]?.let { raw -> runCatching { VolumeUnit.valueOf(raw) }.getOrNull() } ?: VolumeUnit.LITERS
    }

    suspend fun setVolumeUnit(unit: VolumeUnit) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[KEY_VOLUME_UNIT] = unit.name
        }
    }

    // Activada por defecto: la cola de la unidad enfocada es parte de la vista normal del mapa; el
    // ajuste (Cuenta) es para quien prefiere un mapa más limpio. No afecta a la ruta del Historial.
    val showTail: Flow<Boolean> = context.userPreferencesDataStore.data.map { prefs ->
        prefs[KEY_SHOW_TAIL] ?: true
    }

    suspend fun setShowTail(enabled: Boolean) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[KEY_SHOW_TAIL] = enabled
        }
    }

    // Límites de las bandas de color de la línea por velocidad (km/h). Tres claves, siguiendo el patrón
    // de showGeofences: sin valor guardado, o con un conjunto no válido, se usan los de por defecto
    // (SpeedRanges.fromStored).
    val speedRanges: Flow<SpeedRanges> = context.userPreferencesDataStore.data.map { prefs ->
        SpeedRanges.fromStored(prefs[KEY_SPEED_LIMIT_1], prefs[KEY_SPEED_LIMIT_2], prefs[KEY_SPEED_LIMIT_3])
    }

    /** Solo guarda límites válidos; uno inválido se ignora (la validación con mensaje vive en Cuenta). */
    suspend fun setSpeedRanges(ranges: SpeedRanges) {
        if (!ranges.isValid) return
        context.userPreferencesDataStore.edit { prefs ->
            prefs[KEY_SPEED_LIMIT_1] = ranges.first
            prefs[KEY_SPEED_LIMIT_2] = ranges.second
            prefs[KEY_SPEED_LIMIT_3] = ranges.third
        }
    }

    /** "Restaurar valores por defecto": borra las tres claves. */
    suspend fun resetSpeedRanges() {
        context.userPreferencesDataStore.edit { prefs ->
            prefs.remove(KEY_SPEED_LIMIT_1)
            prefs.remove(KEY_SPEED_LIMIT_2)
            prefs.remove(KEY_SPEED_LIMIT_3)
        }
    }
}

package pe.soltelematic.mobile.data.repository

import pe.soltelematic.mobile.core.result.ApiError
import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryRoute
import java.time.LocalDate

/** Días que mira la búsqueda de la última ruta: hoy y los 6 anteriores. */
const val LAST_ROUTE_SEARCH_DAYS = 7

sealed interface LastRouteResult {
    /** El día más reciente con algún tramo "drive", junto con la ruta completa de ese día. */
    data class Found(val day: LocalDate, val route: HistoryRoute) : LastRouteResult

    /** Ninguno de los días buscados tiene un tramo "drive". */
    data object NoRoute : LastRouteResult

    /** Una petición falló: no se sabe si había ruta, así que NO se sigue con días más viejos. */
    data class Failed(val error: ApiError) : LastRouteResult
}

/** "Recorrido" = cualquier tramo "drive" del servidor, sin distancia mínima propia. */
fun HistoryRoute.hasDrive(): Boolean = legs.any { it is HistoryDriveLeg }

/**
 * Primer día a consultar. Una unidad no tiene posiciones posteriores a su último reporte, así que
 * si éste fue antes de hoy se salta directo a ese día (ahorra las peticiones de los días en medio).
 * Sin dato o con una fecha futura (reloj desfasado) se empieza por hoy.
 */
internal fun lastRouteSearchStart(today: LocalDate, lastSeenDay: LocalDate?): LocalDate =
    if (lastSeenDay != null && lastSeenDay.isBefore(today)) lastSeenDay else today

/**
 * Último día con recorrido: hoy y, si no tiene ningún tramo "drive" (vacío o solo paradas), hacia
 * atrás día por día hasta LAST_ROUTE_SEARCH_DAYS en total. [fetchDay] pide la ruta de un día
 * (hoy: de 00:00 a ahora; un día pasado: de 00:00 a 23:59:59). Peor caso: 7 peticiones, en serie.
 */
suspend fun findLastRoute(
    today: LocalDate,
    lastSeenDay: LocalDate?,
    fetchDay: suspend (LocalDate) -> ApiResult<HistoryRoute>
): LastRouteResult {
    val oldest = today.minusDays(LAST_ROUTE_SEARCH_DAYS - 1L)
    var day = lastRouteSearchStart(today, lastSeenDay)
    while (!day.isBefore(oldest)) {
        when (val result = fetchDay(day)) {
            is ApiResult.Success -> if (result.data.hasDrive()) return LastRouteResult.Found(day, result.data)
            is ApiResult.Error -> return LastRouteResult.Failed(result.error)
        }
        day = day.minusDays(1)
    }
    return LastRouteResult.NoRoute
}

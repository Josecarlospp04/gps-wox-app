package pe.soltelematic.mobile.domain.repository

import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.domain.model.AssetDetail
import pe.soltelematic.mobile.domain.model.DeviceCommand
import pe.soltelematic.mobile.domain.model.DeviceService
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.ParameterSeries
import pe.soltelematic.mobile.domain.model.SendCommandOutcome
import pe.soltelematic.mobile.domain.model.ServiceCreateForm
import pe.soltelematic.mobile.domain.model.ServiceCreateRequest
import pe.soltelematic.mobile.domain.model.UnitStat
import java.time.LocalDateTime

/**
 * Métodos independientes a propósito (device/{id}, history en sus dos formas, address, commands,
 * services): un fallo o demora en la dirección geocodificada, en las métricas del día, en los
 * comandos disponibles o en los servicios de mantenimiento no debe bloquear la ficha, que ya tiene
 * lo esencial con getDetail. Cada uno vive en su propio ApiResult; el ViewModel decide qué hacer
 * con cada uno por separado en vez de esperar a que respondan todos para pintar algo.
 */
interface AssetDetailRepository {
    suspend fun getDetail(id: Int): ApiResult<AssetDetail>

    /** stats de hoy (medianoche a ahora) para el bloque "HOY" de la pestaña Resumen. */
    suspend fun getTodayStats(id: Int): ApiResult<List<UnitStat>>

    /** Ruta completa (viajes, paradas, polyline) para la pantalla de Historial, Sprint 2B. */
    suspend fun getRoute(id: Int, from: LocalDateTime, to: LocalDateTime): ApiResult<HistoryRoute>

    /**
     * Ruta de HOY (00:00 de la zona del teléfono hasta ahora) para el modo "Recorrido de hoy" del
     * mapa principal. Mismo endpoint y mismo rango que getTodayStats, pero con la ruta completa.
     */
    suspend fun getTodayRoute(id: Int): ApiResult<HistoryRoute>

    /**
     * Series de parámetros (voltaje, batería, señal, satélites, velocidad, combustible) para las
     * gráficas de la pantalla nueva accesible desde Historial (parche 8, GET clientlite/parameters).
     * Solo trae las series que la unidad SÍ reporta -- ver ParametersResponseDto. El servidor ya
     * submuestrea con LTTB a máximo 500 puntos por serie, la app no vuelve a reducir nada.
     */
    suspend fun getParameters(id: Int, from: LocalDateTime, to: LocalDateTime): ApiResult<List<ParameterSeries>>

    suspend fun getAddress(lat: Double, lng: Double): ApiResult<String?>

    /** Solo connection=gprs -- SMS no está implementado en la app todavía. */
    suspend fun getCommands(id: Int): ApiResult<List<DeviceCommand>>

    /**
     * Servicios de mantenimiento programado para la pestaña Servicios (parche 6, ver
     * DeviceServiceDto) -- reemplaza al JSON crudo que se mostraba antes desde
     * AssetDetail.services (device/{id}, que ya no se usa para pintar esta pestaña).
     */
    suspend fun getServices(id: Int): ApiResult<List<DeviceService>>

    /** Metadata para el formulario "Nuevo servicio" (ver ServiceCreateForm/ServiceFormSheet). */
    suspend fun getServiceCreateForm(id: Int): ApiResult<ServiceCreateForm>

    /**
     * El id del servicio creado no le sirve a nadie hoy (la pantalla refresca la lista completa
     * con getServices tras un 200, ver AssetDetailViewModel.onCreateService) -- ApiResult<Unit>
     * en vez de devolver un DeviceService a medias armado en el cliente. Los errores de campo
     * (422) SÍ importan, y ya viajan en ApiResult.Error/ApiError.ValidationError como el resto de
     * formularios (ver GeofencesRepository.createGeofence) -- ojo que este endpoint en particular
     * los devuelve bajo la clave "id", no el nombre de un campo real (ver ServiceFormSheet).
     */
    suspend fun createService(id: Int, request: ServiceCreateRequest): ApiResult<Unit>

    /**
     * attributes son los valores del formulario por nombre de CommandAttribute.name -- ya en
     * String, listos para el body form-urlencoded (ver AssetDetailApi.sendCommand). El resultado
     * de negocio (éxito/rechazo del servidor) va en SendCommandOutcome dentro del Success, nunca
     * como ApiError: el servidor responde 200 en ambos casos.
     */
    suspend fun sendCommand(id: Int, type: String, attributes: Map<String, String>): ApiResult<SendCommandOutcome>
}

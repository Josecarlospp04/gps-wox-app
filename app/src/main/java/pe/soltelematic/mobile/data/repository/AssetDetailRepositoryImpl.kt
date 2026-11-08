package pe.soltelematic.mobile.data.repository

import pe.soltelematic.mobile.core.network.ApiCallExecutor
import pe.soltelematic.mobile.core.network.IconUrlResolver
import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.data.mapper.toDomain
import pe.soltelematic.mobile.data.remote.api.AssetDetailApi
import pe.soltelematic.mobile.data.mapper.toDto
import pe.soltelematic.mobile.domain.model.AssetDetail
import pe.soltelematic.mobile.domain.model.DeviceCommand
import pe.soltelematic.mobile.domain.model.DeviceService
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.ParameterSeries
import pe.soltelematic.mobile.domain.model.SendCommandOutcome
import pe.soltelematic.mobile.domain.model.ServiceCreateForm
import pe.soltelematic.mobile.domain.model.ServiceCreateRequest
import pe.soltelematic.mobile.domain.model.UnitStat
import pe.soltelematic.mobile.domain.repository.AssetDetailRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HISTORY_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Rango de "hoy": de medianoche a [now], en la zona del teléfono (igual que antes en
 * getTodayStats). Un solo [now] para ambos extremos, así el rango no se descuadra si el reloj cruza
 * la medianoche entre dos lecturas.
 */
internal fun todayRange(now: LocalDateTime = LocalDateTime.now()): Pair<LocalDateTime, LocalDateTime> =
    now.toLocalDate().atStartOfDay() to now

// La app solo envía comandos GPRS -- SMS no está implementado (ver AssetDetailRepository.getCommands).
private const val COMMANDS_CONNECTION_GPRS = "gprs"

class AssetDetailRepositoryImpl(
    private val api: AssetDetailApi,
    private val apiCallExecutor: ApiCallExecutor,
    private val iconUrlResolver: IconUrlResolver
) : AssetDetailRepository {

    override suspend fun getDetail(id: Int): ApiResult<AssetDetail> =
        when (val result = apiCallExecutor.execute { api.getDevice(id) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.data.toDomain(iconUrlResolver))
            is ApiResult.Error -> result
        }

    override suspend fun getTodayStats(id: Int): ApiResult<List<UnitStat>> {
        val (from, to) = todayRange()
        return when (
            val result = apiCallExecutor.execute {
                api.getHistory(
                    deviceId = id,
                    from = from.format(HISTORY_DATE_FORMAT),
                    to = to.format(HISTORY_DATE_FORMAT)
                )
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.data.stats.map { it.toDomain() })
            is ApiResult.Error -> result
        }
    }

    override suspend fun getRoute(id: Int, from: LocalDateTime, to: LocalDateTime): ApiResult<HistoryRoute> =
        when (
            val result = apiCallExecutor.execute {
                api.getHistory(
                    deviceId = id,
                    from = from.format(HISTORY_DATE_FORMAT),
                    to = to.format(HISTORY_DATE_FORMAT)
                )
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.data.toDomain())
            is ApiResult.Error -> result
        }

    override suspend fun getTodayRoute(id: Int): ApiResult<HistoryRoute> {
        val (from, to) = todayRange()
        return getRoute(id, from, to)
    }

    override suspend fun getParameters(id: Int, from: LocalDateTime, to: LocalDateTime): ApiResult<List<ParameterSeries>> =
        when (
            val result = apiCallExecutor.execute {
                api.getParameters(
                    deviceId = id,
                    from = from.format(HISTORY_DATE_FORMAT),
                    to = to.format(HISTORY_DATE_FORMAT)
                )
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Error -> result
        }

    override suspend fun getAddress(lat: Double, lng: Double): ApiResult<String?> =
        when (val result = apiCallExecutor.execute { api.getAddress(lat, lng) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.data.address)
            is ApiResult.Error -> result
        }

    override suspend fun getCommands(id: Int): ApiResult<List<DeviceCommand>> =
        when (
            val result = apiCallExecutor.execute {
                api.getCommands(connection = COMMANDS_CONNECTION_GPRS, deviceId = id)
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.data.data.map { it.toDomain() })
            is ApiResult.Error -> result
        }

    override suspend fun getServices(id: Int): ApiResult<List<DeviceService>> =
        when (val result = apiCallExecutor.execute { api.getServices(id) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.data.map { it.toDomain() })
            is ApiResult.Error -> result
        }

    override suspend fun getServiceCreateForm(id: Int): ApiResult<ServiceCreateForm> =
        when (val result = apiCallExecutor.execute { api.getServiceCreateForm(id) }) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Error -> result
        }

    override suspend fun createService(id: Int, request: ServiceCreateRequest): ApiResult<Unit> =
        when (val result = apiCallExecutor.execute { api.createService(id, request.toDto()) }) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> result
        }

    override suspend fun sendCommand(
        id: Int,
        type: String,
        attributes: Map<String, String>
    ): ApiResult<SendCommandOutcome> {
        // Los fijos van al final: si algún día un atributo del servidor se llamara igual que uno
        // de estos (no debería, son nombres reservados del endpoint), el fijo nunca se pisa.
        val params = attributes + mapOf(
            "connection" to COMMANDS_CONNECTION_GPRS,
            "device_id" to id.toString(),
            "type" to type
        )
        return when (val result = apiCallExecutor.execute { api.sendCommand(params) }) {
            is ApiResult.Success -> ApiResult.Success(
                if (result.data.status == 1) {
                    SendCommandOutcome.Success(result.data.message.orEmpty())
                } else {
                    SendCommandOutcome.Rejected(result.data.errors.orEmpty())
                }
            )
            is ApiResult.Error -> result
        }
    }
}

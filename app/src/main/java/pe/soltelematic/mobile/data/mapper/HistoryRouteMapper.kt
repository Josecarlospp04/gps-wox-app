package pe.soltelematic.mobile.data.mapper

import pe.soltelematic.mobile.data.remote.dto.HistoryCoordinatesDto
import pe.soltelematic.mobile.data.remote.dto.HistoryDataDto
import pe.soltelematic.mobile.data.remote.dto.HistoryItemDto
import pe.soltelematic.mobile.data.remote.dto.HistoryPointDto
import pe.soltelematic.mobile.data.remote.dto.HistoryPositionDto
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryLeg
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.RouteEvent
import pe.soltelematic.mobile.domain.model.classifyRouteEventTitle
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// start/end.time.formatted usa el mismo patrón que time.formatted en el resto de la app.
private val LEG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")

// positions[].t usa un patrón distinto (yyyy-MM-dd, no dd-MM-yyyy) -- confirmado contra el
// servidor real, no es un error de tipeo.
private val POSITION_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun HistoryDataDto.toDomain(): HistoryRoute = HistoryRoute(
    periodStats = stats.map { it.toDomain() },
    // status="event" (end/stats/positions en null) NO es un tramo: toLegOrNull() lo descarta y va a
    // events (ver toEventOrNull).
    legs = items.mapNotNull { it.toLegOrNull() },
    events = items.mapNotNull { it.toEventOrNull() }
)

private fun HistoryItemDto.toEventOrNull(): RouteEvent? {
    if (status != "event") return null
    val titleText = title.orEmpty()
    return RouteEvent(
        type = classifyRouteEventTitle(titleText),
        // start.time.timestamp es epoch UTC correcto (verificado: 1794632457 = 00:00:57 en Perú),
        // no se parsea formatted como en los tramos.
        time = start?.time?.timestamp?.let(Instant::ofEpochSecond),
        point = start?.coordinates.toGeoPointOrNull(),
        title = titleText
    )
}

private fun HistoryItemDto.toLegOrNull(): HistoryLeg? {
    val startEndpoint = start.toEndpoint()
    val endEndpoint = end.toEndpoint()
    val legStats = stats?.map { it.toDomain() } ?: emptyList()
    return when (status) {
        "stop" -> HistoryStopLeg(title = title, start = startEndpoint, end = endEndpoint, stats = legStats)
        "drive" -> HistoryDriveLeg(
            title = title,
            start = startEndpoint,
            end = endEndpoint,
            stats = legStats,
            positions = positions?.mapNotNull { it.toDomainOrNull() } ?: emptyList()
        )
        else -> null
    }
}

private fun HistoryPointDto?.toEndpoint(): HistoryEndpoint = HistoryEndpoint(
    point = this?.coordinates?.toGeoPointOrNull(),
    time = this?.time?.formatted?.toInstantOrNull(LEG_TIME_FORMAT)
)

private fun HistoryCoordinatesDto?.toGeoPointOrNull(): GeoPoint? {
    val latValue = this?.lat ?: return null
    val lngValue = this?.lng ?: return null
    return GeoPoint(latValue, lngValue)
}

private fun HistoryPositionDto.toDomainOrNull(): HistoryPosition? {
    val latValue = lat ?: return null
    val lngValue = lng ?: return null
    return HistoryPosition(
        point = GeoPoint(latValue, lngValue),
        time = t?.toInstantOrNull(POSITION_TIME_FORMAT),
        speedText = s,
        colorHex = c
    )
}

// Asume la misma zona horaria del servidor (Perú), igual que AssetDetailMapper.kt -- ver comentario
// homónimo ahí. runCatching por si el servidor cambia el patrón sin avisar.
private fun String.toInstantOrNull(pattern: DateTimeFormatter): Instant? =
    runCatching { LocalDateTime.parse(this, pattern).atZone(ZoneId.systemDefault()).toInstant() }
        .getOrNull()

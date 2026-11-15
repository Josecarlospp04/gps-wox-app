package pe.soltelematic.mobile.domain.model

import java.time.Instant

/**
 * Ruta de un rango de fechas (Sprint 2B), resultado de GET history. legs viene en el mismo orden
 * cronológico en que lo entrega el servidor -- alterna HistoryStopLeg/HistoryDriveLeg, y ya viene
 * separado de los eventos: los items status="event" (end/stats/positions en null) van a [events],
 * no a legs.
 */
data class HistoryRoute(
    val periodStats: List<UnitStat>,
    val legs: List<HistoryLeg>,
    // Eventos de la plataforma del mismo rango (status="event" de GET history), en el orden del
    // servidor. Todos; el mapa solo dibuja los de tipo reconocido (ver RouteEventType).
    val events: List<RouteEvent> = emptyList()
)

data class HistoryEndpoint(
    val point: GeoPoint?,
    val time: Instant?
)

sealed interface HistoryLeg {
    val title: String?
    val start: HistoryEndpoint
    val end: HistoryEndpoint
    val stats: List<UnitStat>
}

/**
 * Parada: se muestra siempre en la línea de tiempo, sin filtrar por duración. El marcador en el
 * mapa va en start.point -- la unidad no se mueve durante una parada, start y end son casi el
 * mismo punto (ver nota en HistoryRouteMapper.kt).
 */
data class HistoryStopLeg(
    override val title: String?,
    override val start: HistoryEndpoint,
    override val end: HistoryEndpoint,
    override val stats: List<UnitStat>
) : HistoryLeg

/** Viaje: positions arma el polyline en el mapa, con color por tramo según HistoryPosition.colorHex. */
data class HistoryDriveLeg(
    override val title: String?,
    override val start: HistoryEndpoint,
    override val end: HistoryEndpoint,
    override val stats: List<UnitStat>,
    val positions: List<HistoryPosition>
) : HistoryLeg

data class HistoryPosition(
    val point: GeoPoint,
    val time: Instant?,
    val speedText: String?,
    val colorHex: String?
)

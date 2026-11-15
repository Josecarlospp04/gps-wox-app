package pe.soltelematic.mobile.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.RouteEvent
import pe.soltelematic.mobile.domain.model.RouteEventType
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import java.time.Instant

private val T = Instant.parse("2026-11-14T13:00:00Z")
private val A = GeoPoint(-6.0, -76.0)
private val B = GeoPoint(-6.001, -76.0)

private fun driveLeg() = HistoryDriveLeg(
    null, HistoryEndpoint(A, T), HistoryEndpoint(B, T), emptyList(),
    listOf(HistoryPosition(A, T, "10", null), HistoryPosition(B, T, "20", null))
)

private fun stopLeg(point: GeoPoint) = HistoryStopLeg(null, HistoryEndpoint(point, T), HistoryEndpoint(point, T), emptyList())

class RouteEventMarkersTest {

    @Test
    fun `solo se dibujan los eventos de ignicion, cada uno con su indice en events`() {
        val events = listOf(
            RouteEvent(RouteEventType.OTHER, T, A, "Exceso de velocidad (5 kph)"),
            RouteEvent(RouteEventType.IGNITION_ON, T, A, "Encendido conectado"),
            RouteEvent(RouteEventType.OTHER, T, B, "Rellenos de combustible"),
            RouteEvent(RouteEventType.IGNITION_OFF, T, B, "Ignición apagada")
        )
        val data = HistoryRoute(emptyList(), listOf(driveLeg()), events).toRouteMapData()

        val eventMarkers = data.markers.filter { it.eventIndex != null }

        assertEquals(listOf(RouteMarkerRole.IGNITION_ON, RouteMarkerRole.IGNITION_OFF), eventMarkers.map { it.role })
        assertEquals(listOf(1, 3), eventMarkers.map { it.eventIndex })
        assertEquals(listOf(A, B), eventMarkers.map { it.position })
        eventMarkers.forEach { assertNull(it.legIndex) }
    }

    @Test
    fun `un evento sin coordenadas no se dibuja`() {
        val events = listOf(RouteEvent(RouteEventType.IGNITION_ON, T, null, "Encendido conectado"))

        val data = HistoryRoute(emptyList(), listOf(driveLeg()), events).toRouteMapData()

        assertEquals(emptyList<Any>(), data.markers.filter { it.eventIndex != null })
    }

    @Test
    fun `los eventos van despues de paradas y partida y llegada para dibujarse por encima`() {
        val events = listOf(RouteEvent(RouteEventType.IGNITION_OFF, T, A, "Ignición apagada"))
        val data = HistoryRoute(emptyList(), listOf(stopLeg(A), driveLeg(), stopLeg(B)), events).toRouteMapData()

        assertEquals(RouteMarkerRole.IGNITION_OFF, data.markers.last().role)
        assertEquals(setOf(RouteMarkerRole.STOP), data.markers.dropLast(1).map { it.role }.toSet())
    }

    @Test
    fun `los eventos no cambian los marcadores de tramos ni la partida y llegada`() {
        val legs = listOf(driveLeg())
        val without = HistoryRoute(emptyList(), legs).toRouteMapData()
        val with = HistoryRoute(emptyList(), legs, listOf(RouteEvent(RouteEventType.IGNITION_ON, T, A, "Encendido conectado"))).toRouteMapData()

        assertEquals(without.markers, with.markers.filter { it.eventIndex == null })
        assertEquals(without.polylines, with.polylines)
        assertEquals(listOf(RouteMarkerRole.ROUTE_START, RouteMarkerRole.ROUTE_END), without.markers.map { it.role })
    }
}

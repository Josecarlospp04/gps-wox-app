package pe.soltelematic.mobile.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.core.format.formatDurationCompact
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryLeg
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.RouteEvent
import pe.soltelematic.mobile.domain.model.RouteEventType
import pe.soltelematic.mobile.domain.model.UnitStat
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerData
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerKey
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerTarget
import pe.soltelematic.mobile.ui.map.engine.isEvent
import pe.soltelematic.mobile.ui.map.engine.target
import java.time.Instant
import java.time.ZoneId

private val LIMA = ZoneId.of("America/Lima")
private val T_STOP_START = Instant.parse("2026-11-14T05:06:46Z") // 00:06:46 Lima
private val T_STOP_END = Instant.parse("2026-11-14T13:08:28Z") // 08:08:28 Lima
private val P1 = GeoPoint(-11.95, -76.98)
private val P2 = GeoPoint(-12.04, -77.04)

private fun stop(start: Instant?, end: Instant?, point: GeoPoint = P1, duration: String? = null) = HistoryStopLeg(
    title = null,
    start = HistoryEndpoint(point, start),
    end = HistoryEndpoint(point, end),
    stats = listOfNotNull(duration?.let { UnitStat("duration", "Duración", it) })
)

private fun drive(start: Instant?, end: Instant?) = HistoryDriveLeg(
    title = null,
    start = HistoryEndpoint(P1, start),
    end = HistoryEndpoint(P2, end),
    stats = emptyList(),
    positions = emptyList()
)

private fun route(legs: List<HistoryLeg>, events: List<RouteEvent> = emptyList()) = HistoryRoute(emptyList(), legs, events)

class RouteInfoCardTest {

    // --- contenido por tipo de icono

    @Test
    fun `evento de ignicion lleva su hora y su punto`() {
        val on = RouteEvent(RouteEventType.IGNITION_ON, T_STOP_END, P2, "Encendido conectado")
        val off = RouteEvent(RouteEventType.IGNITION_OFF, T_STOP_START, P1, "Ignición apagada")
        val r = route(emptyList(), listOf(on, off))

        val onCard = r.infoCardFor(RouteMarkerKey(RouteMarkerRole.IGNITION_ON, null, 0))!!
        val offCard = r.infoCardFor(RouteMarkerKey(RouteMarkerRole.IGNITION_OFF, null, 1))!!

        assertEquals(InfoCardData(InfoCardKind.IGNITION_ON, T_STOP_END, null, null, P2), onCard)
        assertEquals(InfoCardKind.IGNITION_OFF, offCard.kind)
        assertEquals(P1, offCard.point)
    }

    @Test
    fun `parada lleva inicio, fin, duracion del servidor y su lugar`() {
        val r = route(listOf(stop(T_STOP_START, T_STOP_END, duration = "08:01:42")))

        val card = r.infoCardFor(RouteMarkerKey(RouteMarkerRole.STOP, 0, null))!!

        assertEquals(InfoCardKind.STOP, card.kind)
        assertEquals(T_STOP_START, card.start)
        assertEquals(T_STOP_END, card.end)
        assertEquals("08:01:42", card.durationRaw)
        assertEquals(P1, card.point)
    }

    @Test
    fun `sin duracion del servidor la parada la calcula entre inicio y fin`() {
        val r = route(listOf(stop(T_STOP_START, T_STOP_END)))

        assertEquals("08:01:42", r.infoCardFor(RouteMarkerKey(RouteMarkerRole.STOP, 0, null))!!.durationRaw)
    }

    @Test
    fun `partida de un viaje es su inicio y llegada es su fin`() {
        val r = route(listOf(drive(T_STOP_START, T_STOP_END)))

        val start = r.infoCardFor(RouteMarkerKey(RouteMarkerRole.ROUTE_START, 0, null))!!
        val end = r.infoCardFor(RouteMarkerKey(RouteMarkerRole.ROUTE_END, 0, null))!!

        assertEquals(InfoCardData(InfoCardKind.ROUTE_START, T_STOP_START, null, null, P1), start)
        assertEquals(InfoCardData(InfoCardKind.ROUTE_END, T_STOP_END, null, null, P2), end)
    }

    @Test
    fun `en ida y vuelta partida es la salida de la primera parada y llegada la entrada de la ultima`() {
        val r = route(listOf(stop(T_STOP_START, T_STOP_END), stop(T_STOP_END, null)))

        assertEquals(T_STOP_END, r.infoCardFor(RouteMarkerKey(RouteMarkerRole.ROUTE_START, 0, null))!!.start)
        assertEquals(T_STOP_END, r.infoCardFor(RouteMarkerKey(RouteMarkerRole.ROUTE_END, 1, null))!!.start)
    }

    @Test
    fun `un icono que ya no existe no tiene tarjeta`() {
        val r = route(listOf(stop(T_STOP_START, T_STOP_END)))

        assertNull(r.infoCardFor(RouteMarkerKey(RouteMarkerRole.STOP, 5, null)))
        assertNull(r.infoCardFor(RouteMarkerKey(RouteMarkerRole.IGNITION_ON, null, 3)))
        assertNull(r.infoCardFor(RouteMarkerKey(RouteMarkerRole.IGNITION_ON, null, null)))
        // una parada que apunta a un viaje:
        assertNull(route(listOf(drive(T_STOP_START, T_STOP_END))).infoCardFor(RouteMarkerKey(RouteMarkerRole.STOP, 0, null)))
    }

    // --- formato de fecha, hora y duracion

    @Test
    fun `fecha y hora en la zona dada`() {
        assertEquals("14/11/2026", formatCardDate(T_STOP_START, LIMA))
        assertEquals("00:06:46", formatCardTime(T_STOP_START, LIMA))
        assertEquals("08:08:28", formatCardTime(T_STOP_END, LIMA))
        assertEquals("-", formatCardDate(null, LIMA))
        assertEquals("-", formatCardTime(null, LIMA))
    }

    @Test
    fun `el fin de una parada que cruza la medianoche lleva su fecha`() {
        val nextDay = Instant.parse("2026-11-15T14:00:00Z") // 09:00 del 15/11 en Lima

        assertEquals("08:08:28", formatCardEndTime(T_STOP_START, T_STOP_END, LIMA))
        assertEquals("15/11 09:00:00", formatCardEndTime(T_STOP_START, nextDay, LIMA))
        assertEquals("-", formatCardEndTime(T_STOP_START, null, LIMA))
    }

    @Test
    fun `duracion entre dos instantes en HH MM SS`() {
        assertEquals("08:01:42", durationRawBetween(T_STOP_START, T_STOP_END))
        assertEquals("00:00:00", durationRawBetween(T_STOP_START, T_STOP_START))
        assertEquals("26:00:00", durationRawBetween(T_STOP_START, T_STOP_START.plusSeconds(26 * 3600)))
        assertNull(durationRawBetween(T_STOP_END, T_STOP_START))
        assertNull(durationRawBetween(null, T_STOP_END))
    }

    @Test
    fun `la duracion de la tarjeta se muestra compacta, en horas y minutos`() {
        assertEquals("8h 02", formatDurationCompact("08:01:42"))
        assertEquals("<1 min", formatDurationCompact("00:00:40"))
    }

    @Test
    fun `las coordenadas de respaldo llevan seis decimales con punto`() {
        assertEquals("-11.952758, -76.987653", formatCoordinates(GeoPoint(-11.952758, -76.987653)))
    }

    // --- enrutado de toques por tipo de marcador

    @Test
    fun `tocar una parada o partida o llegada lleva a su tramo`() {
        assertEquals(RouteMarkerTarget.Leg(3), RouteMarkerKey(RouteMarkerRole.STOP, 3, null).target())
        assertEquals(RouteMarkerTarget.Leg(0), RouteMarkerKey(RouteMarkerRole.ROUTE_START, 0, null).target())
        assertEquals(RouteMarkerTarget.Leg(7), RouteMarkerKey(RouteMarkerRole.ROUTE_END, 7, null).target())
    }

    @Test
    fun `tocar un evento lleva a su evento y no a un tramo`() {
        assertEquals(RouteMarkerTarget.Event(2), RouteMarkerKey(RouteMarkerRole.IGNITION_ON, null, 2).target())
        assertEquals(RouteMarkerTarget.Event(0), RouteMarkerKey(RouteMarkerRole.IGNITION_OFF, null, 0).target())
    }

    @Test
    fun `una clave incompleta no enruta a nada`() {
        assertNull(RouteMarkerKey(RouteMarkerRole.STOP, null, null).target())
        assertNull(RouteMarkerKey(RouteMarkerRole.IGNITION_ON, null, null).target())
        // un evento con legIndex pero sin eventIndex tampoco cae por error en un tramo
        assertNull(RouteMarkerKey(RouteMarkerRole.IGNITION_ON, 4, null).target())
    }

    @Test
    fun `solo los dos roles de ignicion son eventos`() {
        assertTrue(RouteMarkerRole.IGNITION_ON.isEvent)
        assertTrue(RouteMarkerRole.IGNITION_OFF.isEvent)
        assertFalse(RouteMarkerRole.STOP.isEvent)
        assertFalse(RouteMarkerRole.ROUTE_START.isEvent)
        assertFalse(RouteMarkerRole.ROUTE_END.isEvent)
    }

    @Test
    fun `la clave del marcador sale de sus campos`() {
        val marker = RouteMarkerData(legIndex = null, position = P1, role = RouteMarkerRole.IGNITION_OFF, eventIndex = 4)

        assertEquals(RouteMarkerKey(RouteMarkerRole.IGNITION_OFF, null, 4), marker.key)
        assertEquals(RouteMarkerTarget.Event(4), marker.key.target())
    }
}

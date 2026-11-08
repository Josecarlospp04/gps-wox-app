package pe.soltelematic.mobile.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.ui.history.AddressResolution
import pe.soltelematic.mobile.ui.history.toRouteMapData
import java.time.Instant
import java.time.LocalDate

private val T0 = Instant.parse("2026-11-08T14:00:00Z")
private fun t(minutes: Long) = T0.plusSeconds(minutes * 60)

// ~0.0001 grados de latitud = ~11 m; 0.00005 = ~5.5 m.
private const val STEP_11M = 0.0001
private const val STEP_5M = 0.00005

private val ORIGIN = GeoPoint(-6.0, -76.0)
private fun north(base: GeoPoint, deg: Double) = GeoPoint(base.lat + deg, base.lng)

private fun stop(point: GeoPoint, start: Long, end: Long) = HistoryStopLeg(
    title = null,
    start = HistoryEndpoint(point, t(start)),
    end = HistoryEndpoint(point, t(end)),
    stats = emptyList()
)

private fun drive(points: List<GeoPoint>, startMin: Long) = HistoryDriveLeg(
    title = null,
    start = HistoryEndpoint(points.first(), t(startMin)),
    end = HistoryEndpoint(points.last(), t(startMin + points.size - 1)),
    stats = emptyList(),
    positions = points.mapIndexed { i, p -> HistoryPosition(p, t(startMin + i), null, null) }
)

// parada 0-10 min, trayecto 10-12 min (3 posiciones, termina en `driveEnd`)
private val DRIVE_POINTS = listOf(ORIGIN, north(ORIGIN, 0.001), north(ORIGIN, 0.002))
private val DRIVE_END = DRIVE_POINTS.last()

private fun route(vararg legs: pe.soltelematic.mobile.domain.model.HistoryLeg) =
    HistoryRoute(periodStats = emptyList(), legs = legs.toList())

private fun baseRoute() = route(stop(ORIGIN, 0, 10), drive(DRIVE_POINTS, 10))

private fun ready(route: HistoryRoute = baseRoute()) = TodayTrackState(
    assetId = 1,
    status = TodayTrackStatus.READY,
    route = route,
    mapData = route.toRouteMapData(),
    dayDate = LocalDate.of(2026, 11, 8)
)

class TodayTrackLiveTest {

    // --- Umbral de 10 m ---

    @Test
    fun `un punto a mas de 10 m del final del recorrido se anade`() {
        val track = ready().withLivePoint(north(DRIVE_END, STEP_11M), t(13))

        assertEquals(1, track.provisional.size)
    }

    @Test
    fun `un punto a menos de 10 m no se anade`() {
        val track = ready().withLivePoint(north(DRIVE_END, STEP_5M), t(13))

        assertTrue(track.provisional.isEmpty())
    }

    @Test
    fun `el umbral se mide contra el ultimo provisional, no contra el final del recorrido`() {
        val first = north(DRIVE_END, STEP_11M)
        val track = ready()
            .withLivePoint(first, t(13))
            .withLivePoint(north(first, STEP_5M), t(14)) // 5 m del ultimo provisional: ruido
            .withLivePoint(north(first, STEP_11M), t(15))

        assertEquals(2, track.provisional.size)
        assertEquals(t(15), track.provisional.last().time)
    }

    @Test
    fun `un punto con hora anterior o igual al ultimo provisional se ignora`() {
        val first = north(DRIVE_END, STEP_11M)
        val track = ready().withLivePoint(first, t(14))

        assertEquals(1, track.withLivePoint(north(first, STEP_11M), t(14)).provisional.size)
        assertEquals(1, track.withLivePoint(north(first, STEP_11M), t(13)).provisional.size)
    }

    @Test
    fun `un punto ya cubierto por el recorrido no se anade`() {
        // El trayecto cubre hasta t(12): un punto de t(12) o anterior ya esta en la respuesta.
        assertTrue(ready().withLivePoint(north(DRIVE_END, STEP_11M), t(12)).provisional.isEmpty())
        assertTrue(ready().withLivePoint(north(DRIVE_END, STEP_11M), t(5)).provisional.isEmpty())
    }

    @Test
    fun `sin recorrido listo no se anaden puntos`() {
        val loading = TodayTrackState(assetId = 1, status = TodayTrackStatus.LOADING)
        val empty = TodayTrackState(assetId = 1, status = TodayTrackStatus.EMPTY)

        assertTrue(loading.withLivePoint(ORIGIN, t(1)).provisional.isEmpty())
        assertTrue(empty.withLivePoint(ORIGIN, t(1)).provisional.isEmpty())
    }

    @Test
    fun `si el recorrido termina en una parada el ancla es el punto de la parada`() {
        val parked = ready(route(drive(DRIVE_POINTS, 0), stop(DRIVE_END, 2, 30)))

        // La parada cubre hasta t(30): un punto posterior a eso a 11 m se anade; uno cercano no.
        assertEquals(1, parked.withLivePoint(north(DRIVE_END, STEP_11M), t(31)).provisional.size)
        assertTrue(parked.withLivePoint(north(DRIVE_END, STEP_5M), t(31)).provisional.isEmpty())
    }

    // --- Polilinea provisional ---

    @Test
    fun `la polilinea provisional arranca en el final del recorrido`() {
        val p1 = north(DRIVE_END, STEP_11M)
        val p2 = north(p1, STEP_11M)
        val path = ready().withLivePoint(p1, t(13)).withLivePoint(p2, t(14)).provisionalPath()

        assertEquals(listOf(DRIVE_END, p1, p2), path)
    }

    @Test
    fun `sin provisionales no hay polilinea provisional`() {
        assertTrue(ready().provisionalPath().isEmpty())
    }

    // --- Refresco completo: descarte de duplicados ---

    @Test
    fun `el refresco descarta los provisionales que la respuesta ya cubre`() {
        val p1 = north(DRIVE_END, STEP_11M)
        val p2 = north(p1, STEP_11M)
        val p3 = north(p2, STEP_11M)
        val live = ready().withLivePoint(p1, t(13)).withLivePoint(p2, t(14)).withLivePoint(p3, t(15))

        // El servidor ya trae hasta t(14): p1 y p2 salen, p3 (t15) se queda.
        val extended = route(stop(ORIGIN, 0, 10), drive(DRIVE_POINTS + listOf(p1, p2), 10))
        val refreshed = live.withRefreshedRoute(extended, extended.toRouteMapData())

        assertEquals(listOf(p3), refreshed.provisional.map { it.point })
        assertEquals(listOf(p2, p3), refreshed.provisionalPath().drop(0).takeLast(2))
    }

    @Test
    fun `el refresco que cubre todo deja sin provisionales`() {
        val p1 = north(DRIVE_END, STEP_11M)
        val live = ready().withLivePoint(p1, t(13))

        val extended = route(stop(ORIGIN, 0, 10), drive(DRIVE_POINTS + p1, 10), stop(p1, 13, 20))
        val refreshed = live.withRefreshedRoute(extended, extended.toRouteMapData())

        assertTrue(refreshed.provisional.isEmpty())
        assertTrue(refreshed.provisionalPath().isEmpty())
    }

    // --- Refresco completo: no toca camara ni panel ---

    @Test
    fun `el refresco no cambia ids de camara ni el panel`() {
        val before = ready().copy(frameRequestId = 3, centerRequestId = 2, legFocusRequestId = 4, panelExpanded = true)
        val extended = route(stop(ORIGIN, 0, 10), drive(DRIVE_POINTS, 10), stop(DRIVE_END, 12, 40))

        val after = before.withRefreshedRoute(extended, extended.toRouteMapData())

        assertEquals(3, after.frameRequestId)
        assertEquals(2, after.centerRequestId)
        assertEquals(4, after.legFocusRequestId)
        assertTrue(after.panelExpanded)
        assertEquals(3, after.route?.legs?.size)
    }

    @Test
    fun `el refresco conserva el tramo seleccionado si sigue existiendo`() {
        // Tramo 1 (trayecto) seleccionado; ahora el dia termina con una parada nueva: sigue igual.
        val before = ready().withLegSelected(1)
        val extended = route(stop(ORIGIN, 0, 10), drive(DRIVE_POINTS, 10), stop(DRIVE_END, 12, 40))

        assertEquals(1, before.withRefreshedRoute(extended, extended.toRouteMapData()).selectedLegIndex)
    }

    @Test
    fun `el refresco conserva la seleccion aunque el tramo en curso haya crecido`() {
        // Parada en curso (indice 0) que ahora termina mas tarde: mismo inicio, mismo tramo.
        val before = ready(route(stop(ORIGIN, 0, 10))).withLegSelected(0)
        val grown = route(stop(ORIGIN, 0, 25))

        assertEquals(0, before.withRefreshedRoute(grown, grown.toRouteMapData()).selectedLegIndex)
    }

    @Test
    fun `el refresco quita el resaltado si el tramo desaparecio`() {
        val before = ready().withLegSelected(1)
        val shorter = route(stop(ORIGIN, 0, 10))

        assertNull(before.withRefreshedRoute(shorter, shorter.toRouteMapData()).selectedLegIndex)
    }

    @Test
    fun `el refresco quita el resaltado si el indice ahora es otro tramo`() {
        val before = ready().withLegSelected(1)
        // El indice 1 ahora es una parada distinta (otro tipo y otro inicio).
        val other = route(stop(ORIGIN, 0, 10), stop(north(ORIGIN, 0.01), 50, 60))

        assertNull(before.withRefreshedRoute(other, other.toRouteMapData()).selectedLegIndex)
    }

    @Test
    fun `el refresco conserva direcciones solo de las paradas que siguen`() {
        val before = ready().copy(
            addresses = mapOf(0 to AddressResolution.Resolved("Calle 1"), 1 to AddressResolution.Resolved("X"))
        )
        val extended = route(stop(ORIGIN, 0, 10), drive(DRIVE_POINTS, 10))

        assertEquals(setOf(0, 1), before.withRefreshedRoute(extended, extended.toRouteMapData()).addresses.keys)

        val shorter = route(stop(ORIGIN, 0, 10))
        assertEquals(setOf(0), before.withRefreshedRoute(shorter, shorter.toRouteMapData()).addresses.keys)
    }

    @Test
    fun `si el dibujo no cambia se conserva la misma instancia de mapData`() {
        val before = ready()
        // Solo cambia el fin de la parada final (no el dibujo del mapa).
        val grown = route(stop(ORIGIN, 0, 99))
        val first = before.copy(route = grown, mapData = grown.toRouteMapData())
        val grownMore = route(stop(ORIGIN, 0, 120))

        val after = first.withRefreshedRoute(grownMore, grownMore.toRouteMapData())

        assertSame(first.mapData, after.mapData)
    }

    @Test
    fun `el refresco no pisa una carga inicial ni un error en curso`() {
        val newer = baseRoute()
        listOf(TodayTrackStatus.LOADING, TodayTrackStatus.ERROR).forEach { status ->
            val track = TodayTrackState(assetId = 1, status = status)
            assertSame(track, track.withRefreshedRoute(newer, newer.toRouteMapData()))
        }
    }

    @Test
    fun `un refresco vacio pasa a EMPTY, pliega el panel y limpia provisionales`() {
        val live = ready().copy(panelExpanded = true).withLivePoint(north(DRIVE_END, STEP_11M), t(13))
        val empty = HistoryRoute(emptyList(), emptyList())

        val after = live.withRefreshedRoute(empty, empty.toRouteMapData())

        assertEquals(TodayTrackStatus.EMPTY, after.status)
        assertFalse(after.panelExpanded)
        assertTrue(after.provisional.isEmpty())
    }

    // --- Cambio de dia ---

    @Test
    fun `se detecta el cruce de medianoche`() {
        val track = ready()

        assertFalse(track.isStaleDay(LocalDate.of(2026, 11, 8)))
        assertTrue(track.isStaleDay(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `el nuevo dia empieza cargando, sin recorrido ni provisionales, para la misma unidad`() {
        val live = ready().copy(panelExpanded = true, frameRequestId = 5, selectedLegIndex = 1)
            .withLivePoint(north(DRIVE_END, STEP_11M), t(13))

        val next = live.forNewDay(LocalDate.of(2026, 11, 9))

        assertEquals(1, next.assetId)
        assertEquals(LocalDate.of(2026, 11, 9), next.dayDate)
        assertEquals(TodayTrackStatus.LOADING, next.status)
        assertNull(next.route)
        assertNull(next.selectedLegIndex)
        assertFalse(next.panelExpanded)
        assertTrue(next.provisional.isEmpty())
        assertEquals(5, next.frameRequestId)
        assertFalse(next.isStaleDay(LocalDate.of(2026, 11, 9)))
    }
}

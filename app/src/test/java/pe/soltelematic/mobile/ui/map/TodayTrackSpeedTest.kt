package pe.soltelematic.mobile.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.ui.history.toRouteMapData
import java.time.Instant

private val START = Instant.parse("2026-11-14T15:00:00Z")
private val A = GeoPoint(-6.0, -76.0)
private val B = GeoPoint(-6.001, -76.0)

private fun routeWithSpeeds(): HistoryRoute {
    val positions = listOf(
        HistoryPosition(A, START, "12", "#0000FF"),
        HistoryPosition(B, START.plusSeconds(60), "70", "#0000FF")
    )
    val leg = HistoryDriveLeg(null, HistoryEndpoint(A, START), HistoryEndpoint(B, START.plusSeconds(60)), emptyList(), positions)
    return HistoryRoute(emptyList(), listOf(leg))
}

private fun live(): TodayTrackState {
    val route = routeWithSpeeds()
    return TodayTrackState(assetId = 1, status = TodayTrackStatus.READY, route = route, mapData = route.toRouteMapData())
}

class TodayTrackSpeedTest {

    @Test
    fun `el mapper toma la velocidad del texto de la posicion e ignora el color del servidor`() {
        val points = routeWithSpeeds().toRouteMapData().polylines.single().points

        assertEquals(listOf(12.0, 70.0), points.map { it.speedKph })
    }

    @Test
    fun `un punto en vivo conserva su velocidad`() {
        val next = GeoPoint(-6.002, -76.0)

        val track = live().withLivePoint(next, START.plusSeconds(120), speedKph = 45.0)

        assertEquals(45.0, track.provisional.single().speedKph!!, 0.0)
    }

    @Test
    fun `la ruta provisional lleva el ancla sin velocidad y cada punto con la suya`() {
        val p1 = GeoPoint(-6.002, -76.0)
        val p2 = GeoPoint(-6.003, -76.0)
        val track = live()
            .withLivePoint(p1, START.plusSeconds(120), speedKph = 30.0)
            .withLivePoint(p2, START.plusSeconds(180), speedKph = null)

        val path = track.provisionalRoutePath()

        assertEquals(listOf(B, p1, p2), path.map { it.point })
        assertNull(path[0].speedKph)
        assertEquals(30.0, path[1].speedKph!!, 0.0)
        assertNull(path[2].speedKph)
    }

    @Test
    fun `sin puntos en vivo no hay ruta provisional`() {
        assertTrue(live().provisionalRoutePath().isEmpty())
    }
}

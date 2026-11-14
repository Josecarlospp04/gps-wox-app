package pe.soltelematic.mobile.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryLeg
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.ui.history.RouteMapData
import pe.soltelematic.mobile.ui.history.toRouteMapData
import java.time.Instant
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 11, 14)
private val YESTERDAY = TODAY.minusDays(1)
private val POINT = GeoPoint(-6.0, -76.0)
private val ENDPOINT = HistoryEndpoint(POINT, Instant.parse("2026-11-14T15:00:00Z"))

private fun stopLeg() = HistoryStopLeg(null, ENDPOINT, ENDPOINT, emptyList())
private fun driveLeg() = HistoryDriveLeg(
    null, ENDPOINT, ENDPOINT, emptyList(),
    positions = listOf(
        HistoryPosition(POINT, ENDPOINT.time, null, null),
        HistoryPosition(GeoPoint(-6.001, -76.0), ENDPOINT.time, null, null)
    )
)

private fun route(vararg legs: HistoryLeg) = HistoryRoute(emptyList(), legs.toList())

private fun showingYesterday(): TodayTrackState {
    val route = route(stopLeg(), driveLeg())
    return TodayTrackState(
        assetId = 1,
        status = TodayTrackStatus.READY,
        route = route,
        mapData = route.toRouteMapData(),
        dayDate = YESTERDAY,
        dayComplete = true,
        selectedLegIndex = 1,
        panelExpanded = true
    )
}

class TodayTrackDayTest {

    @Test
    fun `el dia se nombra Hoy, Ayer o fecha`() {
        assertEquals(TrackDayKind.TODAY, trackDayKind(TODAY, TODAY))
        assertEquals(TrackDayKind.YESTERDAY, trackDayKind(YESTERDAY, TODAY))
        assertEquals(TrackDayKind.DATE, trackDayKind(TODAY.minusDays(2), TODAY))
        assertEquals(TrackDayKind.DATE, trackDayKind(TODAY.minusDays(6), TODAY))
    }

    @Test
    fun `un dia pasado ya completo no caduca al pasar el tiempo`() {
        val track = showingYesterday()

        assertFalse(track.isStaleDay(TODAY))
        assertFalse(track.isStaleDay(TODAY.plusDays(3)))
    }

    @Test
    fun `hoy cargado como hoy caduca al cruzar la medianoche`() {
        val loadedToday = showingYesterday().copy(dayDate = TODAY, dayComplete = false)

        assertFalse(loadedToday.isStaleDay(TODAY))
        assertTrue(loadedToday.isStaleDay(TODAY.plusDays(1)))
    }

    @Test
    fun `si la unidad empieza a moverse pasa de ayer a hoy`() {
        val todayRoute = route(stopLeg(), driveLeg())
        val before = showingYesterday()

        val after = before.switchedToToday(todayRoute, todayRoute.toRouteMapData(), TODAY)!!

        assertEquals(TODAY, after.dayDate)
        assertFalse(after.dayComplete)
        assertEquals(TodayTrackStatus.READY, after.status)
        assertEquals(todayRoute, after.route)
        assertEquals(before.assetId, after.assetId)
        assertNull(after.selectedLegIndex)
        assertFalse(after.panelExpanded)
        assertEquals(before.frameRequestId + 1, after.frameRequestId)
        assertFalse(after.isStaleDay(TODAY))
    }

    @Test
    fun `no pasa a hoy si hoy aun no tiene viaje ni si ya se muestra hoy`() {
        val onlyStops = route(stopLeg())
        val map = onlyStops.toRouteMapData()

        assertNull(showingYesterday().switchedToToday(onlyStops, map, TODAY))
        assertNull(showingYesterday().switchedToToday(route(), RouteMapData(emptyList(), emptyList()), TODAY))
        assertNull(showingYesterday().copy(dayDate = TODAY, dayComplete = false).switchedToToday(route(driveLeg()), map, TODAY))
    }

    @Test
    fun `no pasa a hoy mientras carga o con error`() {
        val withDrive = route(driveLeg())
        val map = withDrive.toRouteMapData()

        assertNull(showingYesterday().copy(status = TodayTrackStatus.LOADING).switchedToToday(withDrive, map, TODAY))
        assertNull(showingYesterday().copy(status = TodayTrackStatus.ERROR).switchedToToday(withDrive, map, TODAY))
    }

    @Test
    fun `un refresco de hoy con solo paradas deja el modo sin recorrido y sin dibujo`() {
        val onlyStops = route(stopLeg())
        val live = showingYesterday().copy(dayDate = TODAY, dayComplete = false)

        val refreshed = live.withRefreshedRoute(onlyStops, onlyStops.toRouteMapData())

        assertEquals(TodayTrackStatus.EMPTY, refreshed.status)
        assertNull(refreshed.route)
        assertNull(refreshed.mapData)
    }
}

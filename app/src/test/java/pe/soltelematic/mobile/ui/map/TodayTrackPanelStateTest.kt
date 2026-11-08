package pe.soltelematic.mobile.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.ui.history.toRouteMapData

private val STOP_POINT = GeoPoint(-6.0, -76.0)
private val DRIVE_POINTS = listOf(GeoPoint(-6.1, -76.1), GeoPoint(-6.2, -76.2), GeoPoint(-6.3, -76.3))

private fun endpoint(point: GeoPoint?) = HistoryEndpoint(point = point, time = null)

// Día de prueba: parada (0) -> trayecto (1) -> parada (2).
private fun route() = HistoryRoute(
    periodStats = emptyList(),
    legs = listOf(
        HistoryStopLeg(title = null, start = endpoint(STOP_POINT), end = endpoint(STOP_POINT), stats = emptyList()),
        HistoryDriveLeg(
            title = null,
            start = endpoint(DRIVE_POINTS.first()),
            end = endpoint(DRIVE_POINTS.last()),
            stats = emptyList(),
            positions = DRIVE_POINTS.map { HistoryPosition(point = it, time = null, speedText = null, colorHex = null) }
        ),
        HistoryStopLeg(title = null, start = endpoint(DRIVE_POINTS.last()), end = endpoint(DRIVE_POINTS.last()), stats = emptyList())
    )
)

private fun readyTrack(expanded: Boolean = false): TodayTrackState {
    val route = route()
    return TodayTrackState(
        assetId = 1,
        status = TodayTrackStatus.READY,
        route = route,
        mapData = route.toRouteMapData(),
        panelExpanded = expanded
    )
}

class TodayTrackPanelStateTest {

    // --- Plegar / desplegar ---

    @Test
    fun `con recorrido el panel se despliega y se pliega`() {
        val expanded = readyTrack().withPanelExpanded(true)
        assertTrue(expanded.panelExpanded)
        assertFalse(expanded.withPanelExpanded(false).panelExpanded)
    }

    @Test
    fun `sin recorrido el panel no se puede desplegar`() {
        listOf(TodayTrackStatus.LOADING, TodayTrackStatus.EMPTY, TodayTrackStatus.ERROR).forEach { status ->
            val track = TodayTrackState(assetId = 1, status = status).withPanelExpanded(true)
            assertFalse("status=$status", track.panelExpanded)
        }
    }

    // --- Seleccion de tramo ---

    @Test
    fun `tocar un tramo lo selecciona, pliega el panel y pide encuadrar una vez`() {
        val track = readyTrack(expanded = true).withLegSelected(1)

        assertEquals(1, track.selectedLegIndex)
        assertFalse(track.panelExpanded)
        assertEquals(1, track.legFocusRequestId)
    }

    @Test
    fun `tocar otra vez el mismo tramo vuelve a pedir encuadre`() {
        val track = readyTrack(expanded = true).withLegSelected(1).withPanelExpanded(true).withLegSelected(1)

        assertEquals(2, track.legFocusRequestId)
    }

    @Test
    fun `un indice fuera de rango no cambia nada`() {
        val track = readyTrack(expanded = true)

        assertEquals(track, track.withLegSelected(7))
        assertEquals(track, track.withLegSelected(-1))
    }

    @Test
    fun `un tramo de parada se encuadra en su punto`() {
        val points = readyTrack().withLegSelected(0).selectedLegFramePoints()

        assertEquals(listOf(STOP_POINT), points)
    }

    @Test
    fun `un trayecto se encuadra con todo su trazado`() {
        val points = readyTrack().withLegSelected(1).selectedLegFramePoints()

        assertEquals(DRIVE_POINTS, points)
    }

    @Test
    fun `sin tramo seleccionado no hay puntos de encuadre`() {
        assertNull(readyTrack().selectedLegFramePoints())
    }

    // --- Atras ---

    @Test
    fun `atras con el panel desplegado lo pliega y no sale del modo`() {
        val next = readyTrack(expanded = true).afterBack()

        assertEquals(false, next?.panelExpanded)
    }

    @Test
    fun `atras con el panel plegado sale del modo`() {
        assertNull(readyTrack(expanded = false).afterBack())
    }

    @Test
    fun `atras conserva el tramo seleccionado al plegar`() {
        val track = readyTrack().withLegSelected(1).withPanelExpanded(true)

        assertEquals(1, track.afterBack()?.selectedLegIndex)
    }
}

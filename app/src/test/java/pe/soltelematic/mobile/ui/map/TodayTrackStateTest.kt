package pe.soltelematic.mobile.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.domain.model.Asset
import pe.soltelematic.mobile.domain.model.AssetFilter
import pe.soltelematic.mobile.domain.model.AssetIcon
import pe.soltelematic.mobile.domain.model.AssetStatus
import pe.soltelematic.mobile.domain.model.AssetStatusType
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.Ignition
import pe.soltelematic.mobile.ui.history.RouteMapData
import pe.soltelematic.mobile.ui.map.engine.MapMarkerData
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerData
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import pe.soltelematic.mobile.ui.map.engine.RoutePoint
import pe.soltelematic.mobile.ui.map.engine.RoutePolyline

private fun marker(id: Int) = MapMarkerData(
    id = id,
    position = GeoPoint(-6.0 + id, -76.0),
    title = "U$id",
    iconUrl = null,
    statusColorArgb = 0,
    dimmed = false
)

private fun asset(id: Int, name: String, type: AssetStatusType = AssetStatusType.ONLINE) = Asset(
    id = id,
    groupId = null,
    name = name,
    active = true,
    position = GeoPoint(-6.0, -76.0),
    speedKph = null,
    speedText = null,
    speedUnit = null,
    ignition = Ignition.NO_SENSOR,
    status = AssetStatus(type = type, title = null, colorHex = null),
    icon = AssetIcon(url = null, colorHex = null, courseDegrees = null),
    tail = emptyList(),
    tailColorHex = null,
    lastSeenAt = null,
    lastSeenFormatted = null
)

class TodayTrackStateTest {

    // --- Filtrado de marcadores en el modo ---

    @Test
    fun `en el modo solo queda el marcador de la unidad seguida`() {
        val markers = listOf(marker(1), marker(2), marker(3))

        val shown = markers.onlyTracked(trackedAssetId = 2)

        assertEquals(listOf(2), shown.map { it.id })
    }

    @Test
    fun `fuera del modo los marcadores no se tocan`() {
        val markers = listOf(marker(1), marker(2), marker(3))

        assertEquals(markers, markers.onlyTracked(trackedAssetId = null))
    }

    @Test
    fun `si la unidad seguida no tiene marcador no queda ninguno`() {
        val markers = listOf(marker(1), marker(2))

        assertTrue(markers.onlyTracked(trackedAssetId = 9).isEmpty())
    }

    // --- Puntos de encuadre ---

    @Test
    fun `el encuadre incluye polilineas, paradas y la posicion actual`() {
        val track = TodayTrackState(
            assetId = 1,
            status = TodayTrackStatus.READY,
            mapData = RouteMapData(
                polylines = listOf(
                    RoutePolyline(
                        legIndex = 1,
                        points = listOf(RoutePoint(GeoPoint(1.0, 1.0), null), RoutePoint(GeoPoint(2.0, 2.0), null))
                    )
                ),
                markers = listOf(RouteMarkerData(legIndex = 0, position = GeoPoint(0.5, 0.5), role = RouteMarkerRole.STOP))
            )
        )

        val points = track.framePoints(assetPosition = GeoPoint(3.0, 3.0))

        assertEquals(
            listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0), GeoPoint(0.5, 0.5), GeoPoint(3.0, 3.0)),
            points
        )
    }

    @Test
    fun `sin recorrido el encuadre es solo la posicion de la unidad`() {
        val track = TodayTrackState(assetId = 1, status = TodayTrackStatus.EMPTY)

        assertEquals(listOf(GeoPoint(3.0, 3.0)), track.framePoints(assetPosition = GeoPoint(3.0, 3.0)))
        assertTrue(track.framePoints(assetPosition = null).isEmpty())
    }

    // --- Sugerencias del buscador ---

    @Test
    fun `sin texto no hay sugerencias`() {
        val state = MapUiState(assets = listOf(asset(1, "Camion 1")))

        assertTrue(state.searchSuggestions.isEmpty())
    }

    @Test
    fun `las sugerencias filtran por nombre sin distinguir mayusculas`() {
        val state = MapUiState(
            assets = listOf(asset(1, "Camion 1"), asset(2, "Tractor"), asset(3, "CAMION 2")),
            searchQuery = "camion"
        )

        assertEquals(listOf(1, 3), state.searchSuggestions.map { it.id })
    }

    @Test
    fun `las sugerencias se limitan a seis`() {
        val state = MapUiState(
            assets = (1..10).map { asset(it, "Unidad $it") },
            searchQuery = "unidad"
        )

        assertEquals(MAX_SEARCH_SUGGESTIONS, state.searchSuggestions.size)
        assertEquals(6, state.searchSuggestions.size)
    }

    @Test
    fun `las sugerencias respetan el chip de filtro activo`() {
        val state = MapUiState(
            assets = listOf(asset(1, "Camion 1", AssetStatusType.ONLINE), asset(2, "Camion 2", AssetStatusType.OFFLINE)),
            searchQuery = "camion",
            activeFilter = AssetFilter.OFFLINE
        )

        assertEquals(listOf(2), state.searchSuggestions.map { it.id })
    }

    @Test
    fun `una unica sugerencia permite seleccionar con Enter`() {
        val state = MapUiState(
            assets = listOf(asset(1, "Camion 1"), asset(2, "Tractor")),
            searchQuery = "tractor"
        )

        assertEquals(2, state.searchSuggestions.singleOrNull()?.id)
    }

    @Test
    fun `con varias sugerencias Enter no elige ninguna`() {
        val state = MapUiState(
            assets = listOf(asset(1, "Camion 1"), asset(2, "Camion 2")),
            searchQuery = "camion"
        )

        assertNull(state.searchSuggestions.singleOrNull())
    }

    @Test
    fun `la unidad seguida se resuelve desde assets`() {
        val state = MapUiState(
            assets = listOf(asset(1, "Camion 1"), asset(2, "Tractor")),
            todayTrack = TodayTrackState(assetId = 2)
        )

        assertEquals("Tractor", state.trackedAsset?.name)
        assertNull(MapUiState(assets = listOf(asset(1, "Camion 1"))).trackedAsset)
    }
}

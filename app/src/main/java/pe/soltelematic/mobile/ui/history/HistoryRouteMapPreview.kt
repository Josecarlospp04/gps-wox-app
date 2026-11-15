package pe.soltelematic.mobile.ui.history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import coil.ImageLoader
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryPosition
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.ui.map.engine.RouteMapEngine
import pe.soltelematic.mobile.ui.map.engine.google.GoogleRouteMapEngine
import pe.soltelematic.mobile.ui.theme.SOLTELEMATICTheme

/**
 * Datos calcados del payload real de device 455 (ver plan del Sprint 2B, Bloque 1): una parada
 * seguida de un viaje. Cubre a propósito los dos casos límite de HistoryRouteMapMapper: el día
 * empieza en parada (sin ROUTE_START redundante) y termina en drive (sí lleva ROUTE_END).
 *
 * OJO: el SDK de Google Maps no dibuja tiles reales dentro del renderer de preview de Android
 * Studio (sin Play Services) -- esto valida que el mapeo dominio -> engine y el wiring de
 * marcadores/polyline compilan y no crashean, no la apariencia visual real. Esa confirmación
 * queda para el Bloque 4, corriendo la app.
 */
@Preview(showBackground = true, heightDp = 400)
@Composable
private fun HistoryRouteMapPreview() {
    val mapData = remember { sampleRoute().toRouteMapData() }
    val context = LocalContext.current
    val engine: RouteMapEngine = remember { GoogleRouteMapEngine(ImageLoader.Builder(context).build()) }

    SOLTELEMATICTheme {
        val cameraController = engine.rememberCameraController()
        engine.Content(
            modifier = Modifier.fillMaxSize(),
            cameraController = cameraController,
            polylines = mapData.polylines,
            markers = mapData.markers,
            selectedLegIndex = 0,
            onMarkerClick = {},
            playbackPoint = null,
            playbackBearing = 0f,
            unitIcon = null,
            onCameraGesture = {},
            mapType = MapType.NORMAL,
            speedRanges = SpeedRanges.DEFAULT
        )
    }
}

private fun sampleRoute(): HistoryRoute = HistoryRoute(
    periodStats = emptyList(),
    legs = listOf(
        HistoryStopLeg(
            title = "Detener",
            start = HistoryEndpoint(GeoPoint(-12.06362, -76.981367), null),
            end = HistoryEndpoint(GeoPoint(-12.063652, -76.981733), null),
            stats = emptyList()
        ),
        HistoryDriveLeg(
            title = "Conducir",
            start = HistoryEndpoint(GeoPoint(-12.063652, -76.981733), null),
            end = HistoryEndpoint(GeoPoint(-12.067388, -76.986733), null),
            stats = emptyList(),
            positions = listOf(
                HistoryPosition(GeoPoint(-12.063652, -76.981733), null, "6", "#0000FF"),
                HistoryPosition(GeoPoint(-12.0653, -76.9840), null, "12", "#00FF00"),
                HistoryPosition(GeoPoint(-12.067388, -76.986733), null, "8", "#0000FF")
            )
        )
    )
)

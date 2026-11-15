package pe.soltelematic.mobile.ui.map.engine.google

import org.junit.Assert.assertEquals
import org.junit.Test
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerKey
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole

class MarkerTapRoutingTest {

    @Test
    fun `un marcador con RouteMarkerKey como tag es de ruta y lleva su clave`() {
        val key = RouteMarkerKey(RouteMarkerRole.IGNITION_ON, null, 3)

        assertEquals(MarkerTap.Route(key), classifyMarkerTag(key))
    }

    @Test
    fun `parada y partida y llegada tambien son de ruta`() {
        listOf(
            RouteMarkerKey(RouteMarkerRole.STOP, 2, null),
            RouteMarkerKey(RouteMarkerRole.ROUTE_START, 0, null),
            RouteMarkerKey(RouteMarkerRole.ROUTE_END, 8, null),
            RouteMarkerKey(RouteMarkerRole.IGNITION_OFF, null, 7)
        ).forEach { key -> assertEquals(MarkerTap.Route(key), classifyMarkerTag(key)) }
    }

    @Test
    fun `sin tag o con otro tag el marcador sigue el camino de siempre`() {
        // Unidades, clusters y el anillo de selección: sin tag propio, o con tags ajenos.
        assertEquals(MarkerTap.Other, classifyMarkerTag(null))
        assertEquals(MarkerTap.Other, classifyMarkerTag("cluster"))
        assertEquals(MarkerTap.Other, classifyMarkerTag(42))
    }
}

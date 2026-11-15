package pe.soltelematic.mobile.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pe.soltelematic.mobile.data.remote.dto.AssetTimeDto
import pe.soltelematic.mobile.data.remote.dto.HistoryCoordinatesDto
import pe.soltelematic.mobile.data.remote.dto.HistoryDataDto
import pe.soltelematic.mobile.data.remote.dto.HistoryItemDto
import pe.soltelematic.mobile.data.remote.dto.HistoryPointDto
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.RouteEventType
import java.time.Instant

private fun eventItem(title: String?, timestamp: Long? = 1794661703, lat: Double? = -11.95, lng: Double? = -76.98) = HistoryItemDto(
    status = "event",
    title = title,
    start = HistoryPointDto(
        time = AssetTimeDto(timestamp = timestamp, formatted = "14-11-2026 08:08:23"),
        coordinates = if (lat != null && lng != null) HistoryCoordinatesDto(lat, lng) else null
    )
)

private fun stopItem() = HistoryItemDto(
    status = "stop",
    title = "Detener",
    start = HistoryPointDto(AssetTimeDto(1794632806, "14-11-2026 00:06:46"), HistoryCoordinatesDto(-11.95, -76.98)),
    end = HistoryPointDto(AssetTimeDto(1794661708, "14-11-2026 08:08:28"), HistoryCoordinatesDto(-11.95, -76.98))
)

class HistoryEventMapperTest {

    @Test
    fun `un item event ya no se descarta y pasa a events con tipo, hora, punto y titulo`() {
        val route = HistoryDataDto(items = listOf(eventItem("Encendido conectado"))).toDomain()

        val event = route.events.single()
        assertEquals(RouteEventType.IGNITION_ON, event.type)
        assertEquals(Instant.ofEpochSecond(1794661703), event.time)
        assertEquals(GeoPoint(-11.95, -76.98), event.point)
        assertEquals("Encendido conectado", event.title)
    }

    @Test
    fun `la hora sale del timestamp epoch, no del texto formateado`() {
        val event = HistoryDataDto(items = listOf(eventItem("Ignición apagada", timestamp = 1794632457))).toDomain().events.single()

        // 1794632457 = 2026-11-14T05:00:57Z (00:00:57 en Perú).
        assertEquals(Instant.parse("2026-11-14T05:00:57Z"), event.time)
    }

    @Test
    fun `los eventos no son tramos y los tramos siguen igual`() {
        val route = HistoryDataDto(items = listOf(stopItem(), eventItem("Ignición apagada"), eventItem("Exceso de velocidad (5 kph)"))).toDomain()

        assertEquals(1, route.legs.size)
        assertEquals(listOf(RouteEventType.IGNITION_OFF, RouteEventType.OTHER), route.events.map { it.type })
    }

    @Test
    fun `se conservan todos los eventos con su titulo original aunque no se dibujen`() {
        val route = HistoryDataDto(items = listOf(eventItem("Rellenos de combustible"))).toDomain()

        assertEquals("Rellenos de combustible", route.events.single().title)
        assertEquals(RouteEventType.OTHER, route.events.single().type)
    }

    @Test
    fun `sin timestamp o sin coordenadas el evento llega con esos campos en null`() {
        val event = HistoryDataDto(items = listOf(eventItem("Encendido conectado", timestamp = null, lat = null, lng = null))).toDomain().events.single()

        assertNull(event.time)
        assertNull(event.point)
    }

    @Test
    fun `un item sin titulo es un evento OTHER con titulo vacio`() {
        val event = HistoryDataDto(items = listOf(eventItem(null))).toDomain().events.single()

        assertEquals(RouteEventType.OTHER, event.type)
        assertEquals("", event.title)
    }
}

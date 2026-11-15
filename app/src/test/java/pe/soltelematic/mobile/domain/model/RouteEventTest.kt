package pe.soltelematic.mobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteEventTest {

    @Test
    fun `reconoce los dos titulos de ignicion tal como los manda el servidor`() {
        assertEquals(RouteEventType.IGNITION_ON, classifyRouteEventTitle("Encendido conectado"))
        assertEquals(RouteEventType.IGNITION_OFF, classifyRouteEventTitle("Ignición apagada"))
    }

    @Test
    fun `no distingue mayusculas`() {
        assertEquals(RouteEventType.IGNITION_ON, classifyRouteEventTitle("ENCENDIDO CONECTADO"))
        assertEquals(RouteEventType.IGNITION_OFF, classifyRouteEventTitle("ignición APAGADA"))
    }

    @Test
    fun `no distingue tildes`() {
        assertEquals(RouteEventType.IGNITION_OFF, classifyRouteEventTitle("Ignicion apagada"))
        assertEquals(RouteEventType.IGNITION_OFF, classifyRouteEventTitle("IGNICION APAGADA"))
    }

    @Test
    fun `tolera espacios y un sufijo despues del titulo`() {
        assertEquals(RouteEventType.IGNITION_ON, classifyRouteEventTitle("  Encendido conectado  "))
        assertEquals(RouteEventType.IGNITION_OFF, classifyRouteEventTitle("Ignición apagada (sensor 1)"))
    }

    @Test
    fun `los titulos que no son de ignicion se ignoran`() {
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle("Exceso de velocidad (5 kph)"))
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle("Rellenos de combustible"))
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle("Geocerca entrada"))
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle("DIN1 conectado"))
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle("Corte de alimentación"))
    }

    @Test
    fun `un titulo ausente o vacio es OTHER`() {
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle(null))
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle(""))
        assertEquals(RouteEventType.OTHER, classifyRouteEventTitle("   "))
    }
}

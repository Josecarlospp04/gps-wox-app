package pe.soltelematic.mobile.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pe.soltelematic.mobile.ui.history.toSpeedKphOrNull

class SpeedToKphTest {

    @Test
    fun `kph y sus variantes o sin unidad quedan tal cual`() {
        assertEquals(50.0, speedToKph(50.0, "kph")!!, 0.0)
        assertEquals(50.0, speedToKph(50.0, "KM/H")!!, 0.0)
        assertEquals(50.0, speedToKph(50.0, null)!!, 0.0)
        assertEquals(50.0, speedToKph(50.0, "  ")!!, 0.0)
    }

    @Test
    fun `mph, nudos y m por segundo se convierten a km por hora`() {
        assertEquals(80.4672, speedToKph(50.0, "mph")!!, 0.0001)
        assertEquals(92.6, speedToKph(50.0, "kn")!!, 0.0001)
        assertEquals(92.6, speedToKph(50.0, "Knots")!!, 0.0001)
        assertEquals(36.0, speedToKph(10.0, "m/s")!!, 0.0001)
    }

    @Test
    fun `valor ausente o unidad desconocida dan null`() {
        assertNull(speedToKph(null, "kph"))
        assertNull(speedToKph(50.0, "furlongs"))
    }

    @Test
    fun `la velocidad de una posicion del historial se lee del texto`() {
        assertEquals(56.0, "56".toSpeedKphOrNull()!!, 0.0)
        assertEquals(12.5, " 12,5 ".toSpeedKphOrNull()!!, 0.0)
        assertNull("".toSpeedKphOrNull())
        assertNull("abc".toSpeedKphOrNull())
        assertNull((null as String?).toSpeedKphOrNull())
    }
}

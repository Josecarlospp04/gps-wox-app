package pe.soltelematic.mobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRangesTest {

    private val defaults = SpeedRanges.DEFAULT

    @Test
    fun `los limites por defecto son 20, 60 y 80`() {
        assertEquals(SpeedRanges(20, 60, 80), defaults)
        assertTrue(defaults.isValid)
    }

    @Test
    fun `la banda incluye su limite inferior en los bordes 19, 20, 59, 60, 79 y 80`() {
        assertEquals(SpeedBand.LOW, defaults.bandFor(19.0))
        assertEquals(SpeedBand.MEDIUM, defaults.bandFor(20.0))
        assertEquals(SpeedBand.MEDIUM, defaults.bandFor(59.0))
        assertEquals(SpeedBand.HIGH, defaults.bandFor(60.0))
        assertEquals(SpeedBand.HIGH, defaults.bandFor(79.0))
        assertEquals(SpeedBand.VERY_HIGH, defaults.bandFor(80.0))
    }

    @Test
    fun `los bordes decimales caen en la banda de abajo hasta llegar al limite`() {
        assertEquals(SpeedBand.LOW, defaults.bandFor(19.99))
        assertEquals(SpeedBand.MEDIUM, defaults.bandFor(59.99))
        assertEquals(SpeedBand.HIGH, defaults.bandFor(79.99))
    }

    @Test
    fun `cero y velocidades muy altas`() {
        assertEquals(SpeedBand.LOW, defaults.bandFor(0.0))
        assertEquals(SpeedBand.VERY_HIGH, defaults.bandFor(250.0))
    }

    @Test
    fun `una velocidad ausente o no numerica no tiene banda`() {
        assertNull(defaults.bandFor(null))
        assertNull(defaults.bandFor(Double.NaN))
        assertNull(defaults.bandFor(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `la banda usa los limites personalizados`() {
        val custom = SpeedRanges(10, 30, 50)

        assertEquals(SpeedBand.LOW, custom.bandFor(9.0))
        assertEquals(SpeedBand.MEDIUM, custom.bandFor(10.0))
        assertEquals(SpeedBand.HIGH, custom.bandFor(30.0))
        assertEquals(SpeedBand.VERY_HIGH, custom.bandFor(50.0))
    }

    // --- validación del texto escrito en Cuenta

    @Test
    fun `tres enteros positivos ascendentes son validos`() {
        assertEquals(SpeedRangesParse.Valid(SpeedRanges(10, 30, 50)), SpeedRanges.parse("10", "30", "50"))
        assertEquals(SpeedRangesParse.Valid(SpeedRanges(1, 2, 3)), SpeedRanges.parse(" 1 ", "2", "3"))
    }

    @Test
    fun `un campo vacio o no entero no es valido`() {
        val notANumber = SpeedRangesParse.Invalid(SpeedRangesError.NOT_A_NUMBER)

        assertEquals(notANumber, SpeedRanges.parse("", "60", "80"))
        assertEquals(notANumber, SpeedRanges.parse("20", "abc", "80"))
        assertEquals(notANumber, SpeedRanges.parse("20", "60", "8.5"))
        assertEquals(notANumber, SpeedRanges.parse("20", "60", "99999999999"))
    }

    @Test
    fun `cero o negativos no son validos`() {
        val notPositive = SpeedRangesParse.Invalid(SpeedRangesError.NOT_POSITIVE)

        assertEquals(notPositive, SpeedRanges.parse("0", "60", "80"))
        assertEquals(notPositive, SpeedRanges.parse("-5", "60", "80"))
    }

    @Test
    fun `los limites deben ser estrictamente ascendentes`() {
        val notAscending = SpeedRangesParse.Invalid(SpeedRangesError.NOT_ASCENDING)

        assertEquals(notAscending, SpeedRanges.parse("60", "20", "80"))
        assertEquals(notAscending, SpeedRanges.parse("20", "20", "80"))
        assertEquals(notAscending, SpeedRanges.parse("20", "80", "80"))
        assertEquals(notAscending, SpeedRanges.parse("80", "60", "20"))
    }

    @Test
    fun `isValid refleja la misma regla`() {
        assertTrue(SpeedRanges(1, 2, 3).isValid)
        assertFalse(SpeedRanges(0, 2, 3).isValid)
        assertFalse(SpeedRanges(5, 5, 9).isValid)
        assertFalse(SpeedRanges(9, 5, 3).isValid)
    }

    // --- lo guardado en el DataStore

    @Test
    fun `sin nada guardado se usan los valores por defecto`() {
        assertEquals(defaults, SpeedRanges.fromStored(null, null, null))
    }

    @Test
    fun `un valor guardado valido se respeta`() {
        assertEquals(SpeedRanges(10, 30, 50), SpeedRanges.fromStored(10, 30, 50))
    }

    @Test
    fun `un conjunto incompleto o invalido cae completo a los valores por defecto`() {
        assertEquals(defaults, SpeedRanges.fromStored(10, null, 50))
        assertEquals(defaults, SpeedRanges.fromStored(50, 30, 10))
        assertEquals(defaults, SpeedRanges.fromStored(0, 30, 50))
        assertEquals(defaults, SpeedRanges.fromStored(30, 30, 50))
    }
}

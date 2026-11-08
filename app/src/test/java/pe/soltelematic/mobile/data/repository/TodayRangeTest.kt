package pe.soltelematic.mobile.data.repository

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayRangeTest {

    @Test
    fun `el rango de hoy va de medianoche a ahora`() {
        val now = LocalDateTime.of(2026, 11, 8, 14, 35, 12)

        val (from, to) = todayRange(now)

        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0, 0), from)
        assertEquals(now, to)
    }

    @Test
    fun `justo despues de medianoche el rango es de un instante`() {
        val now = LocalDateTime.of(2026, 11, 8, 0, 0, 1)

        val (from, to) = todayRange(now)

        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0, 0), from)
        assertEquals(now, to)
    }

    @Test
    fun `a las 23-59-59 el inicio sigue siendo la medianoche del mismo dia`() {
        val now = LocalDateTime.of(2026, 12, 31, 23, 59, 59)

        val (from, _) = todayRange(now)

        assertEquals(LocalDateTime.of(2026, 12, 31, 0, 0, 0), from)
    }
}

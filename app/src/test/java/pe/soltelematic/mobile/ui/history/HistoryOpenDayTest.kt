package pe.soltelematic.mobile.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test
import pe.soltelematic.mobile.ui.navigation.Destination
import java.time.LocalDate

class HistoryOpenDayTest {

    private val today = LocalDate.of(2026, 11, 14)

    @Test
    fun `abrir hoy o ayer conserva sus chips`() {
        assertEquals(HistoryDateRange(today, today, HistoryDateRange.Preset.TODAY), HistoryDateRange.forDay(today, today))
        val yesterday = today.minusDays(1)
        assertEquals(HistoryDateRange(yesterday, yesterday, HistoryDateRange.Preset.YESTERDAY), HistoryDateRange.forDay(yesterday, today))
    }

    @Test
    fun `otra fecha abre ese dia exacto como rango propio`() {
        val day = LocalDate.of(2026, 11, 12)

        assertEquals(HistoryDateRange(day, day, HistoryDateRange.Preset.CUSTOM), HistoryDateRange.forDay(day, today))
    }

    @Test
    fun `la ruta de Historial lleva el dia solo si se indica`() {
        assertEquals("asset/450/history", Destination.History.createRoute(450))
        assertEquals("asset/450/history?day=2026-11-12", Destination.History.createRoute(450, LocalDate.of(2026, 11, 12)))
    }
}

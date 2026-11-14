package pe.soltelematic.mobile.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.core.result.ApiError
import pe.soltelematic.mobile.core.result.ApiResult
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.HistoryDriveLeg
import pe.soltelematic.mobile.domain.model.HistoryEndpoint
import pe.soltelematic.mobile.domain.model.HistoryLeg
import pe.soltelematic.mobile.domain.model.HistoryRoute
import pe.soltelematic.mobile.domain.model.HistoryStopLeg
import java.time.LocalDate
import java.time.LocalDateTime

private val TODAY = LocalDate.of(2026, 11, 14)

private val POINT = HistoryEndpoint(GeoPoint(-6.0, -76.0), null)

private fun stopLeg() = HistoryStopLeg(title = null, start = POINT, end = POINT, stats = emptyList())
private fun driveLeg() = HistoryDriveLeg(title = null, start = POINT, end = POINT, stats = emptyList(), positions = emptyList())
private fun route(vararg legs: HistoryLeg) = HistoryRoute(emptyList(), legs.toList())

private val EMPTY_DAY = route()
private val ONLY_STOPS = route(stopLeg())
private val WITH_DRIVE = route(stopLeg(), driveLeg(), stopLeg())

/** Servidor falso: ruta por día (los días no listados responden vacío) y registro de lo pedido. */
private class FakeServer(private val days: Map<LocalDate, HistoryRoute>, private val failOn: LocalDate? = null) {
    val requested = mutableListOf<LocalDate>()

    suspend fun fetch(day: LocalDate): ApiResult<HistoryRoute> {
        requested += day
        return if (day == failOn) ApiResult.Error(ApiError.NoConnection) else ApiResult.Success(days[day] ?: EMPTY_DAY)
    }
}

private fun find(server: FakeServer, lastSeenDay: LocalDate? = null): LastRouteResult =
    runBlocking { findLastRoute(TODAY, lastSeenDay) { server.fetch(it) } }

class LastRouteSearchTest {

    @Test
    fun `hoy con viaje se muestra hoy y no se pide nada mas`() {
        val server = FakeServer(mapOf(TODAY to WITH_DRIVE, TODAY.minusDays(1) to WITH_DRIVE))

        val result = find(server)

        assertEquals(LastRouteResult.Found(TODAY, WITH_DRIVE), result)
        assertEquals(listOf(TODAY), server.requested)
    }

    @Test
    fun `hoy solo con paradas y ayer con viaje se muestra ayer`() {
        val yesterday = TODAY.minusDays(1)
        val server = FakeServer(mapOf(TODAY to ONLY_STOPS, yesterday to WITH_DRIVE))

        val result = find(server)

        assertEquals(LastRouteResult.Found(yesterday, WITH_DRIVE), result)
        assertEquals(listOf(TODAY, yesterday), server.requested)
    }

    @Test
    fun `hoy vacio busca hacia atras dia por dia hasta encontrar un viaje`() {
        val threeDaysAgo = TODAY.minusDays(3)
        val server = FakeServer(mapOf(TODAY.minusDays(2) to ONLY_STOPS, threeDaysAgo to WITH_DRIVE))

        val result = find(server)

        assertEquals(LastRouteResult.Found(threeDaysAgo, WITH_DRIVE), result)
        assertEquals((0L..3L).map { TODAY.minusDays(it) }, server.requested)
    }

    @Test
    fun `sin viaje en 7 dias no hay ruta y se piden exactamente 7 dias`() {
        val server = FakeServer(mapOf(TODAY to ONLY_STOPS, TODAY.minusDays(7) to WITH_DRIVE))

        val result = find(server)

        assertSame(LastRouteResult.NoRoute, result)
        assertEquals((0L..6L).map { TODAY.minusDays(it) }, server.requested)
    }

    @Test
    fun `el septimo dia contando hoy todavia cuenta y el octavo no`() {
        val sixDaysAgo = TODAY.minusDays(6)

        assertEquals(LastRouteResult.Found(sixDaysAgo, WITH_DRIVE), find(FakeServer(mapOf(sixDaysAgo to WITH_DRIVE))))
        assertSame(LastRouteResult.NoRoute, find(FakeServer(mapOf(TODAY.minusDays(7) to WITH_DRIVE))))
    }

    @Test
    fun `un fallo de red corta la busqueda y no sigue con dias mas viejos`() {
        val yesterday = TODAY.minusDays(1)
        val server = FakeServer(mapOf(TODAY.minusDays(2) to WITH_DRIVE), failOn = yesterday)

        val result = find(server)

        assertEquals(LastRouteResult.Failed(ApiError.NoConnection), result)
        assertEquals(listOf(TODAY, yesterday), server.requested)
    }

    @Test
    fun `si la unidad reporto por ultima vez antes de hoy se salta hoy`() {
        val lastSeen = TODAY.minusDays(2)
        val server = FakeServer(mapOf(lastSeen to WITH_DRIVE))

        val result = find(server, lastSeenDay = lastSeen)

        assertEquals(LastRouteResult.Found(lastSeen, WITH_DRIVE), result)
        assertEquals(listOf(lastSeen), server.requested)
    }

    @Test
    fun `si el ultimo reporte es anterior a la ventana no se pide nada`() {
        val server = FakeServer(emptyMap())

        val result = find(server, lastSeenDay = TODAY.minusDays(10))

        assertSame(LastRouteResult.NoRoute, result)
        assertTrue(server.requested.isEmpty())
    }

    @Test
    fun `un ultimo reporte de hoy o futuro no cambia el dia de arranque`() {
        assertEquals(TODAY, lastRouteSearchStart(TODAY, TODAY))
        assertEquals(TODAY, lastRouteSearchStart(TODAY, TODAY.plusDays(1)))
        assertEquals(TODAY, lastRouteSearchStart(TODAY, null))
        assertEquals(TODAY.minusDays(1), lastRouteSearchStart(TODAY, TODAY.minusDays(1)))
    }

    @Test
    fun `solo cuenta como recorrido un tramo drive`() {
        assertTrue(WITH_DRIVE.hasDrive())
        assertFalse(ONLY_STOPS.hasDrive())
        assertFalse(EMPTY_DAY.hasDrive())
    }

    @Test
    fun `el rango de un dia pasado es completo y el de hoy llega hasta ahora`() {
        val now = LocalDateTime.of(2026, 11, 14, 18, 20, 40)

        assertEquals(
            LocalDateTime.of(2026, 11, 13, 0, 0, 0) to LocalDateTime.of(2026, 11, 13, 23, 59, 59),
            dayRange(LocalDate.of(2026, 11, 13), now)
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 14, 0, 0, 0) to now,
            dayRange(LocalDate.of(2026, 11, 14), now)
        )
    }
}

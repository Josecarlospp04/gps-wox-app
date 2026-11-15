package pe.soltelematic.mobile.ui.map.engine.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.SpeedBand
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.ui.map.engine.RoutePoint

private fun point(index: Int, speedKph: Double?) = RoutePoint(GeoPoint(-6.0 + index * 0.0001, -76.0), speedKph)

class RouteBandGroupingTest {

    private val ranges = SpeedRanges.DEFAULT

    @Test
    fun `puntos consecutivos de la misma banda forman un solo tramo`() {
        val points = listOf(point(0, 5.0), point(1, 10.0), point(2, 19.0))

        val runs = points.groupByBand(ranges)

        assertEquals(1, runs.size)
        assertEquals(SpeedBand.LOW, runs[0].band)
        assertEquals(points, runs[0].points)
    }

    @Test
    fun `al cambiar de banda el tramo nuevo repite el ultimo punto del anterior`() {
        val p = (0..4).map { point(it, listOf(10.0, 15.0, 30.0, 40.0, 90.0)[it]) }

        val runs = p.groupByBand(ranges)

        assertEquals(listOf(SpeedBand.LOW, SpeedBand.MEDIUM, SpeedBand.VERY_HIGH), runs.map { it.band })
        assertEquals(listOf(p[0], p[1]), runs[0].points)
        // El segmento p1->p2 lo pinta la banda del punto de llegada (p2): azul, no verde.
        assertEquals(listOf(p[1], p[2], p[3]), runs[1].points)
        assertEquals(listOf(p[3], p[4]), runs[2].points)
    }

    @Test
    fun `la velocidad de cada tramo es la del punto de llegada en los bordes`() {
        // 19 -> 20 -> 59 -> 60 -> 79 -> 80
        val speeds = listOf(19.0, 20.0, 59.0, 60.0, 79.0, 80.0)
        val p = speeds.mapIndexed { i, s -> point(i, s) }

        val runs = p.groupByBand(ranges)

        assertEquals(
            listOf(SpeedBand.LOW, SpeedBand.MEDIUM, SpeedBand.HIGH, SpeedBand.VERY_HIGH),
            runs.map { it.band }
        )
        assertEquals(listOf(p[1], p[2]), runs[1].points.drop(0).takeLast(2))
        assertEquals(listOf(p[2], p[3], p[4]), runs[2].points)
        assertEquals(listOf(p[4], p[5]), runs[3].points)
    }

    @Test
    fun `el primer tramo de un solo punto queda aparte para que quien dibuja lo descarte`() {
        val p = listOf(point(0, 10.0), point(1, 30.0))

        val runs = p.groupByBand(ranges)

        assertEquals(2, runs.size)
        assertEquals(1, runs[0].points.size)
        assertEquals(listOf(p[0], p[1]), runs[1].points)
    }

    @Test
    fun `una velocidad desconocida forma su propio tramo gris`() {
        val p = listOf(point(0, 10.0), point(1, null), point(2, null), point(3, 10.0))

        val runs = p.groupByBand(ranges)

        assertEquals(listOf(SpeedBand.LOW, null, SpeedBand.LOW), runs.map { it.band })
        assertNull(runs[1].band)
        assertEquals(listOf(p[0], p[1], p[2]), runs[1].points)
    }

    @Test
    fun `cambiar los limites cambia la agrupacion sin tocar los puntos`() {
        val p = listOf(point(0, 25.0), point(1, 25.0), point(2, 25.0))

        assertEquals(listOf(SpeedBand.MEDIUM), p.groupByBand(SpeedRanges(20, 60, 80)).map { it.band })
        assertEquals(listOf(SpeedBand.LOW), p.groupByBand(SpeedRanges(30, 60, 80)).map { it.band })
        assertEquals(listOf(SpeedBand.VERY_HIGH), p.groupByBand(SpeedRanges(5, 10, 20)).map { it.band })
    }

    @Test
    fun `sin puntos no hay tramos`() {
        assertEquals(emptyList<BandRun>(), emptyList<RoutePoint>().groupByBand(ranges))
    }
}

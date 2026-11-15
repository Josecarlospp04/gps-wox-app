package pe.soltelematic.mobile.ui.map.engine.google

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import kotlin.math.roundToInt
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.SpeedBand
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import pe.soltelematic.mobile.ui.map.engine.isEvent
import pe.soltelematic.mobile.ui.map.engine.RoutePoint
import pe.soltelematic.mobile.ui.map.engine.RoutePolyline
import pe.soltelematic.mobile.ui.theme.SoltelematicColors

// Dibujado de rutas compartido por GoogleRouteMapEngine (Historial) y GoogleMapEngine (modo
// "Recorrido de hoy" del mapa principal). Extraído SIN cambios de lógica de GoogleRouteMapEngine.

internal const val POLYLINE_WIDTH_PX = 10f
internal const val SELECTED_POLYLINE_WIDTH_PX = 16f
// Opacidad del tramo provisional en vivo (aún no confirmado por el servidor).
internal const val PROVISIONAL_ALPHA = 0.65f

// Gris neutro: velocidad desconocida (positions[].s ausente o no numérica).
internal val NEUTRAL_POLYLINE_COLOR = Color(0xFF757575)

// Un recorrido largo trae miles de posiciones (verificado con datos reales: 31,132 puntos en 7
// días de una sola unidad) -- ver investigación de la corrección post-2B. Dibujar un Polyline
// nativo por cada par de puntos crashea la app (OutOfMemoryError dentro de addPolyline, cada
// llamada es su propia transacción Binder). toRenderablePolylineRuns() ataca esto en dos capas:
// agrupar por color consecutivo (sin pérdida) + Douglas-Peucker (con pérdida geométrica, acotada).
//
// BASE conservador a propósito: en cuadras urbanas angostas, un tolerance alto puede "cortar
// esquina" y hacer parecer que la unidad atravesó una manzana -- confirmado visualmente contra el
// trazado real de calles en Tarapoto antes de fijar este valor (ver notas de verificación).
// ESCALATED es la red de seguridad: si el presupuesto de puntos (MAX_TOTAL_POLYLINE_POINTS) no
// alcanza ni con BASE, se re-simplifica una vez más agresivo antes de, como último recurso,
// truncar runs completos -- el mapa puede quedar incompleto, la app nunca debe volver a crashear
// por esto sin importar cuántos días abarque el rango elegido (hasta 31, ver HistoryDateRange).
private const val BASE_SIMPLIFY_TOLERANCE_METERS = 3.0
private const val ESCALATED_SIMPLIFY_TOLERANCE_METERS = 25.0
private const val MAX_TOTAL_POLYLINE_POINTS = 6_000

// Tamaños de los pines circulares propios (retematizado, ver tarea de Historial): reemplazan los
// pines de stock de Google (BitmapDescriptorFactory.defaultMarker por hue), que no admiten los
// tokens de color de la marca. MARKER_SELECTED_DIAMETER_DP > MARKER_DIAMETER_DP porque el
// resaltado de selección es un anillo dibujado en el mismo bitmap (un solo Marker por dato, igual
// que antes), no una capa superpuesta.
private const val MARKER_DIAMETER_DP = 18
internal const val MARKER_SELECTED_DIAMETER_DP = 26
private const val MARKER_STROKE_WIDTH_DP = 2f
private const val MARKER_INNER_DOT_DIAMETER_DP = 6f
private const val MARKER_SELECTION_RING_WIDTH_DP = 2f
private const val MARKER_SELECTION_RING_GAP_DP = 2f

// Insignias de partida/llegada ("A"/"B"): más grandes que los pines de parada para no confundirse con
// los puntos de evento, que son más chicos. La variante seleccionada agrega el anillo en el mismo
// bitmap, igual que los pines de parada.
private const val BADGE_DIAMETER_DP = 24
private const val BADGE_SELECTED_DIAMETER_DP = 32
private const val BADGE_LETTER_FRACTION = 0.58f

// Puntos de evento (motor encendido/apagado): 14 dp en total, borde blanco incluido. El bitmap mide
// exactamente eso a propósito -- el área tocable de un marcador de Google es su bitmap, y uno más
// grande (con margen transparente) taparía el toque de la parada que tiene debajo.
private const val EVENT_DOT_DIAMETER_DP = 14
private const val EVENT_DOT_BORDER_DP = 2f
// Opacidad plena: Color.WHITE como Int de android.graphics.
private const val EVENT_DOT_BORDER_COLOR = 0xFFFFFFFF.toInt()

/** Colores de marca ya resueltos a Int (android.graphics.Color) -- ver buildRouteMarkerBitmap. */
internal data class RouteMarkerPalette(
    val statusMoving: Int,
    val onSurface: Int,
    val surface: Int,
    val outline: Int,
    val primary: Int,
    val statusAlert: Int
)

/**
 * Un bitmap por combinación (role, seleccionado) -- 6 en total, generados una vez por composición
 * de Content() y cacheados vía remember() sobre los colores resueltos. Mismo criterio que
 * MarkerIconCache (Bloque 7 del mapa en vivo): un bitmap propio por Canvas en vez de pines de
 * stock, para que el marcador respete los tokens de marca en vez del catálogo fijo de hues de
 * Google. No hace falta un cache más elaborado (por URL, LRU, etc.): son 6 bitmaps chicos, no
 * cientos de unidades.
 */
internal fun buildRouteMarkerIcons(
    density: Float,
    palette: RouteMarkerPalette
): Map<Pair<RouteMarkerRole, Boolean>, BitmapDescriptor> {
    fun iconFor(role: RouteMarkerRole, selected: Boolean): BitmapDescriptor = when (role) {
        // Partida / llegada: insignias oscuras (ink) con "A" / "B" en el color de superficie. Antes eran un
        // círculo verde y uno oscuro; el verde y el rojo pasaron a ser de los eventos de motor, así
        // que la forma (más grande, con letra) es lo que las distingue.
        RouteMarkerRole.ROUTE_START -> buildBadgeBitmap(
            density = density,
            selected = selected,
            letter = "A",
            fillColor = palette.onSurface,
            letterColor = palette.surface,
            selectionRingColor = palette.primary
        )
        RouteMarkerRole.ROUTE_END -> buildBadgeBitmap(
            density = density,
            selected = selected,
            letter = "B",
            fillColor = palette.onSurface,
            letterColor = palette.surface,
            selectionRingColor = palette.primary
        )
        // Eventos: punto verde (motor encendido) / rojo (apagado) con borde blanco. Sin variante
        // seleccionada propia: tocar un evento abre su tarjeta, no lo "selecciona" en la lista.
        RouteMarkerRole.IGNITION_ON -> buildEventDotBitmap(density, palette.statusMoving)
        RouteMarkerRole.IGNITION_OFF -> buildEventDotBitmap(density, palette.statusAlert)
        // Parada: círculo surface con borde outline y punto interior ink -- distinto de
        // inicio/fin porque una parada no es un extremo de la ruta.
        RouteMarkerRole.STOP -> buildRouteMarkerBitmap(
            density = density,
            selected = selected,
            fillColor = palette.surface,
            strokeColor = palette.outline,
            innerDotColor = palette.onSurface,
            selectionRingColor = palette.primary
        )
    }
    return RouteMarkerRole.values().flatMap { role ->
        listOf((role to false) to iconFor(role, false), (role to true) to iconFor(role, true))
    }.toMap()
}

/** Insignia circular con una letra centrada ("A" partida, "B" llegada), con anillo si está seleccionada. */
private fun buildBadgeBitmap(
    density: Float,
    selected: Boolean,
    letter: String,
    fillColor: Int,
    letterColor: Int,
    selectionRingColor: Int
): BitmapDescriptor {
    val diameterDp = if (selected) BADGE_SELECTED_DIAMETER_DP else BADGE_DIAMETER_DP
    val sizePx = (diameterDp * density).roundToInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val ringWidthPx = MARKER_SELECTION_RING_WIDTH_DP * density
    val ringGapPx = MARKER_SELECTION_RING_GAP_DP * density
    val bodyRadius = if (selected) center - ringWidthPx - ringGapPx else center

    if (selected) {
        canvas.drawCircle(center, center, center - ringWidthPx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = selectionRingColor
            style = Paint.Style.STROKE
            strokeWidth = ringWidthPx
        })
    }
    canvas.drawCircle(center, center, bodyRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    })
    val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = letterColor
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = bodyRadius * 2f * BADGE_LETTER_FRACTION
    }
    // Centrado vertical por métricas de la fuente (drawText ancla en la línea base).
    val baseline = center - (letterPaint.fontMetrics.ascent + letterPaint.fontMetrics.descent) / 2f
    canvas.drawText(letter, center, baseline, letterPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** Punto de evento: círculo de color con borde blanco, 14 dp en total. */
private fun buildEventDotBitmap(density: Float, fillColor: Int): BitmapDescriptor {
    val sizePx = (EVENT_DOT_DIAMETER_DP * density).roundToInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    canvas.drawCircle(center, center, center, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EVENT_DOT_BORDER_COLOR
        style = Paint.Style.FILL
    })
    canvas.drawCircle(center, center, center - EVENT_DOT_BORDER_DP * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    })
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/**
 * Ancla del icono sobre su coordenada. Un motor apagado/encendido cae casi siempre en el mismo punto
 * que una parada (y el encendido, también con la insignia "A"): centrados, el punto de 14 dp
 * quedaría completamente dentro de la parada y taparía su toque. Por eso los eventos se corren hacia
 * arriba, uno a cada lado: el apagado arriba a la izquierda y el encendido arriba a la derecha (su
 * esquina inferior va sobre la coordenada). Así evento y parada se ven y se pueden tocar. El resto
 * de iconos va centrado.
 */
internal fun RouteMarkerRole.markerAnchor(): Offset = when (this) {
    RouteMarkerRole.IGNITION_OFF -> Offset(1f, 1f)
    RouteMarkerRole.IGNITION_ON -> Offset(0f, 1f)
    else -> Offset(0.5f, 0.5f)
}

/** Los eventos van por encima de paradas y partida/llegada; lo seleccionado, justo debajo de ellos. */
internal fun RouteMarkerRole.markerZIndex(selected: Boolean): Float = when {
    isEvent -> 1.5f
    selected -> 1f
    else -> 0f
}

/**
 * Dibuja un pin circular a mano (Canvas), reemplazando BitmapDescriptorFactory.defaultMarker: el
 * pin de stock solo admite un hue de una paleta fija de Google, no un color de marca. selected
 * agranda el bitmap y agrega un anillo en selectionRingColor alrededor del mismo círculo -- un
 * solo Marker por dato (igual que antes de este cambio), no dos capas superpuestas.
 */
private fun buildRouteMarkerBitmap(
    density: Float,
    selected: Boolean,
    fillColor: Int,
    strokeColor: Int?,
    innerDotColor: Int?,
    selectionRingColor: Int
): BitmapDescriptor {
    val diameterDp = if (selected) MARKER_SELECTED_DIAMETER_DP else MARKER_DIAMETER_DP
    val sizePx = (diameterDp * density).roundToInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val ringWidthPx = MARKER_SELECTION_RING_WIDTH_DP * density
    val ringGapPx = MARKER_SELECTION_RING_GAP_DP * density
    val bodyRadius = if (selected) center - ringWidthPx - ringGapPx else center

    if (selected) {
        canvas.drawCircle(center, center, center - ringWidthPx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = selectionRingColor
            style = Paint.Style.STROKE
            strokeWidth = ringWidthPx
            isAntiAlias = true
        })
    }
    canvas.drawCircle(center, center, bodyRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    })
    if (strokeColor != null) {
        val strokeWidthPx = MARKER_STROKE_WIDTH_DP * density
        canvas.drawCircle(center, center, bodyRadius - strokeWidthPx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        })
    }
    if (innerDotColor != null) {
        canvas.drawCircle(center, center, MARKER_INNER_DOT_DIAMETER_DP * density / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = innerDotColor
            style = Paint.Style.FILL
        })
    }
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/**
 * Todo el recorrido no seleccionado, listo para dibujar: agrupado por banda de velocidad
 * consecutiva y simplificado, con presupuesto de puntos global (no por tramo -- lo que importa para
 * memoria es el total de objetos Polyline de la pantalla, no cuántos le tocan a cada viaje).
 *
 * Tres pasadas, cada una solo si la anterior no alcanzó el presupuesto:
 * 1. BASE_SIMPLIFY_TOLERANCE_METERS (conservador, ver nota arriba).
 * 2. ESCALATED_SIMPLIFY_TOLERANCE_METERS (más agresivo, para rangos largos con muchos puntos --
 *    p. ej. hasta 31 días de una unidad muy activa).
 * 3. Si ni así entra en el presupuesto (caso extremo, no visto con datos reales hasta hoy):
 *    truncar runs completos hasta caber. El mapa queda incompleto -- la lista de abajo (Bloque 3)
 *    sigue mostrando el día completo igual, esto solo recorta el dibujo del mapa -- pero la app
 *    nunca vuelve a crashear por esto.
 */
internal fun List<RoutePolyline>.toRenderablePolylineRuns(ranges: SpeedRanges): List<Pair<SpeedBand?, List<LatLng>>> {
    val baseRuns = toBandRuns(BASE_SIMPLIFY_TOLERANCE_METERS, ranges)
    if (baseRuns.sumOf { it.second.size } <= MAX_TOTAL_POLYLINE_POINTS) return baseRuns

    val escalatedRuns = toBandRuns(ESCALATED_SIMPLIFY_TOLERANCE_METERS, ranges)
    if (escalatedRuns.sumOf { it.second.size } <= MAX_TOTAL_POLYLINE_POINTS) return escalatedRuns

    val truncated = mutableListOf<Pair<SpeedBand?, List<LatLng>>>()
    var budget = MAX_TOTAL_POLYLINE_POINTS
    for (run in escalatedRuns) {
        if (budget < 2) break
        val (band, points) = run
        if (points.size <= budget) {
            truncated.add(run)
            budget -= points.size
        } else {
            truncated.add(band to points.take(budget))
            budget = 0
        }
    }
    return truncated
}

private fun List<RoutePolyline>.toBandRuns(toleranceMeters: Double, ranges: SpeedRanges): List<Pair<SpeedBand?, List<LatLng>>> =
    flatMap { it.points.toSimplifiedBandRuns(toleranceMeters, ranges) }

/** Un tramo de línea consecutivo de una sola banda de velocidad (null = velocidad desconocida). */
internal data class BandRun(val band: SpeedBand?, val points: List<RoutePoint>)

/**
 * Agrupa puntos consecutivos de la misma banda de velocidad. La velocidad de cada tramo de línea es
 * la del punto de LLEGADA: cada tramo nuevo (salvo el primero) arranca repitiendo el último punto del
 * tramo anterior, así el segmento que cruza el límite entre bandas toma el color del punto al que
 * llega y la línea no queda con un hueco visible donde cambia el color. Los tramos de un solo punto
 * (típicamente el primero) no son geometría válida para un Polyline y los descarta quien dibuja.
 */
internal fun List<RoutePoint>.groupByBand(ranges: SpeedRanges): List<BandRun> {
    val runs = mutableListOf<Pair<SpeedBand?, MutableList<RoutePoint>>>()
    for (point in this) {
        val band = ranges.bandFor(point.speedKph)
        val previousRun = runs.lastOrNull()
        if (previousRun != null && previousRun.first == band) {
            previousRun.second.add(point)
        } else {
            runs.add(band to mutableListOf<RoutePoint>().apply {
                if (previousRun != null) add(previousRun.second.last())
                add(point)
            })
        }
    }
    return runs.map { BandRun(it.first, it.second) }
}

/**
 * Agrupa por banda ([groupByBand]) y simplifica la geometría de cada tramo con Douglas-Peucker
 * (PolyUtil.simplify, de android-maps-utils -- ya dependencia del proyecto), devolviendo banda +
 * lista de LatLng por tramo, listo para un solo Polyline(). Douglas-Peucker siempre conserva los
 * extremos de la lista que recibe, así que el punto compartido entre tramos sobrevive la
 * simplificación en ambos lados.
 */
private fun List<RoutePoint>.toSimplifiedBandRuns(toleranceMeters: Double, ranges: SpeedRanges): List<Pair<SpeedBand?, List<LatLng>>> {
    if (size < 2) return emptyList()
    return groupByBand(ranges).mapNotNull { run ->
        if (run.points.size < 2) return@mapNotNull null
        run.band to PolyUtil.simplify(run.points.map { it.point.toLatLng() }, toleranceMeters)
    }
}

/**
 * Tramos por banda SIN simplificar, para el tramo provisional en vivo (son pocos puntos, igual que la
 * cola de la unidad).
 */
internal fun List<RoutePoint>.toBandRunsLatLng(ranges: SpeedRanges): List<Pair<SpeedBand?, List<LatLng>>> =
    groupByBand(ranges)
        .filter { it.points.size >= 2 }
        .map { run -> run.band to run.points.map { it.point.toLatLng() } }

private fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lng)

/** Color fijo de la banda (tema); null = velocidad desconocida, gris neutro. */
internal fun SpeedBand?.toRouteColor(colors: SoltelematicColors): Color =
    if (this == null) NEUTRAL_POLYLINE_COLOR else colors.speedColor(this)

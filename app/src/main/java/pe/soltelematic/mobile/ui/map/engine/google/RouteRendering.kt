package pe.soltelematic.mobile.ui.map.engine.google

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import kotlin.math.roundToInt
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerRole
import pe.soltelematic.mobile.ui.map.engine.RoutePoint
import pe.soltelematic.mobile.ui.map.engine.RoutePolyline

// Dibujado de rutas compartido por GoogleRouteMapEngine (Historial) y GoogleMapEngine (modo
// "Recorrido de hoy" del mapa principal). Extraído SIN cambios de lógica de GoogleRouteMapEngine.

internal const val POLYLINE_WIDTH_PX = 10f
internal const val SELECTED_POLYLINE_WIDTH_PX = 16f
internal const val DEFAULT_POLYLINE_COLOR = "#757575" // gris neutro: positions.c ausente

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

/** Colores de marca ya resueltos a Int (android.graphics.Color) -- ver buildRouteMarkerBitmap. */
internal data class RouteMarkerPalette(
    val statusMoving: Int,
    val onSurface: Int,
    val surface: Int,
    val outline: Int,
    val primary: Int
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
        // Inicio: círculo relleno statusMoving -- mismo verde que "unidad en movimiento" en el
        // resto de la app (ver StatusPill en SummaryTab.kt).
        RouteMarkerRole.ROUTE_START -> buildRouteMarkerBitmap(
            density = density,
            selected = selected,
            fillColor = palette.statusMoving,
            strokeColor = null,
            innerDotColor = null,
            selectionRingColor = palette.primary
        )
        // Fin: círculo relleno ink -- neutro a propósito, para no competir con el verde de inicio
        // ni con el ámbar/rojo de otros estados de la unidad.
        RouteMarkerRole.ROUTE_END -> buildRouteMarkerBitmap(
            density = density,
            selected = selected,
            fillColor = palette.onSurface,
            strokeColor = null,
            innerDotColor = null,
            selectionRingColor = palette.primary
        )
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
 * Todo el recorrido no seleccionado, listo para dibujar: agrupado por color consecutivo y
 * simplificado, con presupuesto de puntos global (no por tramo -- lo que importa para memoria es
 * el total de objetos Polyline de la pantalla, no cuántos le tocan a cada viaje).
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
internal fun List<RoutePolyline>.toRenderablePolylineRuns(): List<Pair<String?, List<LatLng>>> {
    val baseRuns = toColorRuns(BASE_SIMPLIFY_TOLERANCE_METERS)
    if (baseRuns.sumOf { it.second.size } <= MAX_TOTAL_POLYLINE_POINTS) return baseRuns

    val escalatedRuns = toColorRuns(ESCALATED_SIMPLIFY_TOLERANCE_METERS)
    if (escalatedRuns.sumOf { it.second.size } <= MAX_TOTAL_POLYLINE_POINTS) return escalatedRuns

    val truncated = mutableListOf<Pair<String?, List<LatLng>>>()
    var budget = MAX_TOTAL_POLYLINE_POINTS
    for (run in escalatedRuns) {
        if (budget < 2) break
        val (colorHex, points) = run
        if (points.size <= budget) {
            truncated.add(run)
            budget -= points.size
        } else {
            truncated.add(colorHex to points.take(budget))
            budget = 0
        }
    }
    return truncated
}

private fun List<RoutePolyline>.toColorRuns(toleranceMeters: Double): List<Pair<String?, List<LatLng>>> =
    flatMap { it.points.toSimplifiedColorRuns(toleranceMeters) }

/**
 * Agrupa puntos consecutivos del mismo color (positions[].c) en tramos y simplifica la geometría
 * de cada uno con Douglas-Peucker (PolyUtil.simplify, de android-maps-utils -- ya dependencia del
 * proyecto), devolviendo un color + lista de LatLng por tramo, listo para un solo Polyline().
 *
 * Cada tramo nuevo (salvo el primero) arranca repitiendo el último punto del tramo anterior:
 * Douglas-Peucker siempre conserva los extremos de la lista que recibe, así que ese punto
 * compartido sobrevive la simplificación en ambos tramos y la línea no queda con un hueco visible
 * donde cambia el color. Un tramo que queda en menos de 2 puntos tras esto se descarta -- no es
 * geometría válida para un Polyline, y el punto ya quedó representado como extremo del tramo
 * vecino.
 */
private fun List<RoutePoint>.toSimplifiedColorRuns(toleranceMeters: Double): List<Pair<String?, List<LatLng>>> {
    if (size < 2) return emptyList()

    val runs = mutableListOf<MutableList<RoutePoint>>()
    for (point in this) {
        val previousRun = runs.lastOrNull()
        if (previousRun != null && previousRun.last().colorHex == point.colorHex) {
            previousRun.add(point)
        } else {
            runs.add(mutableListOf<RoutePoint>().apply {
                if (previousRun != null) add(previousRun.last())
                add(point)
            })
        }
    }

    return runs.mapNotNull { run ->
        if (run.size < 2) return@mapNotNull null
        val simplified = PolyUtil.simplify(run.map { it.point.toLatLng() }, toleranceMeters)
        run.first().colorHex to simplified
    }
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lng)

internal fun String.toComposeColor(): Color =
    runCatching { Color(AndroidColor.parseColor(this)) }.getOrDefault(Color(AndroidColor.parseColor(DEFAULT_POLYLINE_COLOR)))

package pe.soltelematic.mobile.ui.map.engine.google

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.ImageLoader
import coil.request.ImageRequest
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType as GoogleMapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlin.math.roundToInt
import pe.soltelematic.mobile.domain.model.AssetIcon
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.ui.map.engine.MapCameraController
import pe.soltelematic.mobile.ui.map.engine.RouteMapEngine
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerData
import pe.soltelematic.mobile.ui.map.engine.RoutePolyline
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors

// Marcador de reproducción con icono real (ver Content, casos A/B/C del rumbo). Más grande que los
// pines de parada: acá es el foco visual de la pantalla mientras se reproduce, no un punto de
// referencia entre varios.
private const val UNIT_ICON_DIAMETER_DP = 36

// Caso C (sin icono propio) usa el mismo diámetro que tenía el círculo plano que reemplaza --
// mismo espacio en pantalla, solo cambia la forma de círculo a flecha.
private const val STANDALONE_ARROW_DIAMETER_DP = MARKER_SELECTED_DIAMETER_DP

// Caso B: flecha chica "aparte" del PNG fijo (ver comentario de RouteMapEngine.Content) -- deja
// ver el pin completo, la flecha es solo el indicador de rumbo.
private const val BEARING_BADGE_DIAMETER_DP = 16

// Ancla de la flecha chica del caso B: valor bajo en ambos ejes para que su esquina superior
// izquierda quede cerca del centro del pin (mismo punto GPS) y el resto sobresalga hacia abajo a
// la derecha, como una insignia -- no se pudo confirmar el resultado visual exacto contra el SDK
// real (ver nota de HistoryRouteMapPreview sobre el renderer de preview), ajustar si hace falta.
private val BEARING_BADGE_ANCHOR = Offset(0.15f, 0.15f)

/**
 * Sin ClusterManager ni MarkerIconCache a propósito: a diferencia de GoogleMapEngine (200+
 * unidades, refrescos frecuentes por polling), acá se pinta una vez por apertura de pantalla y
 * el conteo de marcadores es chico (paradas de un día). Markers/Polyline declarativos de
 * maps-compose alcanzan sin el costo de mantenerlos -- no hay el problema de rendimiento que
 * motivó el enfoque imperativo del Bloque 7.
 *
 * Pines circulares propios vía Canvas (ver buildRouteMarkerBitmap), no bitmaps por URL: a
 * diferencia de los marcadores de unidad (con icono real por modelo de equipo), acá no hay un
 * asset visual que resolver, solo 6 combinaciones fijas de color -- suficiente para dibujarlas
 * directo sin pasar por MarkerIconCache ni por carga de imagen.
 *
 * El marcador de reproducción es la excepción: ahí sí hay un PNG real que cargar (el icono de la
 * unidad, ver Content) -- por eso, a diferencia del resto de esta clase, recibe un ImageLoader por
 * constructor. Es el mismo ImageLoader de Koin que usa MarkerIconCache en el mapa en vivo (mismo
 * caché de Coil, un PNG ya descargado ahí no se vuelve a pedir por red acá), pero sin un cache
 * propio de BitmapDescriptor por URL como el de MarkerIconCache: esta pantalla resuelve como mucho
 * una URL (la unidad del historial que se está viendo), no cientos, así que remember(url) sobre la
 * composición alcanza sin ese mecanismo.
 */
class GoogleRouteMapEngine(private val imageLoader: ImageLoader) : RouteMapEngine {

    @Composable
    override fun rememberCameraController(): MapCameraController {
        val cameraPositionState = rememberCameraPositionState()
        val scope = rememberCoroutineScope()
        return GoogleMapCameraController(cameraPositionState, scope)
    }

    @Composable
    override fun Content(
        modifier: Modifier,
        cameraController: MapCameraController,
        polylines: List<RoutePolyline>,
        markers: List<RouteMarkerData>,
        selectedLegIndex: Int?,
        onMarkerClick: (Int) -> Unit,
        playbackPoint: GeoPoint?,
        playbackBearing: Float,
        unitIcon: AssetIcon?,
        onCameraGesture: () -> Unit,
        mapType: MapType
    ) {
        // Casteo seguro: el único MapCameraController que existe hoy para este contrato es el
        // que devuelve rememberCameraController() de esta misma clase.
        val googleController = cameraController as GoogleMapCameraController

        val density = LocalDensity.current.density
        val statusMovingArgb = LocalSoltelematicColors.current.statusMoving.toArgb()
        val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
        val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
        val outlineArgb = MaterialTheme.colorScheme.outline.toArgb()
        val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
        val markerIcons = remember(density, statusMovingArgb, onSurfaceArgb, surfaceArgb, outlineArgb, primaryArgb) {
            buildRouteMarkerIcons(
                density = density,
                palette = RouteMarkerPalette(
                    statusMoving = statusMovingArgb,
                    onSurface = onSurfaceArgb,
                    surface = surfaceArgb,
                    outline = outlineArgb,
                    primary = primaryArgb
                )
            )
        }
        // Flecha primary (caso C: la unidad no tiene icono propio, o su PNG todavía no cargó) --
        // reemplaza al círculo plano que había antes: un círculo no comunica hacia dónde apunta la
        // unidad, la flecha sí, rotada con playbackBearing más abajo. Dibujada apuntando al norte
        // (0°) una sola vez, igual criterio que buildRouteMarkerBitmap -- la rotación real se
        // aplica después vía el parámetro rotation de Marker(), nunca regenerando el bitmap.
        val standaloneArrowIcon = remember(density, primaryArgb) {
            buildBearingArrowBitmap(density = density, diameterDp = STANDALONE_ARROW_DIAMETER_DP, color = primaryArgb)
        }
        // Flecha chica del caso B (icono tipo "icon"/pin, ver AssetIcon.courseDegrees): mismo
        // dibujo que la de arriba, más chica, para no tapar el PNG de la unidad.
        val bearingBadgeIcon = remember(density, primaryArgb) {
            buildBearingArrowBitmap(density = density, diameterDp = BEARING_BADGE_DIAMETER_DP, color = primaryArgb)
        }

        // PNG real de la unidad (casos A/B) -- se resuelve de forma asíncrona con el mismo
        // ImageLoader/caché de Coil que ya usa MarkerIconCache en el mapa en vivo, así que un icono
        // ya descargado ahí no vuelve a pedirse por red. produceState(key1 = url) relanza la carga
        // solo si la URL cambia (nunca en cada tick de reproducción, que no toca unitIcon), y se
        // resetea a null mientras tanto -- ver el fallback a standaloneArrowIcon más abajo para
        // ese hueco. remember por URL en vez de un cache tipo MarkerIconCache: acá se resuelve como
        // mucho una unidad a la vez (la del historial abierto), no cientos.
        val context = LocalContext.current
        val unitIconSizePx = (UNIT_ICON_DIAMETER_DP * density).roundToInt()
        val unitIconDescriptorState by produceState<BitmapDescriptor?>(initialValue = null, unitIcon?.url, unitIconSizePx) {
            value = unitIcon?.url?.let { url -> loadUnitIconDescriptor(context, imageLoader, url, unitIconSizePx) }
        }

        // GESTURE es el único reason que dispara esto (centerOn/moveInstantly del propio engine
        // caen en API_ANIMATION/DEVELOPER_ANIMATION, ver MapCameraController.moveInstantly) -- así
        // que solo un arrastre real del usuario suelta el seguimiento de cámara en HistoryScreen.
        LaunchedEffect(googleController.cameraPositionState.cameraMoveStartedReason) {
            if (googleController.cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
                onCameraGesture()
            }
        }

        // remember(polylines, selectedLegIndex), NO dentro del content lambda de GoogleMap: ese
        // lambda es un solo scope de recomposición, y playbackPoint (más abajo, para el marcador
        // de reproducción) cambia varias veces por segundo durante la reproducción. Sin este
        // remember, cada actualización del marcador volvía a correr Douglas-Peucker
        // (toRenderablePolylineRuns) sobre TODO el recorrido -- el costo real detrás del jank
        // medido a velocidades altas, no la cámara ni el propio marcador.
        val selectedPolyline = remember(polylines, selectedLegIndex) {
            polylines.firstOrNull { it.legIndex == selectedLegIndex }
        }
        val renderableRuns = remember(polylines, selectedLegIndex) {
            polylines.filter { it.legIndex != selectedLegIndex }.toRenderablePolylineRuns()
        }

        GoogleMap(
            modifier = modifier,
            cameraPositionState = googleController.cameraPositionState,
            properties = MapProperties(mapType = mapType.toGoogleMapType()),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false)
        ) {
            // Tramo seleccionado aparte, siempre a fidelidad completa (nunca pasa por el
            // presupuesto de puntos ni por simplify): es un solo Polyline, no miles, así que no
            // hay riesgo de memoria, y "resaltar el tramo completo" pierde sentido si se recorta.
            selectedPolyline?.let { selected ->
                Polyline(
                    points = selected.points.map { it.point.toLatLng() },
                    color = MaterialTheme.colorScheme.primary,
                    width = SELECTED_POLYLINE_WIDTH_PX,
                    zIndex = 1f,
                    clickable = false
                )
            }

            // El resto del recorrido sí pasa por el presupuesto de puntos + simplify -- acá es
            // donde vivían los miles de Polyline que crasheaban la app.
            renderableRuns.forEach { (colorHex, points) ->
                Polyline(
                    points = points,
                    color = (colorHex ?: DEFAULT_POLYLINE_COLOR).toComposeColor(),
                    width = POLYLINE_WIDTH_PX,
                    clickable = false
                )
            }

            markers.forEach { marker ->
                val isSelected = marker.legIndex == selectedLegIndex
                Marker(
                    state = rememberMarkerState(position = marker.position.toLatLng()),
                    icon = markerIcons.getValue(marker.role to isSelected),
                    zIndex = if (isSelected) 1f else 0f,
                    // Contrato del engine: nunca popup. onClick consumido (true) evita que el
                    // SDK abra el InfoWindow por defecto.
                    onClick = {
                        onMarkerClick(marker.legIndex)
                        true
                    }
                )
            }

            // MarkerState.position es mutableStateOf (ver maps-compose) -- mutarlo directo mueve
            // el Marker nativo sin quitarlo/agregarlo de nuevo, a diferencia de recrear
            // rememberMarkerState(position=...) en cada tick (ese overload solo lee position en
            // la creación). rememberMarkerState() se llama siempre, fuera del if, para que el
            // slot de Compose sea estable entre reproducción activa e inactiva -- mismo criterio
            // para bearingBadgeMarkerState (caso B), aunque su Marker() solo se agregue a veces.
            val playbackMarkerState = rememberMarkerState()
            val bearingBadgeMarkerState = rememberMarkerState()
            if (playbackPoint != null) {
                SideEffect {
                    playbackMarkerState.position = playbackPoint.toLatLng()
                    bearingBadgeMarkerState.position = playbackPoint.toLatLng()
                }
                // Copia a un val local: unitIconDescriptorState es la propiedad delegada de
                // produceState, cada lectura vuelve a consultar el State -- copiarla una vez
                // garantiza que hasUnitIcon (abajo) y el icon= del Marker más abajo vean
                // exactamente el mismo valor, sin una ventana rarísima donde el productor
                // actualice el State entre ambas lecturas dentro de esta misma composición.
                val unitIconDescriptor = unitIconDescriptorState
                val hasUnitIcon = unitIcon?.url != null && unitIconDescriptor != null
                // AssetIcon.courseDegrees != null es la señal del servidor de "este icono rota"
                // (tipo "rotating" en device_icons) -- ver el comentario de esa propiedad.
                val unitIconRotates = unitIcon?.courseDegrees != null
                if (hasUnitIcon) {
                    // Caso A o B: PNG real de la unidad. rotation es un parámetro normal de
                    // Marker() (maps-compose), no de MarkerState -- pero según el propio código de
                    // la librería (Marker.kt: update(rotation) { marker.rotation = it }) cambiarlo
                    // en cada recomposición solo llama a marker.rotation = valor sobre el Marker
                    // nativo ya existente, nunca lo recrea. Es el mismo costo bajo que ya paga la
                    // mutación de position de arriba, no una regresión al jank que motivó ese
                    // patrón. flat=true solo cuando SÍ rota: así el ángulo (calculado en grados
                    // respecto al norte real) se mantiene correcto si el usuario rota el mapa con
                    // dos dedos (rotationGesturesEnabled por defecto, ver MapUiSettings) -- un
                    // marcador no-flat siempre mira de frente a la cámara sin importar el rumbo.
                    Marker(
                        state = playbackMarkerState,
                        icon = unitIconDescriptor,
                        rotation = if (unitIconRotates) playbackBearing else 0f,
                        flat = unitIconRotates,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 2f,
                        onClick = { true }
                    )
                    if (!unitIconRotates) {
                        // Caso B: el PNG es un pin de gota que nunca rota (ver
                        // AssetIcon.courseDegrees) -- esta flecha aparte es la única que gira, para
                        // que el giro se perciba igual que en el caso A.
                        Marker(
                            state = bearingBadgeMarkerState,
                            icon = bearingBadgeIcon,
                            rotation = playbackBearing,
                            flat = true,
                            anchor = BEARING_BADGE_ANCHOR,
                            zIndex = 3f,
                            onClick = { true }
                        )
                    }
                } else {
                    // Caso C: sin icono propio, o su PNG todavía no terminó de cargar (mismo
                    // tratamiento visual mientras tanto -- se corrige solo en cuanto
                    // unitIconDescriptor deja de ser null).
                    Marker(
                        state = playbackMarkerState,
                        icon = standaloneArrowIcon,
                        rotation = playbackBearing,
                        flat = true,
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 2f,
                        onClick = { true }
                    )
                }
            }
        }
    }
}

/**
 * Dibuja una flecha a mano (Canvas) apuntando al norte (0°), mismo criterio que
 * buildRouteMarkerBitmap: se genera una sola vez por (densidad, color) y se cachea vía remember --
 * el giro real hacia playbackBearing se aplica después con el parámetro rotation de Marker(),
 * nunca redibujando el bitmap. La usan los casos B (flecha chica junto al PNG fijo) y C (flecha
 * sola, reemplaza al círculo plano que no comunicaba dirección).
 */
private fun buildBearingArrowBitmap(density: Float, diameterDp: Int, color: Int): BitmapDescriptor {
    val sizePx = (diameterDp * density).roundToInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val size = sizePx.toFloat()
    // Punta arriba, "cola" hendida (dos picos traseros) -- silueta de flecha de rumbo estándar,
    // no un triángulo simple: se distingue de los pines circulares del resto del mapa a simple
    // vista incluso a este tamaño chico.
    val path = Path().apply {
        moveTo(size / 2f, 0f)
        lineTo(size, size)
        lineTo(size / 2f, size * 0.72f)
        lineTo(0f, size)
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    })
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/**
 * Carga el PNG de la unidad (casos A/B) con el ImageLoader/caché de Coil de Koin -- mismo request
 * que MarkerIconCache.loadIconBitmap en el mapa en vivo. allowHardware(false): un hardware bitmap
 * no se puede recortar/escalar con Canvas. Devuelve null si la carga falla (sin conexión, URL
 * rota, etc.) -- el caller cae al caso C (flecha sola) mientras tanto, nunca deja el marcador sin
 * dibujar.
 */
private suspend fun loadUnitIconDescriptor(context: Context, imageLoader: ImageLoader, url: String, sizePx: Int): BitmapDescriptor? {
    val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
    val drawable = imageLoader.execute(request).drawable ?: return null
    val bitmap = Bitmap.createScaledBitmap(drawable.toBitmap(), sizePx, sizePx, true)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lng)

private fun MapType.toGoogleMapType(): GoogleMapType = when (this) {
    MapType.NORMAL -> GoogleMapType.NORMAL
    MapType.SATELLITE -> GoogleMapType.SATELLITE
    MapType.HYBRID -> GoogleMapType.HYBRID
    MapType.TERRAIN -> GoogleMapType.TERRAIN
}


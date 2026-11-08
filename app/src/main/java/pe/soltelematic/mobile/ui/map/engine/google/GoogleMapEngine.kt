package pe.soltelematic.mobile.ui.map.engine.google

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Marker as MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.Geofence
import pe.soltelematic.mobile.domain.model.GeofenceShape
import pe.soltelematic.mobile.domain.model.MapType
import pe.soltelematic.mobile.ui.map.engine.GeofenceDraftPreview
import pe.soltelematic.mobile.ui.map.engine.MapCameraController
import pe.soltelematic.mobile.ui.map.engine.MapEngine
import pe.soltelematic.mobile.ui.map.engine.MapMarkerData
import pe.soltelematic.mobile.ui.map.engine.MapTailData
import pe.soltelematic.mobile.ui.map.engine.RouteOverlay
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors
import com.google.maps.android.compose.MapType as GoogleMapType

private const val GEOFENCE_STROKE_WIDTH_PX = 4f
private const val GEOFENCE_FILL_ALPHA_ACTIVE = 0.15f
private const val GEOFENCE_FILL_ALPHA_INACTIVE = 0.08f
private const val GEOFENCE_STROKE_ALPHA_ACTIVE = 1f
private const val GEOFENCE_STROKE_ALPHA_INACTIVE = 0.6f
private const val TAIL_STROKE_WIDTH_PX = 8f
private const val FALLBACK_GEOFENCE_COLOR = "#9E9E9E" // mismo gris neutro que MarkerIconCache
private val INACTIVE_GEOFENCE_STROKE_PATTERN: List<PatternItem> = listOf(Dash(20f), Gap(12f))

// Radio del punto que marca cada vértice/centro durante el dibujo de una geocerca -- ver
// GeofenceDraftPreview.Draw. No clicable (mismo criterio que Geofence.Draw): un punto tapando el
// toque siguiente rompería el flujo de "cada tap agrega un vértice".
private const val DRAFT_VERTEX_RADIUS_METERS = 3.0

// Metros por grado de latitud, constante en toda la Tierra (a diferencia de longitud, que
// depende de la latitud -- ver approxFootprintMeters). Suficiente para una estimación de tamaño,
// no para geometría real: solo se usa para decidir qué geocerca dibujar encima de cuál.
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

// Centro aproximado de Perú, con zoom amplio para que se vea el país completo. Posición inicial
// de la cámara mientras no hay datos: sin esto, CameraPositionState arranca en (0,0) -- frente a
// África, la posición por defecto de Google Maps -- y el salto al encuadre real de la flota es
// enorme y visible. Con esto el salto es corto (Perú -> zona real de la flota).
private val PERU_CENTER = LatLng(-9.19, -75.0152)
private const val PERU_INITIAL_ZOOM = 5f

// Alto que ocupa la atribución de Google Maps (logo + "Google", obligatoria, no se puede
// ocultar) en la esquina inferior. La dibuja el propio SDK, no es un composable de la app, así
// que no se puede medir con onSizeChanged como el resto de los overlays (ver MapScreen.kt) --
// de ahí que sea una constante y no una medición. Con margen generoso a propósito: un marcador
// justo al ras del logo se ve tan mal como uno tapado por él.
private val GOOGLE_ATTRIBUTION_RESERVED_HEIGHT = 40.dp

/**
 * Implementación sobre Google Maps SDK (maps-compose + maps-compose-utils para clustering).
 * Es la única clase del proyecto que puede importar de com.google.android.gms.maps o
 * com.google.maps.android.compose -- si algún día se migra a MapLibre, esta clase se
 * reemplaza entera y ui/map/MapScreen.kt no cambia una línea.
 *
 * El clustering se maneja imperativo (ClusterManager + AssetClusterRenderer vía MapEffect), no
 * con el Clustering(clusterItemContent = {...}) de maps-compose-utils: ese overload renderiza
 * cada marcador/cluster componiendo una vista Compose y rasterizándola a bitmap en el hilo
 * principal en cada reclusterización, que es el costo real medido con 200+ unidades (ver
 * investigación del Bloque 7). AssetClusterRenderer, en cambio, reutiliza BitmapDescriptor
 * cacheados por icono (ver MarkerIconCache) y deja que la librería reparta/reutilice los Marker
 * nativos como ya está optimizada para hacerlo.
 */
class GoogleMapEngine(private val iconCache: MarkerIconCache) : MapEngine {

    @Composable
    override fun rememberCameraController(): MapCameraController {
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(PERU_CENTER, PERU_INITIAL_ZOOM)
        }
        val scope = rememberCoroutineScope()
        return remember(cameraPositionState, scope) {
            GoogleMapCameraController(cameraPositionState, scope)
        }
    }

    @Composable
    override fun Content(
        modifier: Modifier,
        cameraController: MapCameraController,
        markers: List<MapMarkerData>,
        selectedMarkerId: Int?,
        myLocationEnabled: Boolean,
        geofences: List<Geofence>,
        onMarkerClick: (Int) -> Unit,
        onMapClick: (GeoPoint) -> Unit,
        contentPadding: PaddingValues,
        mapType: MapType,
        draft: GeofenceDraftPreview?,
        tail: MapTailData?,
        route: RouteOverlay?,
        onCameraGesture: () -> Unit
    ) {
        // Casteo seguro: el único MapCameraController que existe hoy es el que devuelve
        // rememberCameraController() de esta misma clase.
        val googleController = cameraController as GoogleMapCameraController
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val clusteringState = remember { ClusteringState() }
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current

        // Fondo/tinta de la píldora del marcador, resueltos acá (Compose tiene el tema) para
        // pasarlos a MarkerIconCache/AssetClusterRenderer, que no son @Composable. Se leen en cada
        // recomposición de Content -- MapEffect(markers) de abajo los usa cada vez que corre.
        val pillSurfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
        val pillInkArgb = MaterialTheme.colorScheme.onSurface.toArgb()
        val draftPreviewColor = MaterialTheme.colorScheme.primary

        // Recorrido de hoy (ver RouteOverlay): mismo dibujado que el Historial (RouteRendering.kt).
        // remember FUERA del content lambda de GoogleMap, por la misma razón que en
        // GoogleRouteMapEngine: ese lambda recompone con cada cambio de estado y Douglas-Peucker no
        // debe correr de nuevo si las polilíneas no cambiaron.
        val routeDensity = LocalDensity.current.density
        val routeStatusMovingArgb = LocalSoltelematicColors.current.statusMoving.toArgb()
        val routeOnSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
        val routeOutlineArgb = MaterialTheme.colorScheme.outline.toArgb()
        val hasRoute = route != null
        val routeMarkerIcons = remember(hasRoute, routeDensity, routeStatusMovingArgb, routeOnSurfaceArgb, pillSurfaceArgb, routeOutlineArgb, draftPreviewColor) {
            if (!hasRoute) {
                emptyMap()
            } else {
                buildRouteMarkerIcons(
                    density = routeDensity,
                    palette = RouteMarkerPalette(
                        statusMoving = routeStatusMovingArgb,
                        onSurface = routeOnSurfaceArgb,
                        surface = pillSurfaceArgb,
                        outline = routeOutlineArgb,
                        primary = draftPreviewColor.toArgb()
                    )
                )
            }
        }
        val routePolylines = route?.polylines
        val routeSelectedLeg = route?.selectedLegIndex
        // Igual que GoogleRouteMapEngine: el tramo resaltado va aparte y a fidelidad completa, el
        // resto pasa por el presupuesto de puntos.
        val routeSelectedPolyline = remember(routePolylines, routeSelectedLeg) {
            routePolylines?.firstOrNull { it.legIndex == routeSelectedLeg }
        }
        val provisionalPath = route?.provisionalPath
        val provisionalLatLngs = remember(provisionalPath) { provisionalPath?.map { it.toLatLng() }.orEmpty() }
        val provisionalColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        val routeRuns = remember(routePolylines, routeSelectedLeg) {
            routePolylines?.filter { it.legIndex != routeSelectedLeg }?.toRenderablePolylineRuns().orEmpty()
        }

        // contentPadding solo trae lo que MapScreen puede medir (barra+chips arriba, columna de
        // FABs a la derecha, ver MapScreen.kt) -- abajo hace falta sumar lo que le corresponde
        // a este motor concreto: la barra de navegación del sistema (safeDrawing, insets reales,
        // no una constante) más la atribución de Google (GOOGLE_ATTRIBUTION_RESERVED_HEIGHT,
        // constante porque el SDK la dibuja él mismo). Otro motor (p. ej. MapLibre) tendría su
        // propio cálculo acá, no en MapScreen.
        val systemNavigationBarHeight = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
        val effectiveContentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding() + systemNavigationBarHeight + GOOGLE_ATTRIBUTION_RESERVED_HEIGHT
        )

        // maps-compose ya registra el OnCameraMoveStartedListener del SDK y expone su motivo en
        // cameraMoveStartedReason: GESTURE solo para el usuario, las animaciones propias llegan como
        // DEVELOPER_ANIMATION. Un gesto que interrumpe una animación cambia el motivo con isMoving
        // ya en true, por eso se observa la combinación y no isMoving solo.
        //
        // Además exige un dedo sobre el mapa (TouchTracker): cambiar el padding al abrir/cerrar la
        // hoja mueve la cámara sin que nadie toque, y eso no puede contar como gesto aunque el SDK
        // lo etiquete así.
        val currentOnCameraGesture by rememberUpdatedState(onCameraGesture)
        val touchTracker = remember { TouchTracker() }
        LaunchedEffect(googleController) {
            val state = googleController.cameraPositionState
            snapshotFlow { state.isMoving && state.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE }
                .distinctUntilChanged()
                .filter { it && touchTracker.isTouchingRecently() }
                .collect { currentOnCameraGesture() }
        }

        SideEffect {
            with(density) {
                googleController.paddingPx = intArrayOf(
                    effectiveContentPadding.calculateLeftPadding(layoutDirection).roundToPx(),
                    effectiveContentPadding.calculateTopPadding().roundToPx(),
                    effectiveContentPadding.calculateRightPadding(layoutDirection).roundToPx(),
                    effectiveContentPadding.calculateBottomPadding().roundToPx()
                )
            }
        }

        GoogleMap(
            modifier = modifier.pointerInput(touchTracker) {
                // Pasada Initial y sin consumir: solo observa, el mapa recibe los toques intactos.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        touchTracker.onPointerEvent(event.changes.any { it.pressed })
                    }
                }
            },
            cameraPositionState = googleController.cameraPositionState,
            // myLocationEnabled solo debe llegar en true cuando quien llama ya confirmó el
            // permiso ACCESS_FINE_LOCATION; si no, el SDK de Google Maps lanza SecurityException.
            properties = MapProperties(isMyLocationEnabled = myLocationEnabled, mapType = mapType.toGoogleMapType()),
            // mapToolbarEnabled = false: sin los botones "cómo llegar" / "abrir en Google Maps" que
            // el SDK muestra al tocar un marcador.
            uiSettings = MapUiSettings(
                myLocationButtonEnabled = false,
                zoomControlsEnabled = false,
                mapToolbarEnabled = false
            ),
            // No es solo estético: el SDK usa este padding también para calcular el bounding box
            // visible en newLatLngBounds (ver GoogleMapCameraController.fitAll), así que un
            // encuadre automático ya no deja marcadores debajo de la barra de búsqueda/chips/FABs.
            contentPadding = effectiveContentPadding,
            onMapClick = { latLng -> onMapClick(latLng.toGeoPoint()) }
        ) {
            // Los marcadores de unidad se dibujan siempre por encima de los overlays de suelo
            // (polígonos, círculos) sin importar el zIndex -- son capas distintas en el SDK de
            // Google Maps. El zIndex de cada forma (ver approxFootprintMeters) solo ordena las
            // geocercas entre sí, para que una pequeña dentro de una grande no quede tapada.
            geofences.forEach { geofence ->
                geofence.Draw()
            }

            draft?.Draw(previewColor = draftPreviewColor)

            // Sin RouteSimplifier: son ~11 puntos. zIndex 0 queda por debajo del anillo (1f) y de
            // los marcadores.
            if (tail != null && tail.points.size >= 2) {
                Polyline(
                    points = tail.points.map { it.toLatLng() },
                    clickable = false,
                    color = Color(tail.colorArgb),
                    width = TAIL_STROKE_WIDTH_PX,
                    zIndex = 0f
                )
            }

            // Recorrido de hoy: por debajo de los marcadores de unidad (zIndex 0, como la cola) y
            // sin ninguna interacción -- el toque sobre una parada se consume para que el SDK no
            // abra su InfoWindow.
            routeSelectedPolyline?.let { selected ->
                Polyline(
                    points = selected.points.map { it.point.toLatLng() },
                    color = MaterialTheme.colorScheme.primary,
                    width = SELECTED_POLYLINE_WIDTH_PX,
                    zIndex = 0.5f,
                    clickable = false
                )
            }
            // Continuación en vivo: se recompone sola cuando cambia provisionalPath, sin tocar las
            // polilíneas simplificadas de arriba (memoizadas por identidad de la lista).
            if (provisionalLatLngs.size >= 2) {
                Polyline(
                    points = provisionalLatLngs,
                    color = provisionalColor,
                    width = POLYLINE_WIDTH_PX,
                    zIndex = 0.25f,
                    clickable = false
                )
            }
            routeRuns.forEach { (colorHex, points) ->
                Polyline(
                    points = points,
                    color = (colorHex ?: DEFAULT_POLYLINE_COLOR).toComposeColor(),
                    width = POLYLINE_WIDTH_PX,
                    clickable = false
                )
            }
            route?.markers?.forEach { marker ->
                val isSelected = marker.legIndex == routeSelectedLeg
                MarkerComposable(
                    state = rememberMarkerState(position = marker.position.toLatLng()),
                    icon = routeMarkerIcons.getValue(marker.role to isSelected),
                    onClick = { true }
                )
            }

            // Se dispara con cada refresco de Room (nueva lista de markers), nunca con cada
            // movimiento de cámara -- eso lo cubre el listener debounced de abajo, creado una
            // sola vez la primera vez que este efecto corre.
            MapEffect(Unit) { googleMap -> googleController.googleMap = googleMap }

            MapEffect(markers) { googleMap ->
                val manager = clusteringState.manager ?: run {
                    val newManager = ClusterManager<AssetClusterItem>(context, googleMap)
                    val renderer = AssetClusterRenderer(context, googleMap, newManager, iconCache)
                    newManager.renderer = renderer
                    clusteringState.renderer = renderer
                    // El anillo no está en ninguna colección del manager y MarkerOptions de este SDK no
                    // permite marcarlo no clicable: sin este filtro su toque caería al comportamiento
                    // por defecto de Google (centrar la cámara, info window). Se consume y, como tapa
                    // la píldora de su unidad, equivale a tocar esa unidad (vuelve a enfocarla).
                    googleMap.setOnMarkerClickListener { marker ->
                        if (marker == clusteringState.selectionRing) {
                            clusteringState.selectedId?.let(onMarkerClick)
                            true
                        } else {
                            newManager.onMarkerClick(marker)
                        }
                    }
                    // El anillo comparte posición con el marcador que el renderer acaba de colocar,
                    // no solo con el dato: nunca se separan aunque el renderer lo ubique distinto.
                    renderer.onItemMarkerPlaced = { item, marker ->
                        if (item.markerData.id == clusteringState.selectedId) {
                            clusteringState.selectionRing?.position = marker.position
                        }
                    }
                    // Opción A del Bloque 7: coalesce varios onCameraIdle seguidos (zoom in/out
                    // en ráfaga) en una sola reclusterización.
                    googleMap.setOnCameraIdleListener(
                        DebouncedCameraIdleListener(scope) { newManager.onCameraIdle() }
                    )
                    newManager.setOnClusterItemClickListener { item ->
                        onMarkerClick(item.markerData.id)
                        true
                    }
                    clusteringState.manager = newManager
                    newManager
                }

                // El renderer se crea una sola vez (arriba); sus colores de píldora se refrescan
                // en cada corrida de este efecto, así un cambio de tema no deja bitmaps viejos.
                // let (no apply): apply expondría "this" como receptor implícito, y sus
                // propiedades pillSurfaceArgb/pillInkArgb tapan a las locales del mismo nombre.
                clusteringState.renderer?.let { renderer ->
                    renderer.pillSurfaceArgb = pillSurfaceArgb
                    renderer.pillInkArgb = pillInkArgb
                }

                // Se salta las claves ya cacheadas (ver MarkerIconCache), así que en un refresco
                // con las mismas unidades esto no vuelve a pedir red.
                iconCache.preload(markers, pillSurfaceArgb, pillInkArgb)
                val items = markers.map { AssetClusterItem(it) }
                manager.clearItems()
                manager.addItems(items)
                manager.cluster()

                // cluster() es asíncrono (ClusterTask -> RenderTask -> MarkerModifier) mientras que
                // el anillo se coloca en el acto: sin esto el anillo llegaba primero y el marcador
                // después -- o tarde, si el reagrupado se pisaba con otro. Los marcadores de unidad
                // que ya están en pantalla se mueven aquí mismo, a la misma posición que el anillo;
                // el reagrupado solo decide agrupar/desagrupar. Las unidades dentro de un cluster no
                // tienen marcador propio (getMarker == null) y se saltan. Solo se toca el SDK
                // cuando la posición cambió realmente.
                val renderer = clusteringState.renderer
                if (renderer != null) {
                    items.forEach { item ->
                        val marker = renderer.getMarker(item) ?: return@forEach
                        if (marker.position != item.position) marker.position = item.position
                    }
                }
            }

            // Estado "seleccionado": un Marker de anillo superpuesto en vez de una variante de
            // icono por estado -- a lo sumo una unidad seleccionada a la vez, no vale la pena
            // duplicar el caché de bitmaps por eso.
            MapEffect(selectedMarkerId, markers) { googleMap ->
                clusteringState.selectionRing?.remove()
                clusteringState.selectionRing = null
                clusteringState.selectedId = selectedMarkerId
                val selected = selectedMarkerId?.let { id -> markers.firstOrNull { it.id == id } }
                if (selected != null) {
                    clusteringState.selectionRing = googleMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(selected.position.lat, selected.position.lng))
                            .icon(iconCache.selectionRingDescriptor)
                            .anchor(0.5f, 0.5f)
                            .zIndex(1f)
                            .flat(true)
                    )
                }
            }
        }
    }
}

/**
 * Si hay (o acaba de haber) un dedo sobre el mapa. La holgura cubre que el SDK avise del inicio del
 * movimiento un instante después de que el evento táctil ya pasó por Compose.
 */
private class TouchTracker {
    private var pressed = false
    private var lastReleaseMs = 0L

    fun onPointerEvent(anyPressed: Boolean) {
        if (pressed && !anyPressed) lastReleaseMs = SystemClock.uptimeMillis()
        pressed = anyPressed
    }

    fun isTouchingRecently(): Boolean =
        pressed || SystemClock.uptimeMillis() - lastReleaseMs <= TOUCH_GRACE_MS
}

private const val TOUCH_GRACE_MS = 300L

/** Sobrevive a recomposiciones (remember), no a cambios de configuración: no hace falta más. */
private class ClusteringState {
    var manager: ClusterManager<AssetClusterItem>? = null
    var renderer: AssetClusterRenderer? = null
    var selectionRing: Marker? = null
    var selectedId: Int? = null
}

/**
 * Nunca clicable (contrato de MapEngine): competiría con el toque para seleccionar unidades.
 * Sin etiqueta de nombre (decisión de producto): con varias geocercas superpuestas en pantalla
 * chica se amontonan. Inactiva (active == false) se dibuja atenuada -- relleno y contorno con
 * menos alpha, contorno punteado -- para que siga siendo referencia visual sin parecer que vigila.
 */
@Composable
@GoogleMapComposable
private fun Geofence.Draw() {
    val fillAlpha = if (active) GEOFENCE_FILL_ALPHA_ACTIVE else GEOFENCE_FILL_ALPHA_INACTIVE
    val strokeAlpha = if (active) GEOFENCE_STROKE_ALPHA_ACTIVE else GEOFENCE_STROKE_ALPHA_INACTIVE
    val strokePattern = if (active) null else INACTIVE_GEOFENCE_STROKE_PATTERN
    // Negativo: cuanto más grande la forma, más al fondo. Así una geocerca chica dentro de una
    // grande (radios reales de 1315.95 m y 563.48 m, ver Sprint 5) no queda tapada por el relleno
    // de la que la contiene.
    val zIndex = -shape.approxFootprintMeters().toFloat()

    when (val shape = shape) {
        is GeofenceShape.Polygon -> Polygon(
            points = shape.vertices.map { it.toLatLng() },
            clickable = false,
            fillColor = colorHex.toGeofenceColor(fillAlpha),
            strokeColor = colorHex.toGeofenceColor(strokeAlpha),
            strokePattern = strokePattern,
            strokeWidth = GEOFENCE_STROKE_WIDTH_PX,
            zIndex = zIndex
        )
        is GeofenceShape.Circle -> Circle(
            center = shape.center.toLatLng(),
            radius = shape.radiusMeters,
            clickable = false,
            fillColor = colorHex.toGeofenceColor(fillAlpha),
            strokeColor = colorHex.toGeofenceColor(strokeAlpha),
            strokePattern = strokePattern,
            strokeWidth = GEOFENCE_STROKE_WIDTH_PX,
            zIndex = zIndex
        )
    }
}

/**
 * Vista previa de la geocerca en construcción (ver MapScreen). Color fijo (previewColor, el color
 * real se elige recién en el formulario final) y siempre por encima de las geocercas reales (sin
 * zIndex negativo) -- es lo que el usuario está tocando en este momento. Puntos no clicables, ver
 * DRAFT_VERTEX_RADIUS_METERS.
 */
@Composable
@GoogleMapComposable
private fun GeofenceDraftPreview.Draw(previewColor: Color) {
    when (this) {
        is GeofenceDraftPreview.Polygon -> {
            vertices.forEach { vertex ->
                Circle(
                    center = vertex.toLatLng(),
                    radius = DRAFT_VERTEX_RADIUS_METERS,
                    clickable = false,
                    fillColor = previewColor,
                    strokeColor = previewColor
                )
            }
            if (vertices.size >= 2) {
                Polyline(
                    points = vertices.map { it.toLatLng() },
                    clickable = false,
                    color = previewColor,
                    width = GEOFENCE_STROKE_WIDTH_PX
                )
            }
        }
        is GeofenceDraftPreview.Circle -> {
            center?.let { point ->
                Circle(
                    center = point.toLatLng(),
                    radius = radiusMeters,
                    clickable = false,
                    fillColor = previewColor.copy(alpha = GEOFENCE_FILL_ALPHA_ACTIVE),
                    strokeColor = previewColor,
                    strokeWidth = GEOFENCE_STROKE_WIDTH_PX
                )
                Circle(
                    center = point.toLatLng(),
                    radius = DRAFT_VERTEX_RADIUS_METERS,
                    clickable = false,
                    fillColor = previewColor,
                    strokeColor = previewColor
                )
            }
        }
    }
}

/**
 * Estimación de tamaño en metros, NO geometría real -- solo para ordenar zIndex (ver Draw()).
 * Circle ya trae su radio; Polygon usa la diagonal de su bounding box / 2, con longitud corregida
 * por coseno de la latitud (un grado de longitud encoge hacia los polos, uno de latitud no).
 */
private fun GeofenceShape.approxFootprintMeters(): Double = when (this) {
    is GeofenceShape.Circle -> radiusMeters
    is GeofenceShape.Polygon -> {
        val lats = vertices.map { it.lat }
        val lngs = vertices.map { it.lng }
        val latSpanMeters = (lats.max() - lats.min()) * METERS_PER_DEGREE_LATITUDE
        val lngSpanMeters = (lngs.max() - lngs.min()) * METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(lats.average()))
        hypot(latSpanMeters, lngSpanMeters) / 2
    }
}

private fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lng)

private fun LatLng.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

private fun MapType.toGoogleMapType(): GoogleMapType = when (this) {
    MapType.NORMAL -> GoogleMapType.NORMAL
    MapType.SATELLITE -> GoogleMapType.SATELLITE
    MapType.HYBRID -> GoogleMapType.HYBRID
    MapType.TERRAIN -> GoogleMapType.TERRAIN
}

private fun String.toGeofenceColor(alpha: Float): Color {
    val argb = runCatching { AndroidColor.parseColor(this) }
        .getOrDefault(AndroidColor.parseColor(FALLBACK_GEOFENCE_COLOR))
    return Color(argb).copy(alpha = alpha)
}

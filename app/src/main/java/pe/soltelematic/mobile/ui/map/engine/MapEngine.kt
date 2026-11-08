package pe.soltelematic.mobile.ui.map.engine

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.soltelematic.mobile.domain.model.Geofence
import pe.soltelematic.mobile.domain.model.GeoPoint
import pe.soltelematic.mobile.domain.model.MapType

/**
 * Vista mínima de un Asset para el motor de mapas. Deliberadamente no lleva velocidad,
 * ignición ni el resto de datos del bottom sheet: el motor solo necesita lo que hace falta
 * para dibujar y rotar un marcador.
 */
data class MapMarkerData(
    val id: Int,
    val position: GeoPoint,
    val title: String,
    /** PNG propio de la unidad (vehículo, maquinaria, candado...); null = sin ícono, ver iconColorHex. */
    val iconUrl: String?,
    // Color de estado ya resuelto (statusMoving/Idle/Alert/Offline según AssetStatusType, ver
    // MapScreen.kt) como ARGB de Android, no el colorHex crudo del servidor: la píldora usa el
    // mismo mapeo de 4 colores que el resto de la app, no la paleta libre del backend. Resuelto
    // en MapScreen.kt (tiene LocalSoltelematicColors) para que este archivo siga sin depender de
    // ningún proveedor de mapas.
    val statusColorArgb: Int,
    /** true cuando status.type == OFFLINE: la píldora completa (fondo, ícono y texto) se dibuja atenuada. */
    val dimmed: Boolean
)

/**
 * Cola de la unidad seleccionada, ya lista para dibujar: puntos de más antigua a más reciente
 * (el último ya es la posición del marcador) y color como ARGB resuelto en MapScreen.
 */
data class MapTailData(
    val points: List<GeoPoint>,
    val colorArgb: Int
)

/**
 * Recorrido de hoy de una unidad (modo "Recorrido de hoy" del mapa principal), ya listo para
 * dibujar: mismos tipos que usa el mapa del Historial (RoutePolyline/RouteMarkerData, ver
 * RouteMapEngine.kt), así que se reutiliza el dibujado de ruta en vez de duplicarlo.
 */
data class RouteOverlay(
    val polylines: List<RoutePolyline>,
    val markers: List<RouteMarkerData>,
    // Tramo resaltado (mismo legIndex que RoutePolyline/RouteMarkerData); null = ninguno.
    val selectedLegIndex: Int? = null
)

/**
 * Puente imperativo hacia la cámara del motor concreto. Los botones flotantes del mapa
 * (centrar en mi ubicación, ajustar zoom a todas) no son composables, así que necesitan un
 * objeto al que llamar desde un onClick normal en vez de una función @Composable.
 */
interface MapCameraController {
    fun centerOn(point: GeoPoint, zoomLevel: Float = DEFAULT_ZOOM)
    fun fitAll(points: List<GeoPoint>)

    // Encuadra los puntos dentro del área libre (respeta contentPadding) sin acercarse más que
    // DEFAULT_ZOOM -- el mismo tope que "Ver en mapa". Con un solo punto equivale a centerOn().
    fun frame(points: List<GeoPoint>)

    // Salto instantáneo, sin animación -- para seguir un marcador que se mueve varias veces por
    // segundo (reproducción de recorrido, ver HistoryScreen): centerOn() anima con
    // CameraUpdateFactory.newLatLngZoom, y encolar una animación nueva antes de que la anterior
    // termine (a 8x el marcador avanza cada ~25ms) es la causa típica de jank de cámara. Preserva
    // zoom/tilt/bearing actuales -- no fuerza el zoom que sí trae centerOn().
    fun moveInstantly(point: GeoPoint)

    // Seguimiento de una unidad que se mueve: anima (corto y suave) hasta centrar el punto dentro
    // del área libre (respeta contentPadding, con o sin hoja) conservando zoom/tilt/bearing -- a
    // diferencia de centerOn(), que fuerza un zoom. El encuadre de cola + unidad es frame(), solo
    // al enfocar; esto se llama en cada sondeo cuya posición cambió.
    fun followTo(point: GeoPoint)

    companion object {
        const val DEFAULT_ZOOM = 16f
    }
}

/**
 * Forma en construcción durante el modo dibujo de geocercas (ver MapScreen/MapViewModel) -- vive
 * acá, no en domain/model/GeofenceShape, porque tolera estados intermedios que una geocerca real
 * nunca tiene (polígono con 0-2 vértices, círculo sin centro todavía). El color final se elige
 * recién en el formulario, así que el engine la dibuja con un color fijo propio (ver
 * GoogleMapEngine).
 */
sealed interface GeofenceDraftPreview {
    data class Polygon(val vertices: List<GeoPoint>) : GeofenceDraftPreview
    data class Circle(val center: GeoPoint?, val radiusMeters: Double) : GeofenceDraftPreview
}

/**
 * Todo lo que una pantalla puede pedirle a un mapa, sin nombrar Google Maps ni ningún otro
 * proveedor. Migrar a MapLibre es escribir una implementación nueva de esta interfaz, no
 * tocar ui/map/MapScreen.kt.
 */
interface MapEngine {

    @Composable
    fun rememberCameraController(): MapCameraController

    @Composable
    fun Content(
        modifier: Modifier,
        cameraController: MapCameraController,
        markers: List<MapMarkerData>,
        selectedMarkerId: Int?,
        // Contrato: solo pasar true cuando ACCESS_FINE_LOCATION ya está concedido. El motor no
        // pide el permiso, confía en que quien llama (MapScreen) ya lo hizo.
        myLocationEnabled: Boolean,
        // Se reutiliza el modelo de dominio tal cual (como GeoPoint): ya es agnóstico de
        // proveedor de mapas y trae exactamente lo necesario para dibujar, sin recortar campos
        // como sí hace MapMarkerData respecto de Asset. Lista ya vacía si el interruptor de la
        // pantalla está apagado -- el motor no conoce esa preferencia, solo dibuja lo que recibe.
        // Nunca clicables (ver GoogleMapEngine): competirían con el toque para seleccionar unidades.
        geofences: List<Geofence>,
        onMarkerClick: (Int) -> Unit,
        // Entrega el punto del mapa que se tocó -- lo necesita el modo dibujo de geocercas
        // (agregar vértice / fijar centro). En modo normal (MapScreen) se ignora.
        onMapClick: (GeoPoint) -> Unit,
        // Espacio real ocupado por los overlays de MapScreen (barra de búsqueda + chips arriba,
        // columna de FABs a la derecha), medido en runtime, no un margen fijo. El motor lo usa
        // para que ni sus controles propios ni el encuadre (fitAll) queden debajo de esos overlays.
        contentPadding: PaddingValues,
        // NORMAL por defecto (ver MapUiState). El SDK concreto (GoogleMapEngine) lo traduce a su
        // propio enum de tipo de mapa -- este contrato no nombra Google Maps.
        mapType: MapType,
        // null = no está en modo dibujo. Sin valor por defecto a propósito (mismo motivo que
        // RouteMapEngine.Content.playbackPoint): un default en un miembro @Composable de una
        // interfaz no genera el bridge $default correctamente -- AbstractMethodError en runtime
        // al llamarlo a través del tipo de interfaz. Todo caller pasa este parámetro explícito.
        draft: GeofenceDraftPreview?,
        // Cola de la unidad seleccionada; null = nada que dibujar. Sin valor por defecto, mismo
        // motivo que draft. Siempre por debajo de los marcadores.
        tail: MapTailData?,
        // Recorrido de hoy de la unidad en modo "Recorrido de hoy"; null = fuera de ese modo. Sin
        // valor por defecto, mismo motivo que draft. Los marcadores de parada nunca abren popup ni
        // reportan toques.
        route: RouteOverlay?,
        // El usuario empezó a mover la cámara con un gesto (arrastrar, pellizcar, rotar, inclinar).
        // Las animaciones propias de la app (frame/centerOn/followTo/fitAll) NO lo disparan. Una
        // vez por gesto, no por frame. Sin valor por defecto, mismo motivo que draft.
        onCameraGesture: () -> Unit
    )
}

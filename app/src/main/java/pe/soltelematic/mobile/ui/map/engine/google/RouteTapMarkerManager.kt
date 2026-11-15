package pe.soltelematic.mobile.ui.map.engine.google

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker
import com.google.maps.android.collections.MarkerManager
import pe.soltelematic.mobile.ui.map.engine.RouteMarkerKey

/** Qué es un marcador tocado, según su tag. */
internal sealed interface MarkerTap {
    /** Marcador de ruta (parada, partida/llegada o evento): lleva su RouteMarkerKey como tag. */
    data class Route(val key: RouteMarkerKey) : MarkerTap

    /** Cualquier otro (unidades, clusters, anillo de selección): sigue el camino de siempre. */
    data object Other : MarkerTap
}

internal fun classifyMarkerTag(tag: Any?): MarkerTap =
    (tag as? RouteMarkerKey)?.let { MarkerTap.Route(it) } ?: MarkerTap.Other

/**
 * MarkerManager de ClusterManager con atención de los marcadores de ruta.
 *
 * Por qué existe: MarkerManager (android-maps-utils) es quien posee el listener de toques de marcadores
 * del mapa -- en su constructor (MapObjectManager) encola en el Handler del hilo principal un
 * map.setOnMarkerClickListener(this) --, y ese runnable corre después de cualquier
 * googleMap.setOnMarkerClickListener hecho justo tras crear el ClusterManager, que queda anulado sin
 * aviso. El listener de maps-compose (el onClick de cada Marker) tampoco sobrevive: ClusterManager lo
 * pisa igual. Sobrescribir onMarkerClick de ESTE manager no depende de quién instala qué ni cuándo: él
 * siempre es el listener activo.
 *
 * Los marcadores de ruta devuelven true (consumido): el SDK no centra la cámara por su cuenta, lo hace
 * la pantalla dejando libre el espacio de la tarjeta. Todo lo demás pasa a MarkerManager tal cual.
 */
internal class RouteTapMarkerManager(
    map: GoogleMap,
    private val onRouteMarkerClick: (RouteMarkerKey) -> Unit
) : MarkerManager(map) {

    override fun onMarkerClick(marker: Marker): Boolean =
        when (val tap = classifyMarkerTag(marker.tag)) {
            is MarkerTap.Route -> {
                onRouteMarkerClick(tap.key)
                true
            }
            MarkerTap.Other -> super.onMarkerClick(marker)
        }
}

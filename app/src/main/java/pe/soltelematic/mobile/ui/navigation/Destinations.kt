package pe.soltelematic.mobile.ui.navigation

import java.time.LocalDate

private const val ASSET_ID_ARG = "assetId"
private const val EVENT_ID_ARG = "eventId"
private const val ALERT_ID_ARG = "alertId"
private const val FROM_ARG = "from"
private const val DAY_ARG = "day"
private const val TO_ARG = "to"

/**
 * Login/Map/Account son rutas fijas sin argumentos. AssetDetail sí necesita uno (el id de la
 * unidad) -- placeholder de ruta clásico de Navigation Compose, no rutas @Serializable: para un
 * solo argumento entero no amerita migrar todo el grafo a rutas tipadas.
 */
sealed class Destination(val route: String) {
    data object Login : Destination("login")
    data object ForgotPassword : Destination("forgot_password")
    data object Map : Destination("map")
    data object Units : Destination("units")
    data object Account : Destination("account")
    data object Events : Destination("events")
    data object Reports : Destination("reports")
    // Pantalla hija de Alertas (se abre con el botón de ajustes de EventsScreen), no una pestaña: no
    // está en AppBottomBarRoutes, así que no muestra la barra inferior.
    data object ManageAlerts : Destination("alerts/manage")
    data object CreateAlert : Destination("alerts/create")
    // El mismo formulario de CreateAlert en modo edición: el id de la alerta viaja en la ruta.
    data object EditAlert : Destination("alerts/{$ALERT_ID_ARG}/edit") {
        const val ARG_ID = ALERT_ID_ARG
        fun createRoute(alertId: Int) = "alerts/$alertId/edit"
    }
    data object AssetDetail : Destination("asset/{$ASSET_ID_ARG}") {
        const val ARG_ID = ASSET_ID_ARG
        fun createRoute(assetId: Int) = "asset/$assetId"
    }
    // day (yyyy-MM-dd) es opcional: sin él Historial abre en "Hoy". Lo usa "Ver historial completo"
    // de la Última ruta para abrir el día que se estaba mostrando.
    data object History : Destination("asset/{$ASSET_ID_ARG}/history?$DAY_ARG={$DAY_ARG}") {
        const val ARG_ID = ASSET_ID_ARG
        const val ARG_DAY = DAY_ARG
        fun createRoute(assetId: Int, day: LocalDate? = null) =
            if (day == null) "asset/$assetId/history" else "asset/$assetId/history?$DAY_ARG=$day"
    }
    // from/to viajan en la ruta como LocalDate.toString() (yyyy-MM-dd, sin '/') -- mismo rango que
    // el usuario tenía elegido en Historial (ver HistoryScreen.onOpenParameterCharts), no un rango
    // propio de esta pantalla.
    data object ParameterCharts : Destination("asset/{$ASSET_ID_ARG}/parameters/{$FROM_ARG}/{$TO_ARG}") {
        const val ARG_ID = ASSET_ID_ARG
        const val ARG_FROM = FROM_ARG
        const val ARG_TO = TO_ARG
        fun createRoute(assetId: Int, from: LocalDate, to: LocalDate) = "asset/$assetId/parameters/$from/$to"
    }
    // El id de la ruta solo identifica la entrada del back stack -- no hay GET /events/{id} para
    // reconstruir el AlertEvent completo a partir de él (ver EventDao/EventsApi). Los datos viajan
    // por EventMapRequestBus desde el punto de click (ver EventCard/EventsScreen), que ya tiene el
    // evento completo en memoria.
    data object EventMap : Destination("event/{$EVENT_ID_ARG}/map") {
        const val ARG_ID = EVENT_ID_ARG
        fun createRoute(eventId: Int) = "event/$eventId/map"
    }
}

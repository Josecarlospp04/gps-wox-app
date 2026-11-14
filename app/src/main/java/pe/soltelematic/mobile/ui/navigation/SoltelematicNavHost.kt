package pe.soltelematic.mobile.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.koin.compose.koinInject
import pe.soltelematic.mobile.core.navigation.MapFocusRequestBus
import pe.soltelematic.mobile.core.network.AuthEventBus
import pe.soltelematic.mobile.core.network.UnseenEventsPoller
import pe.soltelematic.mobile.domain.repository.AuthRepository
import pe.soltelematic.mobile.ui.account.AccountScreen
import pe.soltelematic.mobile.ui.alerts.CreateAlertScreen
import pe.soltelematic.mobile.ui.alerts.ManageAlertsScreen
import pe.soltelematic.mobile.ui.assetdetail.AssetDetailScreen
import pe.soltelematic.mobile.ui.events.EventsScreen
import pe.soltelematic.mobile.ui.eventmap.EventMapScreen
import pe.soltelematic.mobile.ui.forgot.ForgotPasswordScreen
import pe.soltelematic.mobile.ui.history.HistoryScreen
import pe.soltelematic.mobile.ui.login.LoginScreen
import pe.soltelematic.mobile.ui.map.MapScreen
import pe.soltelematic.mobile.ui.parameters.ParameterChartsScreen
import pe.soltelematic.mobile.ui.reports.ReportsScreen
import pe.soltelematic.mobile.ui.units.UnitsScreen
import java.time.LocalDate

/**
 * Scaffold propio SOLO para alojar la barra de navegación inferior (Bloque de rediseño del mapa) --
 * no reemplaza el Scaffold/TopAppBar interno de cada pantalla (Events/Account ya tienen el suyo,
 * Map sigue siendo un Box a pantalla completa). contentWindowInsets = 0 a propósito: este Scaffold
 * no debe pelear con los insets que cada pantalla ya maneja por su cuenta (barra de estado arriba,
 * por ejemplo) -- solo reserva el espacio real que ocupa la barra inferior, que a su vez ya se
 * encarga sola del inset de la barra de navegación del sistema (ver AppBottomBar, NavigationBar
 * de Material3 con su windowInsets por defecto). consumeWindowInsets() en el NavHost evita que
 * Map vuelva a sumar ese mismo inset de abajo por su cuenta (ver windowInsetsPadding en
 * MapScreen.kt) -- sin esto, la fila de FABs quedaría con doble margen bajo la barra nueva.
 */
// Clave del savedStateHandle de "Gestionar alertas" con la que CreateAlert pide refrescar la lista.
private const val ALERTS_CHANGED_KEY = "alerts_changed"

@Composable
fun SoltelematicNavHost(
    navController: NavHostController = rememberNavController(),
    authRepository: AuthRepository = koinInject(),
    authEventBus: AuthEventBus = koinInject(),
    unseenEventsPoller: UnseenEventsPoller = koinInject(),
    mapFocusRequestBus: MapFocusRequestBus = koinInject()
) {
    val startDestination = if (authRepository.hasStoredSession()) {
        Destination.Map.route
    } else {
        Destination.Login.route
    }

    // Si el TokenAuthenticator agota el refresh y cierra la sesión mientras el usuario
    // está en Mapa, esto lo regresa a Login limpiando todo el back stack.
    LaunchedEffect(Unit) {
        authEventBus.sessionExpired.collect {
            navController.navigate(Destination.Login.route) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val unseenEventsCount by unseenEventsPoller.unseenCount.collectAsState()

    // Alertas es una de las 4 pestañas, pero también se llega a ella desde la campana dentro de
    // Mapa (ver mockup) -- ambos caminos deben dejar el back stack exactamente igual, o mezclar
    // un navigate() simple (campana) con el patrón popUpTo/singleTop/restoreState (pestañas) deja
    // el grafo en un estado inconsistente. Un solo camino para cualquier destino-pestaña, sin
    // excepción.
    //
    // La guarda de "ya estoy ahí" NO puede leer currentRoute (el `by currentBackStackEntryAsState()`
    // de arriba): quedó demostrado con logging en dispositivo que esa lectura se queda pegada al
    // valor de la composición en la que se creó este navigateToTab() -- el closure que captura
    // NavigationBarItem.onClick no se refresca en cada recomposición de este NavHost (Compose no
    // tiene motivo para recomponer ese ítem si su propio `selected` no cambió), así que currentRoute
    // podía seguir reportando "map" mucho después de haber navegado a otra pestaña. Eso hacía que
    // "Mapa" desde Alertas o Cuenta pareciera no hacer nada: la guarda comparaba destination.route
    // ("map") contra ese currentRoute obsoleto (también "map") y retornaba temprano sin llamar a
    // navigate() -- el back stack real (navController.currentBackStack.value) sí tenía "events" o
    // "account" arriba todo el tiempo, por eso el botón físico de atrás sí volvía al mapa. La guarda
    // ahora lee navController.currentDestination?.route directo del NavController (no una State de
    // Compose), que nunca queda desactualizado. Con esa guarda corregida, el patrón
    // popUpTo(startDestination)+launchSingleTop+restoreState de abajo funciona igual para las 4
    // pestañas, Mapa incluido -- no hace falta ningún caso especial para la start destination.
    fun navigateToTab(destination: Destination) {
        if (destination.route == navController.currentDestination?.route) return
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Solo en las 4 pestañas -- Login/ForgotPassword/AssetDetail/History no la muestran.
            if (currentRoute in AppBottomBarRoutes) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    unseenEventsCount = unseenEventsCount,
                    onNavigate = ::navigateToTab
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
        ) {
            composable(Destination.Login.route) {
                LoginScreen(
                    onNavigateToMap = {
                        navController.navigate(Destination.Map.route) {
                            popUpTo(Destination.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToForgotPassword = {
                        navController.navigate(Destination.ForgotPassword.route)
                    }
                )
            }
            composable(Destination.ForgotPassword.route) {
                ForgotPasswordScreen(onBack = { navController.popBackStack() })
            }
            composable(Destination.Map.route) {
                MapScreen(
                    onOpenAssetDetail = { assetId ->
                        navController.navigate(Destination.AssetDetail.createRoute(assetId))
                    },
                    onOpenHistory = { assetId, day ->
                        navController.navigate(Destination.History.createRoute(assetId, day))
                    },
                    onOpenEvents = { navigateToTab(Destination.Events) }
                )
            }
            composable(Destination.Units.route) {
                UnitsScreen(
                    onOpenAssetDetail = { assetId ->
                        navController.navigate(Destination.AssetDetail.createRoute(assetId))
                    },
                    // Único camino al mapa: la pestaña (una sola instancia de MapViewModel/GoogleMap
                    // y un solo sondeo). El bus guarda la petición hasta que la pestaña la consuma.
                    onOpenOnMap = { assetId ->
                        mapFocusRequestBus.requestFocus(assetId, openSheet = true)
                        navigateToTab(Destination.Map)
                    }
                )
            }
            composable(Destination.Events.route) {
                EventsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAssetDetail = { assetId ->
                        navController.navigate(Destination.AssetDetail.createRoute(assetId))
                    },
                    onOpenEventMap = { eventId ->
                        navController.navigate(Destination.EventMap.createRoute(eventId))
                    },
                    onOpenManageAlerts = { navController.navigate(Destination.ManageAlerts.route) }
                )
            }
            composable(Destination.ManageAlerts.route) { backStackEntry ->
                // CreateAlert avisa por el savedStateHandle de esta entrada, que sigue viva debajo.
                val handle = backStackEntry.savedStateHandle
                val alertsChanged by handle.getStateFlow(ALERTS_CHANGED_KEY, false).collectAsState()
                ManageAlertsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCreateForm = { navController.navigate(Destination.CreateAlert.route) },
                    onOpenEditForm = { alertId -> navController.navigate(Destination.EditAlert.createRoute(alertId)) },
                    refreshRequested = alertsChanged,
                    onRefreshHandled = { handle[ALERTS_CHANGED_KEY] = false }
                )
            }
            composable(Destination.CreateAlert.route) {
                CreateAlertScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        navController.previousBackStackEntry?.savedStateHandle?.set(ALERTS_CHANGED_KEY, true)
                        navController.popBackStack()
                    }
                )
            }
            composable(
                route = Destination.EditAlert.route,
                arguments = listOf(navArgument(Destination.EditAlert.ARG_ID) { type = NavType.IntType })
            ) { backStackEntry ->
                val alertId = backStackEntry.arguments?.getInt(Destination.EditAlert.ARG_ID) ?: return@composable
                CreateAlertScreen(
                    alertId = alertId,
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        navController.previousBackStackEntry?.savedStateHandle?.set(ALERTS_CHANGED_KEY, true)
                        navController.popBackStack()
                    }
                )
            }
            composable(Destination.Reports.route) {
                ReportsScreen()
            }
            composable(Destination.Account.route) {
                AccountScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = {
                        navController.navigate(Destination.Login.route) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                )
            }
            composable(
                route = Destination.AssetDetail.route,
                arguments = listOf(navArgument(Destination.AssetDetail.ARG_ID) { type = NavType.IntType })
            ) { backStackEntry ->
                val assetId = backStackEntry.arguments?.getInt(Destination.AssetDetail.ARG_ID) ?: return@composable
                AssetDetailScreen(
                    assetId = assetId,
                    onBack = { navController.popBackStack() },
                    onOpenHistory = { navController.navigate(Destination.History.createRoute(assetId)) },
                    // Va a la pestaña Mapa (no apila otra entrada de Mapa: eso duplicaba
                    // MapViewModel y sondeos). Comportamiento estándar de pestañas: "atrás" desde
                    // el mapa ya no vuelve a la ficha. popBackStack y no navigateToTab: verificado
                    // en dispositivo que navigate(popUpTo+restoreState) desde la ficha no cambia
                    // el back stack (la ficha queda encima); el Mapa de la pestaña ya está debajo.
                    onNavigateToMap = {
                        mapFocusRequestBus.requestFocus(assetId)
                        if (!navController.popBackStack(Destination.Map.route, inclusive = false)) {
                            navigateToTab(Destination.Map)
                        }
                    }
                )
            }
            composable(
                route = Destination.History.route,
                arguments = listOf(
                    navArgument(Destination.History.ARG_ID) { type = NavType.IntType },
                    navArgument(Destination.History.ARG_DAY) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val assetId = backStackEntry.arguments?.getInt(Destination.History.ARG_ID) ?: return@composable
                // Fecha inválida o ausente -> hoy (ver HistoryDateRange.forDay).
                val day = backStackEntry.arguments?.getString(Destination.History.ARG_DAY)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                HistoryScreen(
                    assetId = assetId,
                    initialDay = day ?: LocalDate.now(),
                    onBack = { navController.popBackStack() },
                    onOpenParameterCharts = { from, to ->
                        navController.navigate(Destination.ParameterCharts.createRoute(assetId, from, to))
                    }
                )
            }
            composable(
                route = Destination.ParameterCharts.route,
                arguments = listOf(
                    navArgument(Destination.ParameterCharts.ARG_ID) { type = NavType.IntType },
                    navArgument(Destination.ParameterCharts.ARG_FROM) { type = NavType.StringType },
                    navArgument(Destination.ParameterCharts.ARG_TO) { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val assetId = backStackEntry.arguments?.getInt(Destination.ParameterCharts.ARG_ID) ?: return@composable
                val from = backStackEntry.arguments?.getString(Destination.ParameterCharts.ARG_FROM)
                    ?.let(LocalDate::parse) ?: return@composable
                val to = backStackEntry.arguments?.getString(Destination.ParameterCharts.ARG_TO)
                    ?.let(LocalDate::parse) ?: return@composable
                ParameterChartsScreen(
                    assetId = assetId,
                    from = from,
                    to = to,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Destination.EventMap.route,
                arguments = listOf(navArgument(Destination.EventMap.ARG_ID) { type = NavType.IntType })
            ) {
                // El id de esta ruta solo identifica la entrada del back stack -- el AlertEvent
                // completo llega por EventMapRequestBus (ver Destination.EventMap/EventMapScreen),
                // no se vuelve a resolver a partir de este id.
                EventMapScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

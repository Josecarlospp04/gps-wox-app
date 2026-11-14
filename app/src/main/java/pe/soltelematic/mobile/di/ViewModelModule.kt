package pe.soltelematic.mobile.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import pe.soltelematic.mobile.ui.account.AccountViewModel
import pe.soltelematic.mobile.ui.assetdetail.AssetDetailViewModel
import pe.soltelematic.mobile.ui.events.EventsViewModel
import pe.soltelematic.mobile.ui.eventmap.EventMapViewModel
import pe.soltelematic.mobile.ui.forgot.ForgotPasswordViewModel
import pe.soltelematic.mobile.ui.history.HistoryViewModel
import pe.soltelematic.mobile.ui.login.LoginViewModel
import pe.soltelematic.mobile.ui.map.MapViewModel
import pe.soltelematic.mobile.ui.parameters.ParameterChartsViewModel
import pe.soltelematic.mobile.ui.alerts.CreateAlertViewModel
import pe.soltelematic.mobile.ui.alerts.ManageAlertsViewModel
import pe.soltelematic.mobile.ui.reports.ReportsViewModel
import pe.soltelematic.mobile.ui.units.UnitsViewModel
import java.time.LocalDate

val viewModelModule = module {
    viewModel { LoginViewModel(get(), get(), get()) }
    viewModel { ForgotPasswordViewModel(get()) }
    viewModel { MapViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AccountViewModel(get(), get(), get()) }
    // assetId llega por parametersOf desde koinViewModel (ver AssetDetailScreen), no por get():
    // es un argumento de navegación, no una dependencia inyectable.
    viewModel { (assetId: Int) -> AssetDetailViewModel(assetId, get(), get(), get()) }
    viewModel { (assetId: Int, initialDay: LocalDate) -> HistoryViewModel(assetId, initialDay, get(), get(), get()) }
    viewModel { (assetId: Int, from: LocalDate, to: LocalDate) -> ParameterChartsViewModel(assetId, from, to, get()) }
    viewModel { EventsViewModel(get(), get(), get(), get()) }
    viewModel { EventMapViewModel(get(), get(), get()) }
    viewModel { UnitsViewModel(get()) }
    viewModel { ManageAlertsViewModel(get()) }
    // alertId llega por parametersOf solo al EDITAR (ver CreateAlertScreen); sin parámetros = crear.
    viewModel { params -> CreateAlertViewModel(params.getOrNull<Int>(), get()) }
    viewModel { ReportsViewModel(get(), get(), get()) }
}

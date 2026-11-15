package pe.soltelematic.mobile.di

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import pe.soltelematic.mobile.BuildConfig
import pe.soltelematic.mobile.core.network.ApiCallExecutor
import pe.soltelematic.mobile.core.network.redactingLoggingInterceptor
import pe.soltelematic.mobile.core.network.AuthEventBus
import pe.soltelematic.mobile.core.network.AuthInterceptor
import pe.soltelematic.mobile.core.network.IconUrlResolver
import pe.soltelematic.mobile.core.network.TokenAuthenticator
import pe.soltelematic.mobile.core.storage.ReportFileStore
import pe.soltelematic.mobile.core.storage.SecureTokenStorage
import pe.soltelematic.mobile.core.storage.SeenEventsStore
import pe.soltelematic.mobile.core.storage.TokenStorage
import pe.soltelematic.mobile.core.storage.UserPreferencesDataStore
import pe.soltelematic.mobile.data.remote.api.AlertsApi
import pe.soltelematic.mobile.data.remote.api.AssetDetailApi
import pe.soltelematic.mobile.data.remote.api.AssetsApi
import pe.soltelematic.mobile.data.remote.api.AuthApi
import pe.soltelematic.mobile.data.remote.api.EventsApi
import pe.soltelematic.mobile.data.remote.api.GeofencesApi
import pe.soltelematic.mobile.data.remote.api.ReportsApi
import pe.soltelematic.mobile.data.remote.api.SharingApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 30L

// reports/generate es síncrono pero puede tardar varios segundos (informe de varios días/varias
// unidades, ver ReportsApi.generate) -- un cliente aparte con este timeout más alto, en vez de
// subir READ_TIMEOUT_SECONDS global: ese valor sigue siendo correcto para el resto de la app, que
// sí debe fallar rápido si el servidor no responde.
private const val REPORTS_READ_TIMEOUT_SECONDS = 120L

// Qualifier para el cliente/Retrofit/API "pelados": sin AuthInterceptor ni TokenAuthenticator.
// No es private porque RepositoryModule también lo usa (forgotPassword necesita el AuthApi
// pelado, igual que TokenAuthenticator usa el mismo para /refresh).
val REFRESH = named("refresh")

// Qualifier para el cliente de Informes (ver REPORTS_READ_TIMEOUT_SECONDS arriba). Sí lleva
// AuthInterceptor/TokenAuthenticator -- a diferencia de REFRESH, este endpoint necesita sesión.
val REPORTS = named("reports")

// Solo se añade con BuildConfig.DEBUG (ver los tres clientes de abajo): en release el interceptor no
// existe, no es un nivel NONE. En debug redacta Authorization y los tokens/contraseña, ver
// redactingLoggingInterceptor.
private fun debugLoggingInterceptor() = redactingLoggingInterceptor()

val networkModule = module {

    single { Json { ignoreUnknownKeys = true } }

    single { AuthEventBus() }

    // TokenStorage/DataStore viven aquí (no hay storageModule propio en el sprint) porque
    // quien primero los consume es el interceptor y el authenticator de abajo.
    single<TokenStorage> { SecureTokenStorage(androidContext()) }
    single { UserPreferencesDataStore(androidContext()) }
    single { SeenEventsStore(androidContext()) }

    // --- Cliente/Retrofit/API "pelados": sin AuthInterceptor ni TokenAuthenticator.
    // Rompen el ciclo: el cliente principal necesita un TokenAuthenticator, que necesita
    // un AuthApi para llamar a /refresh, y ese AuthApi no puede salir del propio cliente
    // principal (todavía no existiría en el momento de construir el authenticator).
    single(REFRESH) {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(debugLoggingInterceptor()) }
            .build()
    }

    single(REFRESH) {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(get(REFRESH))
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
    }

    single(REFRESH) { get<Retrofit>(REFRESH).create(AuthApi::class.java) }

    single { TokenAuthenticator(get(REFRESH), get(), get()) }

    // --- Cliente/Retrofit principal: el que usa el resto de la app.
    single { AuthInterceptor(get()) }

    single {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(get<AuthInterceptor>())
            .authenticator(get<TokenAuthenticator>())
            .apply { if (BuildConfig.DEBUG) addInterceptor(debugLoggingInterceptor()) }
            .build()
    }

    single {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(get())
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
    }

    single { get<Retrofit>().create(AuthApi::class.java) }
    single { get<Retrofit>().create(AssetsApi::class.java) }
    single { get<Retrofit>().create(AssetDetailApi::class.java) }
    single { get<Retrofit>().create(EventsApi::class.java) }
    single { get<Retrofit>().create(GeofencesApi::class.java) }
    single { get<Retrofit>().create(SharingApi::class.java) }
    single { get<Retrofit>().create(AlertsApi::class.java) }

    // --- Cliente/Retrofit/API de Informes: mismo AuthInterceptor/TokenAuthenticator que el
    // principal, solo cambia el readTimeout (ver REPORTS_READ_TIMEOUT_SECONDS arriba).
    single(REPORTS) {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REPORTS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(get<AuthInterceptor>())
            .authenticator(get<TokenAuthenticator>())
            .apply { if (BuildConfig.DEBUG) addInterceptor(debugLoggingInterceptor()) }
            .build()
    }

    single(REPORTS) {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(get(REPORTS))
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
    }

    single(REPORTS) { get<Retrofit>(REPORTS).create(ReportsApi::class.java) }

    single { ApiCallExecutor(get()) }

    single { IconUrlResolver(BuildConfig.BASE_URL) }

    single { ReportFileStore(androidContext()) }
}

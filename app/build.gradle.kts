import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Leída de local.properties (nunca versionada) para no hardcodear el servidor en el código fuente.
// Incluye el prefijo /api/app/clientlite/; las interfaces Retrofit usan rutas relativas sin repetirlo.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val soltelematicBaseUrl: String = localProperties.getProperty("SOLTELEMATIC_BASE_URL", "")
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY", "")

// Firma de release, también desde local.properties (nunca versionada): igual que MAPS_API_KEY,
// si estas 4 propiedades faltan (clon nuevo sin keystore propio) el build type release simplemente
// queda sin signingConfig -- assembleRelease sigue funcionando para compilar/probar, solo que el
// APK resultante no queda firmado para instalar. Ver README para el comando de keytool.
val releaseStoreFile: String = localProperties.getProperty("RELEASE_STORE_FILE", "")
val releaseStorePassword: String = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
val releaseKeyAlias: String = localProperties.getProperty("RELEASE_KEY_ALIAS", "")
val releaseKeyPassword: String = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
val hasReleaseSigningConfig: Boolean = releaseStoreFile.isNotBlank() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

android {
    namespace = "pe.soltelematic.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "pe.gpswox.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BASE_URL", "\"$soltelematicBaseUrl\"")
        // Leída aquí en vez de con buildConfigField porque el manifiesto la necesita como
        // meta-data (com.google.android.geo.API_KEY), no como constante de Kotlin.
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Sin minificar a propósito: el código fuente ya es público en este mismo repo, así
            // que R8 no aporta ninguna protección real (ofuscar no oculta nada que no esté ya en
            // GitHub) -- el único beneficio real sería reducir el tamaño del APK, a cambio de
            // arrastrar el riesgo típico de kotlinx.serialization/Retrofit con reflection bajo R8
            // (serializers que no se encuentran en runtime si falta alguna regla) sin ninguna
            // ganancia de seguridad que lo justifique. Si en el futuro el tamaño del APK importa,
            // esto se activa aparte, con su propio proguard-rules.pro probado y su propia pasada
            // de pruebas en dispositivo -- no como parte apurada de este release.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Íconos para las FABs del mapa (MyLocation, ZoomOutMap) y la cuenta (Logout): no están
    // en el set base de material3, y version.ref lo resuelve el BOM de Compose.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ProcessLifecycleOwner: pausar el polling de Bloque C cuando la app pasa a segundo plano.
    implementation(libs.androidx.lifecycle.process)
    // Red
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Base de datos local
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navegación y almacenamiento
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    // Imágenes, mapas y tiempo real
    implementation(libs.coil.compose)
    implementation(libs.maps.compose)
    implementation(libs.maps.compose.utils)
    implementation(libs.maps.utils)
    implementation(libs.socket.io.client)
    // Inyección de dependencias
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
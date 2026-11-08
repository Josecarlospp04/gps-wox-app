# GPS WOX

App Android nativa de **GPS WOX**, una plataforma de rastreo GPS de flotas heterogéneas: vehículos, maquinaria agrícola (cosechadoras, tractores) y candados electrónicos. Permite a los clientes ver y gestionar sus unidades desde el celular.

El backend es **GPSWOX** (Laravel 9 + Passport OAuth2), consumido a través de `clientlite`, una API específica para clientes móviles.

> Terminología: en la interfaz y en el código se usa **"unidad"** o **"activo"**, nunca "vehículo" — la flota incluye equipos que no son vehículos.

## ⚠️ Aviso de seguridad: el servidor de GPS WOX no tiene TLS todavía

El servidor de GPS WOX responde por **HTTP sin cifrar**, no HTTPS. La app permite tráfico en claro únicamente hacia esa IP específica (`app/src/main/res/xml/network_security_config.xml`), no hacia cualquier destino.

**Qué significa esto en la práctica:** si usás la app en una red no confiable (WiFi pública, por ejemplo), alguien más en esa misma red podría interceptar tus credenciales de inicio de sesión y los datos de posición de las unidades, porque viajan sin cifrar.

Migrar a HTTPS está en el plan, pero requiere un dominio propio — Let's Encrypt (y prácticamente cualquier autoridad certificadora) no emite certificados para direcciones IP directamente, solo para nombres de dominio. Hasta que eso se resuelva, esta limitación se mantiene.

## Instalar la app (usuarios de GPS WOX)

No necesitás compilar nada, ni tener una API key de Google Maps, ni configurar un servidor: el APK publicado ya está listo para usarse con tus credenciales de GPS WOX.

**Vía [Obtainium](https://github.com/ImranR98/Obtainium):**

1. Instalá Obtainium.
2. "Add App" → pegá la URL de este repositorio de GitHub (`https://github.com/Josecarlospp04/gps-wox-app`).
3. Obtainium detecta los releases y te deja instalar/actualizar el APK adjunto directamente, sin pasar por una tienda de aplicaciones.
4. Abrí la app e iniciá sesión con tu usuario y contraseña de GPS WOX.

Eso es todo — no hay ningún paso de configuración adicional para este flujo.

## Compilar desde el código fuente (desarrolladores)

Si vas a clonar este repo y compilarlo vos mismo (por ejemplo, para apuntar a tu propio servidor GPSWOX), necesitás lo siguiente.

### Requisitos

- Android Studio con soporte para AGP 9.2.1
- JDK 11+
- Un dispositivo o emulador con API 26 o superior
- Acceso a un servidor GPSWOX con la API `clientlite` habilitada (ver "Parches necesarios en el servidor GPSWOX" abajo)
- Tu propia API key de Google Maps (SDK for Android habilitado en Google Cloud Console)

### Configuración local

El proyecto lee configuración local desde `local.properties`, que **no se versiona** (ya está en `.gitignore`). Sin este archivo la app compila pero no puede hacer llamadas de red ni mostrar el mapa.

Agrega estas líneas a tu `local.properties` (junto al `sdk.dir` que genera Android Studio):

```properties
# Debe terminar en "/" e incluir el prefijo /api/app/clientlite/
SOLTELEMATIC_BASE_URL=http://tu-servidor/api/app/clientlite/

# Tu propia API key de Google Maps (SDK for Android)
MAPS_API_KEY=tu-api-key-de-google-maps
```

Luego:

```
./gradlew installDebug
```

o desde Android Studio, con un dispositivo/emulador conectado.

> **Nota:** la URL del servidor queda compilada dentro del APK en tiempo de build (`BuildConfig.BASE_URL`), no es configurable desde la app en tiempo de ejecución. Apuntar a un servidor GPSWOX distinto del tuyo implica recompilar con otra `SOLTELEMATIC_BASE_URL`. Hacerla configurable en runtime está planeado para una versión futura.

> **Servidor por HTTP (sin TLS):** si tu servidor de desarrollo responde en HTTP plano, Android bloquea ese tráfico desde API 28 por defecto. `app/src/main/res/xml/network_security_config.xml` habilita cleartext solo para el host configurado ahí — es una excepción **provisional** que debe eliminarse (junto con su referencia en `AndroidManifest.xml`) en cuanto el backend tenga HTTPS.

### Parches necesarios en el servidor GPSWOX

Estos cinco parches se aplicaron directamente sobre la instalación de GPSWOX que usa GPS WOX — **no son parte de este repo** ni de la app Android, viven del lado del servidor. Si usás tu propia instancia de GPSWOX vas a necesitar los mismos, y **hay que volver a aplicarlos después de cada actualización de la plataforma GPSWOX**, porque el proceso de actualización sobrescribe estos archivos y no los conserva.

**1. Passthrough de la cabecera `Authorization` en `.htaccess`**

Sin este parche, Apache descarta la cabecera `Authorization` antes de que Laravel Passport llegue a verla, y **todas las rutas autenticadas devuelven 401 incluso con un token válido** — la app se ve "rota" sin ningún error que lo explique, porque desde el punto de vista de Passport la cabecera simplemente nunca llegó.

Agregá esto al `.htaccess` de la instalación GPSWOX (o al bloque de configuración equivalente de tu vhost):

```apache
RewriteCond %{HTTP:Authorization} .
RewriteRule .* - [E=HTTP_AUTHORIZATION:%{HTTP:Authorization}]
```

> Ojo con una variante muy común de este fix que usa `RewriteCond %{HTTP:Authorization} ^(.*)` junto con `%1` en el `RewriteRule`: `%1` es el grupo capturado por `^(.*)`, que en la práctica muchas veces solo captura el primer carácter de la cabecera, no el token completo. La versión de arriba evita ese problema tomando `%{HTTP:Authorization}` completo directamente.

**2. El endpoint `/user` no devolvía el campo `id`**

La respuesta de `/user` no incluía el `id` del usuario, que la app necesita persistir junto con la sesión. Se agregó en `SettingsController::userSettingsResponse()`:

```php
'id' => $this->user->id,
```

**3. `subscription_expiration` en cero en la cuenta de pruebas**

La cuenta de pruebas tenía la fecha de expiración de suscripción sin configurar (devolvía cero/vacío). Se corrigió asignando una fecha de expiración válida a esa cuenta directamente en la base de datos de GPSWOX.

**4. POST `geofences` en la API móvil**

El endpoint `geofences/map` (lectura) ya existía, pero no había forma de guardar una geocerca nueva desde el celular.

En `routes/app.php`, dentro del mismo grupo que `geofences/map`:

```php
Route::post('geofences', ['uses' => 'GeofencesController@store']);
```

En `app/Http/Controllers/Api/ClientLite/GeofencesController.php`:

```php
public function store(Request $request)
{
    $service = app(\Tobuli\Services\GeofenceService::class);
    $geofence = $service->create($request->all() + ['user_id' => $this->user->id]);

    return response()->json(
        ['status' => 1] +
        $this->transformerService->item($geofence, GeofenceTransformer::class)->toArray()
    );
}
```

> **CRÍTICO:** el `+ ['user_id' => $this->user->id]` es obligatorio. Sin él las geocercas se crean con `user_id` NULL, no pertenecen a nadie y no aparecen en la plataforma web aunque la API responda 200.

**5. DELETE `geofences/{id}` en la API móvil**

En `routes/app.php`:

```php
Route::delete('geofences/{id}', ['uses' => 'GeofencesController@destroy']);
```

El `afterAuth` del mismo controlador pasa a ser:

```php
protected function afterAuth($user)
{
    $action = match (request()->method()) { 'POST' => 'store', 'DELETE' => 'view', default => 'view' };
    $this->checkException('geofences', $action);
}
```

Y el método:

```php
public function destroy($id)
{
    $geofence = Geofence::userOwned($this->user)->find($id);

    if (!$geofence) {
        return response()->json(['status' => 0, 'message' => 'Not found'], 404);
    }

    app(\Tobuli\Services\GeofenceService::class)->delete($geofence);

    return response()->json(['status' => 1, 'id' => (int) $id]);
}
```

> Se usa el permiso `'view'` para DELETE en vez de `'edit'` porque `checkException` con `'edit'` requiere pasar el modelo y falla al resolverlo. La propiedad queda protegida por `userOwned()`, que impide borrar geocercas de otras cuentas.

Después de aplicar el parche 4 o el 5: `php artisan route:clear && php artisan config:clear`.

### Generar un APK de release firmado

El build type `release` firma automáticamente si encontrás estas 4 propiedades en tu `local.properties` (mismo mecanismo que `MAPS_API_KEY`); si no están presentes, `./gradlew assembleRelease` sigue compilando igual, solo que el APK resultante queda sin firmar.

Primero generá tu propio keystore (nunca commitees el archivo `.jks` resultante — ya está en `.gitignore`):

```
keytool -genkeypair -v -keystore release.jks -alias gpswox -keyalg RSA -keysize 2048 -validity 10000
```

Vas a necesitar el SHA-1 de esa firma para restringir tu API key de Google Maps a tu propio `applicationId` + certificado, en vez de dejarla abierta:

```
keytool -list -v -keystore release.jks -alias gpswox
```

(buscá la línea `SHA1:` en la salida — esa es la huella que se agrega en las restricciones de la API key en Google Cloud Console).

Agregá a tu `local.properties`:

```properties
RELEASE_STORE_FILE=../release.jks
RELEASE_STORE_PASSWORD=tu-contraseña-del-keystore
RELEASE_KEY_ALIAS=gpswox
RELEASE_KEY_PASSWORD=tu-contraseña-de-la-clave
```

Y compilá:

```
./gradlew assembleRelease
```

El APK firmado queda en `app/build/outputs/apk/release/app-release.apk`.

> **Sobre la ofuscación (R8/ProGuard):** `isMinifyEnabled` está en `false` a propósito en este release. El código fuente ya es público en este mismo repositorio, así que ofuscarlo no oculta nada real — el único beneficio posible sería reducir el tamaño del APK, a cambio de arrastrar el riesgo típico de que kotlinx.serialization/Retrofit (que usan reflection) fallen en tiempo de ejecución si falta alguna regla de ProGuard. Si en algún momento el tamaño del APK importa lo suficiente, activarlo merece su propio esfuerzo de reglas y pruebas en dispositivo, no hacerlo apurado dentro de otro cambio.

## Estado actual

En desarrollo activo. Completo hasta la fecha:

- Login funcional contra el servidor real, con sesión persistida, y recuperación de contraseña
- Mapa en vivo con clustering de unidades, marcadores tipo píldora (ícono + nombre + color de estado) y capa de geocercas
- Filtros por estado (en línea, detenida, sin señal, etc.) y buscador, compartidos entre el mapa y la lista de Unidades
- Ficha resumida al tocar una unidad (bottom sheet del mapa)
- Actualización incremental de posiciones (sin recargar el mapa completo)
- Ficha de unidad completa (pantalla `AssetDetail`): cabecera con chip de estado, pestañas condicionales (Resumen siempre; Sensores/Servicios/Conductor solo si hay contenido), ubicación con dirección geocodificada y coordenadas copiables, métricas "HOY"
- Historial de recorridos: mapa con polyline por viaje y marcadores de parada/inicio/fin, línea de tiempo con intervalo explícito por tramo y vínculo bidireccional al mapa, resumen del periodo (incluyendo tiempo de conducción calculado del lado del cliente), selector de fechas (Hoy/Ayer/7 días/rango elegido con tope de 31 días), geocodificación perezosa y cacheada por parada
- Bandeja de alertas con badge de no vistos
- Pantalla de Unidades: listado alfabético de toda la flota, independiente del mapa
- Sistema de diseño con tokens propios y soporte white-label
- Enlace de soporte en Cuenta (plataforma web)
- Selector de tipo de mapa (normal, satélite, híbrido, terreno) con persistencia
- Reproducción animada del recorrido en Historial (play/pausa, scrubber, 1×/2×/4×/8×)
- Envío de comandos GPRS a las unidades, con formulario dinámico según lo que declare el servidor
- Creación de geocercas desde el mapa (polígono por puntos o círculo con centro y radio)
- Borrado de geocercas desde la app
- Preferencia de unidad de volumen (litros/galones) con conversión local en la vista de sensores

**Fuera de alcance por ahora:** URL de servidor configurable en runtime (ver nota arriba).

## Stack técnico

- **Lenguaje:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Inyección de dependencias:** Koin
- **Red:** Retrofit + OkHttp + kotlinx.serialization
- **Base de datos local:** Room + KSP
- **Mapas:** Google Maps SDK (Maps Compose + android-maps-utils para clustering)
- **minSdk:** 26 (Android 8.0)

## Arquitectura

Arquitectura por capas:

- **`core/`** — red (cliente HTTP, interceptores, manejo de errores), almacenamiento (tokens cifrados, preferencias), formato (velocidad, duraciones) y otras utilidades transversales
- **`data/`** — DTOs remotos, entidades Room, mappers entre capas y repositorios (implementan las interfaces de `domain/`)
- **`domain/`** — modelos de negocio e interfaces de repositorio, sin dependencias de Android ni de red
- **`ui/`** — pantallas Compose y ViewModels, organizados por feature (login, mapa, unidades, historial, alertas, cuenta)

El mapa vive detrás de una abstracción **`MapEngine`**: la pantalla y el ViewModel no conocen la API de Google Maps directamente, solo el contrato de `MapEngine` (unidades, clustering, cámara). Esto aísla el SDK de mapas del resto de la app — si en el futuro hiciera falta cambiar de proveedor, o testear la lógica de la pantalla sin un mapa real, el cambio queda contenido en la implementación (`GoogleMapEngine`) en vez de esparcirse por toda la UI.

## Notas de desarrollo

- **Koin, no Hilt.** AGP 9.2.1 es incompatible con Hilt ([google/dagger#4944](https://github.com/google/dagger/issues/4944), `Android BaseExtension not found`). No agregar `@HiltAndroidApp`, `@AndroidEntryPoint` ni `@Inject` en este proyecto.
- **`android.disallowKotlinSourceSets=false`** debe estar en `gradle.properties` — sin esto, Gradle no reconoce los source sets de variant (`src/debug`, `src/release`) usados para excluir código de depuración (como el seeder de datos sintéticos) del build de producción.

## Deuda técnica conocida

- **Jank en el mapa de Historial al hacer scroll de la línea de tiempo.** Medido con `dumpsys gfxinfo` (dispositivo real, unidad "prueba EUI-281", rango de 7 días, ~31k posiciones GPS antes de simplificar): percentil 90 de 900ms sobre una muestra de 13 frames. Hipótesis sin confirmar: los `LaunchedEffect` de geocodificación perezosa (`HistoryTimeline.kt`) disparándose en bloque cuando varias filas de parada entran en pantalla a la vez durante un scroll rápido, no el dibujo del polyline en sí (ese ya se resolvió, ver `GoogleRouteMapEngine.kt`). Sin investigar todavía — 13 frames es muestra insuficiente para confirmar la causa. Revisar en el pulido: repetir la medición con una muestra más grande y, si la hipótesis se confirma, evaluar debounce o límite de concurrencia en `onStopRowVisible`.

## Contrato de API

El backend expone `clientlite` bajo el prefijo `/api/app/clientlite/`. Endpoints integrados: `token`, `refresh`, `server_config`, `user`, `devices/map`, `devices/latest`, `device/{id}`, `history` y `address`.

> **Nota sobre `device/{id}`:** el campo `time.timestamp` viene desfasado (~5h en producción, igual al offset UTC-5 de Perú); usar `time.formatted` en su lugar, que sí es correcto. De ahí depende el color de "hace X" y el atenuado de las métricas de "HOY". `AssetMapper.kt` (el de `devices/map`, usado por el mapa) no tiene este problema.

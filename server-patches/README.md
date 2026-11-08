# Parches al servidor GPSWOX — GPS WOX (gps-wox-app)

Doce parches obligatorios. Sin ellos la app no funciona correctamente.

**Hay que reaplicarlos después de cada actualización de la plataforma GPSWOX**, porque se sobrescriben. Al desplegar la app para un cliente nuevo (otro servidor), aplicar los doce antes de nada.

Los archivos de esta carpeta son copias **literales y probadas** de un servidor en producción, no reconstrucciones.

---

## Checklist de despliegue en servidor nuevo

- [ ] Parche 1 — `.htaccess`: passthrough de `Authorization`
- [ ] Parche 2 — `/user` devuelve `id`
- [ ] Parche 3 — `subscription_expiration` con fecha válida
- [ ] Parche 4 — `POST geofences`
- [ ] Parche 5 — `sharing` (enlace temporal)
- [ ] Parche 6 — servicios de mantenimiento (CRUD)
- [ ] Parche 7 — informes (tipos + generación)
- [ ] Parche 8 — series temporales de parámetros (gráficas)
- [ ] Cliente OAuth `ClientLite Password Grant Client` creado (`php artisan server:passport`)
- [ ] Verificar login desde la app antes de entregar
- [ ] Parche 9 — `devices/map` sin filtro de visibilidad
- [ ] Parche 10 — gestión de alertas (`alerts`)
- [ ] Parche 11 — `logout` revoca el token en el servidor
- [ ] Parche 12 — `events` aplica los filtros `date_from` / `date_to`

---

## Antes de empezar: respaldos

```bash
cd /var/www/soltelematic
cp routes/app.php{,.bak}
cp public/.htaccess{,.bak}
cp app/Http/Controllers/Api/ClientLite/SettingsController.php{,.bak}
```

## Dónde va cada archivo

| Archivo de esta carpeta | Destino en el servidor |
|---|---|
| `htaccess.txt` | `public/.htaccess` (editar, no reemplazar) |
| `app.php` | `routes/app.php` |
| `controllers/SettingsController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/GeofencesController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/SharingController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/ServicesController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/ReportsController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/ParametersController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/DevicesController.php` | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/AlertsController.php` (nuevo) | `app/Http/Controllers/Api/ClientLite/` |
| `controllers/EventsController.php` | `app/Http/Controllers/Api/ClientLite/` |

⚠️ `.htaccess` y `SettingsController.php` **no se reemplazan enteros**: el original trae más cosas. Ver abajo qué línea añadir en cada uno.

Tras copiar, verificar sintaxis:

```bash
php -l routes/app.php
for f in app/Http/Controllers/Api/ClientLite/*.php; do php -l "$f"; done
```

---

## 1 · `.htaccess` — passthrough de `Authorization`

**Síntoma sin el parche:** todas las rutas autenticadas devuelven **401 con tokens válidos**. Apache descarta la cabecera antes de que Passport la vea. Afecta a cualquier integración externa, no solo a la app.

**Archivo:** `public/.htaccess`

```apache
RewriteCond %{HTTP:Authorization} .
RewriteRule .* - [E=HTTP_AUTHORIZATION:%{HTTP:Authorization}]
```

⚠️ No usar la variante con `^(.)` y `%1` que circula en foros: `%1` captura solo el **primer carácter** de la cabecera, no el token completo.

---

## 2 · `/user` no devolvía el `id`

**Síntoma:** el `UserDto` de la app llega sin identificador.

**Archivo:** `SettingsController.php` · **Método:** `userSettingsResponse()`

```php
'id' => $this->user->id,
```

---

## 3 · `subscription_expiration` en ceros

**Síntoma:** 401 por suscripción vencida, indistinguible de sesión expirada.

**Acción:** poner una fecha de expiración válida en la cuenta del cliente. **No es un archivo**, es un valor en la base de datos — no se puede versionar.

⚠️ Riesgo abierto: la app no distingue el 401 de suscripción vencida del de sesión expirada.

---

## 4 · `POST geofences`

La creación de geocercas existe solo en rutas web (`routes/web.php` ~130, vía `Route::resource`), que usan sesión y CSRF.

**Contrato:** `name`, `type` (`polygon`/`circle`), `polygon[]` o `center`+`radius`, `polygon_color` (**exactamente 7 caracteres**), `speed_limit`, `group_id`.

⚠️ El color se llama `polygon_color` al **escribir** pero llega como `color` al **leer** (`GET geofences/map`).

---

## 5 · `sharing` — enlace temporal

Solo existía en rutas web (`routes/web.php` ~493). Además `send()` devuelve solo `['status' => 1]`: **el enlace nunca vuelve al cliente**, porque la web lo despacha por SMS/email. La app lo necesita en mano.

**Endpoints:** `GET`, `POST`, `DELETE sharing/{id}`

**Contrato:** `devices[]`, `expiration_by` (`duration`/`date`), `duration` (**en MINUTOS**: 24h=1440, 7d=10080), `expiration_date`, `name`.

Devuelve `hash`. Enlace público: `/sharing/{hash}`. **La app arma la URL con su propia BASE_URL**, no usa el campo `url` de la respuesta (`url()` toma el host de la petición y puede devolver `localhost`).

⚠️ No hay opción de "incluir historial": las rutas públicas son solo posición y dirección. La web tampoco lo ofrece.
⚠️ `afterAuth()` usa `'view'` para DELETE, no `'remove'`: con `'remove'` el `exceptionManager` falla con `"global.sharing" no fue encontrado`.
⚠️ `filterUserAbility()` devuelve una **Collection**, no un query builder — no lleva `->get()`.
⚠️ `expiration_date` vuelve en **dos formatos distintos**: ISO con Z al crear, con espacio y sin zona al listar.

---

## 6 · Servicios de mantenimiento

Solo en rutas web (`routes/web.php` ~321-327). Toda la lógica vive en `ModalHelpers/ServiceModalHelper.php`.

**Endpoints:** `GET device/{id}/services`, `GET device/{id}/services/create`, `POST device/{id}/services`, `PUT services/{id}`, `DELETE services/{id}`

**Contrato:** `name`, `expiration_by` (`odometer`/`engine_hours`/`days`), `interval`, `last_service`, `trigger_event_left` (**menor que `interval`**), `renew_after_expiration`, `allow_expired_value`, `description`.

⚠️ **Tres claves se leen SIN `isset()`** (`email`, `last_service`, `expiration_by`): si faltan es error fatal de PHP (500). El controlador las rellena en `normalizeInput()` con `request()->merge()` — sobre la request real, porque `ModalHelper` toma `$this->data` de `request()->all()` en su constructor.
⚠️ **Huso horario (rama `days`):** `last_service` pasa por `Formatter::time()->reverse`. La app debe enviar hora local del usuario, nunca UTC.
⚠️ El campo legible para mostrar es **`expires`** (`"Odómetro Restante (4949)"`).
⚠️ `odometer_value` llega como **string o número** según si la unidad tiene sensor con valor.

---

## 7 · Informes

**No existía ningún controlador de API previo.** La generación vive solo en `Frontend\ReportsController` + `ModalHelpers/ReportModalHelper.php`.

**Endpoints:** `GET reports/types`, `POST reports/generate`

**Lista blanca** (en el controlador, ampliable sin publicar APK): `1` Información general · `3` Recorridos y paradas · `40` Paradas · `4` Hoja de Viajes · `5` Exceso de velocidad · `7` Geocercas entradas/salidas · `8` Eventos · `11` Rellenos de combustible · `12` Robos de combustible · `25` Historial de objetos · `29` Horas del motor Diariamente · `43` Rutas · `79` Distancia diaria del conductor

⚠️ **Campos extra por tipo:** cada clase de informe declara en `$validation` (protected, se lee con `ReflectionProperty`) los campos que exige. `reports/types` los expone en `requires[]` para que la app pinte el campo que toque sin hardcodear nada. Hoy: `5` exige `speed_limit`, `7` exige `geofences[]`, `25` exige `devices` (que la app ya manda siempre).
⚠️ **Generación síncrona**, sin cola. Con `generate => 1` se salta el paso de la URL intermedia que usa la web.
⚠️ **`devices_query` debe ser un query builder, NO una Collection** — el generador llama `isJoined()`. Y como `request()->merge()` solo acepta escalares/arrays, los datos se pasan con `$helper->setData($data)`.
⚠️ **`Report::download()` no es uniforme:** pdf/xlsx devuelven respuesta de Laravel; **html hace `header()` + `echo` sin `return`** → se captura con `ob_start()/ob_get_clean()`.
⚠️ **Cada tipo declara su propio `$formats`** (protected, sin getter → `ReflectionProperty`). **Rutas (43) solo admite `html`**; pedirle PDF revienta wkhtmltopdf con un 500.

---

## 8 · Series temporales de parámetros (gráficas)

**Motivo:** la plataforma web solo muestra el **valor actual** de cada sensor; no existe histórico en ninguna vista. Pero el dato **sí está guardado**: cada fila de `positions_{device_id}` (base de Traccar) trae el XML crudo del equipo en `other` y los sensores configurados en `sensors_values`.

**Archivos tocados:**
- `routes/app.php`
- `app/Http/Controllers/Api/ClientLite/ParametersController.php` (nuevo)

**Endpoint:** `GET parameters?device_id=&from=&to=`

### Fuente de cada parámetro

| Serie | Origen |
|---|---|
| `voltage` | `<power>` de `other` — voltaje externo (V) |
| `battery` | `<io113>` de `other` — batería interna (%) |
| `rssi` | `<rssi>` de `other` |
| `satellites` | `<sat>` de `other` |
| `speed` | columna `speed` (nudos → km/h ×1.852) |
| `fuel` | `sensors_values`, por el **id** del sensor `fuel_tank` de esa unidad |

Una serie sin datos **no se incluye** en la respuesta: la unidad no reporta ese parámetro y la app no dibuja esa gráfica.

⚠️ **Submuestreo con LTTB** (Largest Triangle Three Buckets), máximo 500 puntos por serie — **no promedio**. Promediar aplana los picos, y el pico es el dato que importa: en una prueba real, el arranque del motor se ve como `25.285 → 21.845 → 28.296` en segundos; con promedio quedaría una línea plana en 25 V. LTTB devuelve siempre valores **reales medidos**.

⚠️ Las posiciones se leen con `$device->positions()` (relación `HasManyTable` que ya resuelve `positions_{id}` en la base de Traccar) — no construir el nombre de tabla a mano.

⚠️ `sensors_values` **ya viene casteado a array** por el modelo; no aplicar `json_decode` sin comprobar.

**Rendimiento verificado:** 7 días = 7425 posiciones → 500 puntos por serie, **1.9 s y 51 KB**.

### `sensor_series` (parche 8b) — series por sensor, independientes de la marca

**Motivo:** las series fijas de arriba usan etiquetas de Teltonika (`<power>`, `<io113>`). Una unidad de otra marca (p. ej. JimiIoT JM-LL301: `<battery>`, `<rssi>`, `<sat>`) no las tiene y no mostraba gráficas.

**Qué se añade:** la respuesta lleva una clave nueva `sensor_series` junto a `data`:

```json
{ "status": 1,
  "data": [ ...series fijas, sin cambios... ],
  "sensor_series": [
    { "key": "speed", "name": "Velocidad", "type": "speed", "unit": "km/h", "points": [[t, v], ...] },
    { "key": "sensor_123", "sensor_id": 123, "name": "RESPALDO", "type": "battery", "unit": "%", "points": [[t, v], ...] }
  ] }
```

- Una serie por sensor de la unidad, con el valor calculado por `DeviceSensor::getValue($position->other, false)` (fórmula, min-max y calibración del propio sensor), y la unidad de `getUnit()`.
- Se excluyen los tipos `acc`, `ignition`, `logical`, `textual` y `odometer`, y cualquier valor no numérico (`null`, `'-'`, booleano). Una serie sin puntos no se incluye.
- Mismos puntos `[t, v]`, mismo LTTB (máx. 500 por serie) y misma lectura con `$device->positions()`.

⚠️ **`data` no se toca:** la app 0.9.0 depende de las series fijas (`voltage`, `battery`, `rssi`, `satellites`, `speed`, `fuel`). Se mantienen por compatibilidad.
⚠️ **Coste:** `getValue` parsea el XML de `other` en cada llamada: es (posiciones × sensores graficables). Los tipos excluidos se descartan antes del bucle y las posiciones sin `other` se saltan.
⚠️ `getValue` aplica la configuración **actual** del sensor a datos históricos: si se cambia la calibración, las gráficas pasadas cambian con ella.

---

## 9 · `devices/map` sin filtro de visibilidad

**Síntoma:** una unidad desaparece del mapa de la app de forma intermitente: el contador de la barra de filtros baja (18 → 17) y el marcador se va. Al rato vuelve sola.

**Causa:** `DevicesController@map` (ClientLite) llevaba `->visible()`, un scope que filtra por `user_device_pivot.active = 1` — es decir, **el checkbox de la lista de objetos de la WEB**, pensado como comodidad de monitoreo de escritorio.

El parpadeo venía de una asimetría: **`map()` aplicaba el filtro y `latest()` no**. La app borra de Room toda unidad que `devices/map` no devuelve (`deleteMissing`), así que la unidad desmarcada se borraba al refrescar y reaparecía cuando `latest` la traía al reportar posición.

**Archivo:** `app/Http/Controllers/Api/ClientLite/DevicesController.php` · **Método:** `map()`

Se elimina la línea `->visible()`.

**Decisión de producto:** la app muestra **siempre la flota completa**, sin importar qué unidades estén marcadas en la web. El campo `active` sigue viajando en el payload (la unidad desmarcada llega con `active: false`), pero la app lo ignora.

⚠️ Solo afecta a la API móvil. La web usa `Frontend\DevicesController`, otro archivo.

⚠️ Los demás `->visible()` de ClientLite (POIs, geocercas, rutas) **se dejan como están**: tienen su propio concepto de visibilidad.

⚠️ El endpoint `devices` (lista de unidades) nunca llevó este filtro, de ahí que la unidad desmarcada siguiera apareciendo en la pestaña Unidades pero no en el mapa.
---

## 10 · Gestión de alertas

La web gestiona alertas solo por rutas con sesión y CSRF. La lógica (validación y permisos) vive en `ModalHelpers/AlertModalHelper.php`; el controlador nuevo solo la expone como JSON. **No se modifica ningún archivo de GPSWOX** salvo `routes/app.php`.

**Respaldo previo** (el controlador es un archivo nuevo, no hay nada que respaldar de él):

```bash
cd /var/www/soltelematic
cp routes/app.php{,.pre-p10.bak}
```

**Endpoints:**

| Método | Ruta | Acción |
|---|---|---|
| `GET` | `alerts` | `index` — lista con `pois` y `zones` como IDs |
| `GET` | `alerts/form` | `form` — datos para el alta |
| `GET` | `alerts/{id}/form` | `form` — datos para la edición (trae `item` y `commands`) |
| `POST` | `alerts` | `store` |
| `PUT` | `alerts/{id}` | `update` |
| `DELETE` | `alerts/{id}` | `destroy` |
| `POST` | `alerts/active` | `active` — body `{ "id": 5 \| [5, 6], "active": true }` |

`alerts/form` se declara **antes** de `alerts/{id}` en `routes/app.php`.

**Despliegue:**

```bash
cd /var/www/soltelematic
cp <carpeta-del-parche>/controllers/AlertsController.php app/Http/Controllers/Api/ClientLite/
cp <carpeta-del-parche>/app.php routes/app.php
php -l routes/app.php
php -l app/Http/Controllers/Api/ClientLite/AlertsController.php
php artisan route:clear
```

⚠️ **El helper se instancia dentro de cada método**, nunca por inyección ni por la facade `CustomFacades\ModalHelpers\AlertModalHelper`: su constructor crea loaders con `Auth::User()` en ese momento. Antes de usarlo: `setUser`, `setApi(true)`, `setData`.
⚠️ **`index`:** en modo API el helper no trae `pois` ni `zones`; el controlador los añade como listas de IDs con un único `Alert::with(['pois','zones'])` (sin N+1).
⚠️ **`form`:** sustituye `types` por una lista `[{ type, title, attributes: [{ name, type, title, options? }] }]`, con cada campo transformado como lo hace la web (`options` solo en `SelectField`). `createData()` lanza `ValidationException` si el usuario no tiene unidades.
⚠️ **`update`:** el `id` de la ruta prevalece sobre un `id` que llegue en el body.
⚠️ **`active`** no está en el helper: replica la lógica del controlador web (`Alert::enabled()` + `$user->can('active', $alert)` por alerta, y `update(['active' => 0|1])`). Su `afterAuth` pide permiso `view`, no `store`.
⚠️ **No se filtra nada del lado servidor** (ni el canal `command` de `notifications` ni los tipos): la app decide qué mostrar.
⚠️ Errores de validación y de permisos: los resuelve el manejador global de excepciones, igual que en `POST geofences` (el controlador no captura nada).

---

## 11 · `logout` revoca el token en el servidor

**Síntoma sin el parche:** `POST clientlite/logout` existía pero solo devolvía un `201` vacío (`routes/app.php`). Cerrar sesión en la app solo borraba el token local: el access token y el refresh token seguían siendo válidos en el servidor hasta caducar (`expires_in` ≈ 15 días), así que quien los hubiera copiado (logs, copia de seguridad, dispositivo perdido) podía seguir usándolos.

**Archivo:** `routes/app.php` · **Ruta:** `POST logout`, dentro del grupo `auth:api` + `active_subscription`. Se sustituye **solo** el cuerpo de esa ruta; no se toca nada más del archivo.

**Qué hace ahora:** marca como revocados los refresh tokens del access token actual (`oauth_refresh_tokens.revoked = true`) y revoca el propio access token (`$token->revoke()`). Sigue respondiendo `201` con `[]`.

**Respaldo y despliegue:**

```bash
cd /var/www/soltelematic
cp routes/app.php{,.pre-p11.bak}
cp <carpeta-del-parche>/app.php routes/app.php
php -l routes/app.php
php artisan route:clear
diff routes/app.php.pre-p11.bak routes/app.php   # debe mostrar solo el bloque de logout
```

**Verificación:** con un token válido, `POST .../clientlite/logout` → `201`; repetir cualquier petición autenticada con ese mismo token → `401`, y `POST .../refresh` con su refresh token → error.

⚠️ La app llama a este endpoint con un timeout de 5 s y **ignora cualquier fallo** (sin red, `401` por suscripción vencida…): el cierre local nunca depende del servidor. Un `401` en esta llamada no dispara el refresh automático (`TokenAuthenticator` la excluye).
⚠️ `active_subscription` también protege esta ruta: con la suscripción vencida el servidor responde sin revocar, y el token sigue vivo hasta caducar.

---

## 12 · `events` aplica los filtros de fecha

**Síntoma sin el parche:** `GET events?date_from=...&date_to=...` ignoraba las fechas y devolvía siempre la bandeja completa.

**Causa:** `index()` de `Api/ClientLite/EventsController.php` leía `$data` sin haberlo asignado, así que el bloque de filtros de fecha era código muerto (`device_id` sí funcionaba porque es filtrable en la entidad).

**Archivo:** `app/Http/Controllers/Api/ClientLite/EventsController.php` · **Método:** `index()` · Se añade una línea al principio:

```php
$data = $request->all();
```

**Respaldo y despliegue:**

```bash
cd /var/www/soltelematic
cp app/Http/Controllers/Api/ClientLite/EventsController.php{,.pre-p12.bak}
cp <carpeta-del-parche>/controllers/EventsController.php app/Http/Controllers/Api/ClientLite/
php -l app/Http/Controllers/Api/ClientLite/EventsController.php
diff app/Http/Controllers/Api/ClientLite/EventsController.php.pre-p12.bak app/Http/Controllers/Api/ClientLite/EventsController.php   # solo la línea de $data
```

**Contrato verificado (página de 20):**

| Parámetro | Formato | Notas |
|---|---|---|
| `device_id` | entero | Filtra por unidad. Verificado: unidad 455 → 1783 de 10781. |
| `date_from` / `date_to` | `yyyy-MM-dd HH:mm:ss` en **hora local del usuario** | El servidor los convierte con `Formatter::time()->reverse`. Verificado: hoy `00:00:00`–`23:59:59` → 107. |

Se combinan con `page` y con `search`.

⚠️ Mandar UTC desplazaría el rango por el huso del usuario. La app envía `00:00:00` del primer día y `23:59:59` del último.

---

## Verificación rápida tras aplicar

Todas las rutas deben devolver **401** sin token (no 404):

```bash
for u in devices geofences/map sharing reports/types parameters alerts alerts/form; do
  echo -n "$u -> "
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/app/clientlite/$u
done
```

El prefijo es `api/app/` (ver `RouteServiceProvider::mapAppRoutes`).

⚠️ `php artisan route:list` está roto en esta instalación (lanza `NotFoundHttpException`); no sirve para verificar.
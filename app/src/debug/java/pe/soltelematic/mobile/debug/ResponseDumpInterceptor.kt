package pe.soltelematic.mobile.debug

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File

/**
 * HERRAMIENTA DE DEBUG (no existe en release, ver src/release/.../ResponseDumpInterceptor.kt).
 * Guarda el CUERPO crudo de las respuestas de history, events, alerts, devices/map y device/{id}
 * en filesDir/dumps/, para bajarlas con adb y verificar contratos de la API. Solo cuerpos: nunca
 * cabeceras (Authorization) ni la petición. Se aplica sobre el cliente principal, que ve el cuerpo
 * ya descomprimido.
 */
private val DUMP_PATHS = listOf("""(.*/history)""", """.*/events""", """.*/alerts""", """.*/devices/map""", """.*/device/[0-9]+""").map { Regex(it) }

fun responseDumpInterceptor(context: Context): Interceptor? {
    val dir = File(context.filesDir, "dumps").apply { mkdirs() }
    return Interceptor { chain ->
        val response: Response = chain.proceed(chain.request())
        val url = chain.request().url
        if (response.isSuccessful && DUMP_PATHS.any { it.matches(url.encodedPath) }) {
            runCatching {
                val body = response.peekBody(Long.MAX_VALUE).string()
                val name = (url.encodedPath.substringAfter("clientlite/", url.encodedPath).trim('/') +
                    (url.encodedQuery?.let { "_$it" } ?: ""))
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
                File(dir, "${System.currentTimeMillis()}_$name.json").writeText(body)
            }
        }
        response
    }
}

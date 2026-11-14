package pe.soltelematic.mobile.debug

import android.content.Context
import okhttp3.Interceptor

/** Contraparte no-op de src/debug/.../ResponseDumpInterceptor.kt: en release no se vuelca nada. */
@Suppress("UNUSED_PARAMETER")
fun responseDumpInterceptor(context: Context): Interceptor? = null

package pe.soltelematic.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.graphics.ColorUtils
import pe.soltelematic.mobile.domain.model.SpeedBand

// Tokens de marca (blanco-etiquetable). El accent por defecto de SOLTELEMATIC es el mismo en
// claro y oscuro; un BrandConfig puede sustituirlo en runtime (ver Theme.kt).
val SoltelematicBrandAccent = Color(0xFFF5871F)
val SoltelematicBrandWashLight = Color(0xFFFDF0E0)

// Neutros y superficies
val SoltelematicCanvasLight = Color(0xFFFCFBF9)
val SoltelematicCanvasDark = Color(0xFF121110)
val SoltelematicSurfaceLight = Color(0xFFFFFFFF)
val SoltelematicSurfaceDark = Color(0xFF1C1B19)
val SoltelematicSurfaceMutedLight = Color(0xFFF2F0EC)
val SoltelematicSurfaceMutedDark = Color(0xFF262420)
val SoltelematicInkLight = Color(0xFF1A1917)
val SoltelematicInkDark = Color(0xFFF2F0EC)
val SoltelematicInkMutedLight = Color(0xFF77736C)
val SoltelematicInkMutedDark = Color(0xFFA8A49B)
val SoltelematicInkFaintLight = Color(0xFFA6A29A)
val SoltelematicInkFaintDark = Color(0xFF7C7871)
val SoltelematicHairlineLight = Color(0xFFE6E2DB)
val SoltelematicHairlineDark = Color(0xFF2D2B27)
val SoltelematicBorderLight = Color(0xFFD8D3CA)
val SoltelematicBorderDark = Color(0xFF34322D)

// Colores de estado: nunca se re-tematizan por marca (ver Theme.kt).
val SoltelematicStatusMovingLight = Color(0xFF1F9D57)
val SoltelematicStatusMovingDark = Color(0xFF3ABE74)
val SoltelematicStatusIdleLight = Color(0xFFDDA00A)
val SoltelematicStatusIdleDark = Color(0xFFEFB728)
val SoltelematicStatusAlertLight = Color(0xFFD8382A)
val SoltelematicStatusAlertDark = Color(0xFFF0574A)
val SoltelematicStatusOfflineLight = Color(0xFF77736C)
val SoltelematicStatusOfflineDark = Color(0xFF8A8781)

// Bandas de velocidad de la línea del recorrido (verde / azul / naranja / rojo). Fijos: el usuario
// solo cambia los límites, nunca los colores. Saturados para leerse sobre mapa claro y satélite.
val SoltelematicSpeedLowLight = Color(0xFF2E9E4F)
val SoltelematicSpeedLowDark = Color(0xFF3FCB6B)
val SoltelematicSpeedMediumLight = Color(0xFF1E6FD9)
val SoltelematicSpeedMediumDark = Color(0xFF4A94F0)
val SoltelematicSpeedHighLight = Color(0xFFF57C00)
val SoltelematicSpeedHighDark = Color(0xFFFF9F40)
val SoltelematicSpeedVeryHighLight = Color(0xFFD32F2F)
val SoltelematicSpeedVeryHighDark = Color(0xFFFF5A4D)

/**
 * Colores que no caben en [androidx.compose.material3.ColorScheme]: los 4 estados de unidad
 * (con su "wash" de fondo suave) y los neutros/derivados propios del sistema.
 * Se expone vía [LocalSoltelematicColors], provisto dentro de `SoltelematicTheme { }`.
 */
data class SoltelematicColors(
    val inkFaint: Color,
    val statusMoving: Color,
    val statusMovingWash: Color,
    val statusIdle: Color,
    val statusIdleWash: Color,
    val statusAlert: Color,
    val statusAlertWash: Color,
    val statusOffline: Color,
    val statusOfflineWash: Color,
    val accentText: Color,
    val speedLow: Color,
    val speedMedium: Color,
    val speedHigh: Color,
    val speedVeryHigh: Color,
) {
    /** Color fijo de una banda de velocidad (línea del recorrido y leyenda de Cuenta). */
    fun speedColor(band: SpeedBand): Color = when (band) {
        SpeedBand.LOW -> speedLow
        SpeedBand.MEDIUM -> speedMedium
        SpeedBand.HIGH -> speedHigh
        SpeedBand.VERY_HIGH -> speedVeryHigh
    }
}

val LocalSoltelematicColors = staticCompositionLocalOf<SoltelematicColors> {
    error("LocalSoltelematicColors no fue provisto: envuelve el contenido en SoltelematicTheme { }")
}

/** Contraste WCAG entre dos colores opacos (1.0–21.0). */
fun contrastRatio(foreground: Color, background: Color): Double =
    ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())

/**
 * Color de contenido sobre [accent]: blanco si alcanza contraste >= 3.0 (WCAG para bloques
 * grandes/UI), si no cae a un ink oscuro. Puro y testeable — nunca asumas blanco fijo, porque
 * un accent white-label muy claro puede no alcanzar el contraste mínimo.
 */
fun onAccentFor(accent: Color): Color =
    if (contrastRatio(Color.White, accent) >= 3.0) Color.White else SoltelematicInkLight

/** Aplica [alpha] a este color y lo aplana sobre [surface] (evita colores translúcidos "vivos"). */
fun Color.washOver(alpha: Float, surface: Color): Color =
    copy(alpha = alpha).compositeOver(surface)

/** Wash de [accent]: 12% en claro, 16% en oscuro, aplanado sobre la superficie del tema. */
fun accentWashFor(accent: Color, darkTheme: Boolean, surface: Color): Color =
    accent.washOver(if (darkTheme) 0.16f else 0.12f, surface)

/**
 * [accent] oscurecido progresivamente (HSL) hasta alcanzar contraste >= 4.5 sobre [surface].
 * Para texto de enlace/acción sobre fondo claro cuando el accent en sí es demasiado claro.
 */
fun accentTextFor(accent: Color, surface: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accent.toArgb(), hsl)
    var candidate = accent
    var steps = 0
    while (contrastRatio(candidate, surface) < 4.5 && hsl[2] > 0.02f && steps < 40) {
        hsl[2] = (hsl[2] - 0.02f).coerceAtLeast(0f)
        candidate = Color(ColorUtils.HSLToColor(hsl))
        steps++
    }
    return candidate
}

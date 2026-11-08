package pe.soltelematic.mobile.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import pe.soltelematic.mobile.R

/**
 * Configuración blanco-etiquetable recibida del servidor. Esta tarea no incluye la llamada de
 * red que la obtiene — solo el composable de tema debe estar preparado para recibirla.
 */
data class BrandConfig(
    val name: String,
    val accentColor: Color,
    val logoUrl: String?,
)

private const val DefaultBrandName = "GPS WOX"

/**
 * Tema raíz de SOLTELEMATIC Mobile con soporte white-label.
 *
 * Se re-tematiza con [brand]: accent y sus derivados (onAccent/accentWash/accentText), logo,
 * nombre visible. Nunca se re-tematiza: los 4 colores de estado, los neutros, radios/espaciado,
 * ni el estilo de iconos — esos siempre salen de los tokens fijos de [Color.kt] y [Shape.kt].
 */
@Composable
fun SoltelematicTheme(
    brand: BrandConfig? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accent = brand?.accentColor ?: SoltelematicBrandAccent
    val canvas = if (darkTheme) SoltelematicCanvasDark else SoltelematicCanvasLight
    val surface = if (darkTheme) SoltelematicSurfaceDark else SoltelematicSurfaceLight
    val surfaceMuted = if (darkTheme) SoltelematicSurfaceMutedDark else SoltelematicSurfaceMutedLight
    val ink = if (darkTheme) SoltelematicInkDark else SoltelematicInkLight
    val inkMuted = if (darkTheme) SoltelematicInkMutedDark else SoltelematicInkMutedLight
    val inkFaint = if (darkTheme) SoltelematicInkFaintDark else SoltelematicInkFaintLight
    val hairline = if (darkTheme) SoltelematicHairlineDark else SoltelematicHairlineLight
    val border = if (darkTheme) SoltelematicBorderDark else SoltelematicBorderLight
    val statusMoving = if (darkTheme) SoltelematicStatusMovingDark else SoltelematicStatusMovingLight
    val statusIdle = if (darkTheme) SoltelematicStatusIdleDark else SoltelematicStatusIdleLight
    val statusAlert = if (darkTheme) SoltelematicStatusAlertDark else SoltelematicStatusAlertLight
    val statusOffline = if (darkTheme) SoltelematicStatusOfflineDark else SoltelematicStatusOfflineLight

    val onAccent = onAccentFor(accent)
    // El wash claro por defecto usa el hex exacto del handoff (#FDF0E0); cualquier otro caso
    // (oscuro, o accent de marca blanca) se deriva en runtime con la fórmula 12%/16%.
    val accentWash = if (brand == null && !darkTheme) {
        SoltelematicBrandWashLight
    } else {
        accentWashFor(accent, darkTheme, surface)
    }
    val accentText = accentTextFor(accent, surface)
    val statusAlertWash = statusAlert.washOver(if (darkTheme) 0.16f else 0.12f, surface)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentWash,
            onPrimaryContainer = ink,
            background = canvas,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = surfaceMuted,
            onSurfaceVariant = inkMuted,
            outline = border,
            outlineVariant = hairline,
            error = statusAlert,
            onError = onAccentFor(statusAlert),
            errorContainer = statusAlertWash,
            onErrorContainer = ink,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentWash,
            onPrimaryContainer = ink,
            background = canvas,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = surfaceMuted,
            onSurfaceVariant = inkMuted,
            outline = border,
            outlineVariant = hairline,
            error = statusAlert,
            onError = onAccentFor(statusAlert),
            errorContainer = statusAlertWash,
            onErrorContainer = ink,
        )
    }

    val soltelematicColors = SoltelematicColors(
        inkFaint = inkFaint,
        statusMoving = statusMoving,
        statusMovingWash = statusMoving.washOver(if (darkTheme) 0.16f else 0.12f, surface),
        statusIdle = statusIdle,
        statusIdleWash = statusIdle.washOver(if (darkTheme) 0.16f else 0.12f, surface),
        statusAlert = statusAlert,
        statusAlertWash = statusAlertWash,
        statusOffline = statusOffline,
        statusOfflineWash = statusOffline.washOver(if (darkTheme) 0.16f else 0.12f, surface),
        accentText = accentText,
    )

    CompositionLocalProvider(LocalSoltelematicColors provides soltelematicColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SoltelematicTypography,
            shapes = SoltelematicShapes,
            content = content,
        )
    }
}

/**
 * Alias retenido por compatibilidad con el punto de entrada existente (`MainActivity`), que no
 * forma parte de este paquete de tema. `dynamicColor` queda sin efecto: el sistema de diseño de
 * marca reemplaza el color dinámico de Material You.
 */
@Deprecated(
    "Usa SoltelematicTheme",
    ReplaceWith("SoltelematicTheme(darkTheme = darkTheme, content = content)"),
)
@Composable
fun SOLTELEMATICTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    SoltelematicTheme(darkTheme = darkTheme, content = content)
}

/**
 * Logo de marca, encajado en un cuadrado de [size] (40dp por defecto). Tres casos, no dos:
 * - brand == null (GPS WOX por defecto, sin white-label activo): logo PNG local (`ic_brand_logo`,
 *   el pin rojo) -- nunca depende de red, así que nunca debería caer a un monograma de respaldo.
 * - brand.logoUrl != null: intenta el logo remoto del cliente; el monograma con su inicial es
 *   el fallback SOLO mientras carga o si esa carga falla.
 * - brand != null sin logoUrl: monograma directo, no hay nada que cargar.
 */
@Composable
fun BrandLogo(brand: BrandConfig?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    when {
        brand == null -> Image(
            painter = painterResource(R.drawable.ic_brand_logo),
            contentDescription = DefaultBrandName,
            modifier = modifier.size(size)
        )
        brand.logoUrl != null -> SubcomposeAsyncImage(
            model = brand.logoUrl,
            contentDescription = brand.name,
            modifier = modifier.size(size),
            loading = { BrandMonogram(brand.name, modifier = Modifier.fillMaxSize()) },
            error = { BrandMonogram(brand.name, modifier = Modifier.fillMaxSize()) },
        )
        else -> BrandMonogram(brand.name, modifier = modifier.size(size))
    }
}

@Composable
private fun BrandMonogram(name: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "S",
            color = onAccent,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ColorSwatchRow(label: String, background: Color, content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(SoltelematicShapes.small)
                .background(background),
        )
        Spacer(Modifier.width(SoltelematicSpacing.sm))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}

@Composable
private fun PalettePreviewContent() {
    val custom = LocalSoltelematicColors.current
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(SoltelematicSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm),
        ) {
            Text("Display", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("Title", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text("Heading", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("Body", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("Label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
            Text("MICRO CAPS".uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("128", style = SoltelematicMetricTypography.large, color = MaterialTheme.colorScheme.onBackground)
            Text("42.5", style = SoltelematicMetricTypography.medium, color = MaterialTheme.colorScheme.onBackground)
            Text("08:41", style = SoltelematicMetricTypography.small, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(SoltelematicSpacing.sm))
            ColorSwatchRow("primary (brandAccent)", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            ColorSwatchRow("primaryContainer (brandWash)", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            ColorSwatchRow("surface", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
            ColorSwatchRow("surfaceVariant", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            ColorSwatchRow("statusMoving", custom.statusMoving, Color.White)
            ColorSwatchRow("statusMovingWash", custom.statusMovingWash, custom.statusMoving)
            ColorSwatchRow("statusIdle", custom.statusIdle, Color.White)
            ColorSwatchRow("statusIdleWash", custom.statusIdleWash, custom.statusIdle)
            ColorSwatchRow("statusAlert (error)", custom.statusAlert, Color.White)
            ColorSwatchRow("statusAlertWash", custom.statusAlertWash, custom.statusAlert)
            ColorSwatchRow("statusOffline", custom.statusOffline, Color.White)
            ColorSwatchRow("statusOfflineWash", custom.statusOfflineWash, custom.statusOffline)
            ColorSwatchRow("accentText", MaterialTheme.colorScheme.surface, custom.accentText)

            Spacer(Modifier.height(SoltelematicSpacing.sm))
            BrandLogo(brand = null)
        }
    }
}

@Preview(name = "Paleta - Claro", showBackground = true)
@Composable
private fun PalettePreviewLight() {
    SoltelematicTheme(darkTheme = false) { PalettePreviewContent() }
}

@Preview(name = "Paleta - Oscuro", showBackground = true)
@Composable
private fun PalettePreviewDark() {
    SoltelematicTheme(darkTheme = true) { PalettePreviewContent() }
}

@Preview(name = "White-label (accent azul)", showBackground = true)
@Composable
private fun WhiteLabelBrandPreview() {
    val brand = BrandConfig(name = "Acme Fleet", accentColor = Color(0xFF2D6CDF), logoUrl = null)
    SoltelematicTheme(brand = brand, darkTheme = false) { PalettePreviewContent() }
}

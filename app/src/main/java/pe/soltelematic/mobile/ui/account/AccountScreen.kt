package pe.soltelematic.mobile.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import pe.soltelematic.mobile.BuildConfig
import pe.soltelematic.mobile.R
import pe.soltelematic.mobile.core.network.serverRootUrl
import pe.soltelematic.mobile.domain.model.VolumeUnit
import pe.soltelematic.mobile.ui.theme.BrandLogo
import pe.soltelematic.mobile.ui.theme.LocalSoltelematicColors
import pe.soltelematic.mobile.ui.theme.SoltelematicMinTouchTarget
import pe.soltelematic.mobile.ui.theme.SoltelematicPillShape
import pe.soltelematic.mobile.ui.theme.SoltelematicShapes
import pe.soltelematic.mobile.ui.theme.SoltelematicSpacing

private val AvatarSize = 56.dp

/**
 * Sprint 5, retematizado: perfil + "Acerca de" + salir. La sección NOTIFICACIONES del mockup no
 * se agrega -- hoy no existe NINGUNA preferencia de notificación en UserPreferencesDataStore (solo
 * lastEmail y showGeofences, que es del mapa, no de alertas), así que no hay nada que restylear
 * sin inventar (ver resumen de la tarea). Tampoco hay BrandConfig real que fetchear en ningún
 * lado de la app todavía -- "Acerca de" usa BrandLogo(brand = null), el mismo default de siempre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: AccountViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loggedOut.collect { onLoggedOut() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.account_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(SoltelematicSpacing.lg)
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.weight(1f))
            } else {
                // El contenido se desplaza (con la sección de colores ya no cabe en pantallas
                // chicas); "Acerca de" y "Cerrar sesión" quedan fijos abajo.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    ProfileCard(uiState)
                    Spacer(modifier = Modifier.height(SoltelematicSpacing.xl))
                    UnitsSection(
                        volumeUnit = uiState.volumeUnit,
                        onVolumeUnitSelected = viewModel::onVolumeUnitSelected,
                        showTail = uiState.showTail,
                        onShowTailChanged = viewModel::onShowTailChanged
                    )
                    Spacer(modifier = Modifier.height(SoltelematicSpacing.xl))
                    SpeedColorsSection(
                        state = uiState,
                        onLimitChanged = viewModel::onSpeedLimitChanged,
                        onSave = viewModel::onSpeedLimitsSave,
                        onReset = viewModel::onSpeedLimitsReset
                    )
                    Spacer(modifier = Modifier.height(SoltelematicSpacing.xl))
                    SupportSection()
                }
            }

            AboutSection()
            Spacer(modifier = Modifier.height(SoltelematicSpacing.lg))
            LogoutButton(onClick = viewModel::onLogout)
        }
    }
}

/**
 * name viene sin campo propio en /user (ver User.kt) -- el correo ocupa el lugar de "identidad
 * principal" en Heading, y lo que iba a ser la línea secundaria de correo pasa a ser el servidor
 * (dato que la pantalla ya mostraba antes de este retematizado, no se descarta).
 */
@Composable
private fun ProfileCard(uiState: AccountUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AccountAvatar(email = uiState.email)
        Spacer(modifier = Modifier.width(SoltelematicSpacing.lg))
        Column {
            Text(
                text = uiState.email ?: "-",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            uiState.serverName?.let { serverName ->
                Text(
                    text = stringResource(R.string.account_server_line, serverName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            uiState.unitCount?.let { count ->
                Text(
                    text = pluralStringResource(R.plurals.account_units_assigned, count, count),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Mismo patrón que BrandMonogram (Theme.kt): círculo accent + inicial -- duplicado a propósito, es marca vs. usuario. */
@Composable
private fun AccountAvatar(email: String?) {
    Box(
        modifier = Modifier
            .size(AvatarSize)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = email?.trim()?.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * Preferencia de unidad de volumen para los sensores de tanque de la ficha (ver SensorsTab):
 * litros o galones, persistida en UserPreferencesDataStore.volumeUnit. Solo dos opciones -- un
 * FilterChip por opción (mismo componente y estilo que AssetFilterChipsRow) evita el chrome de un
 * menú desplegable para una elección tan chica.
 */
@Composable
private fun UnitsSection(
    volumeUnit: VolumeUnit,
    onVolumeUnitSelected: (VolumeUnit) -> Unit,
    showTail: Boolean,
    onShowTailChanged: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.account_units_section_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LocalSoltelematicColors.current.inkFaint,
            modifier = Modifier.padding(bottom = SoltelematicSpacing.sm)
        )
        Text(
            text = stringResource(R.string.account_volume_unit_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = SoltelematicSpacing.sm)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.sm)) {
            VolumeUnitChip(
                label = stringResource(R.string.account_volume_unit_liters),
                selected = volumeUnit == VolumeUnit.LITERS,
                onClick = { onVolumeUnitSelected(VolumeUnit.LITERS) }
            )
            VolumeUnitChip(
                label = stringResource(R.string.account_volume_unit_gallons),
                selected = volumeUnit == VolumeUnit.GALLONS,
                onClick = { onVolumeUnitSelected(VolumeUnit.GALLONS) }
            )
        }
        Spacer(modifier = Modifier.height(SoltelematicSpacing.lg))
        // Toda la fila conmuta (toggleable), no solo el Switch: objetivo táctil completo.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SoltelematicMinTouchTarget)
                .toggleable(value = showTail, role = Role.Switch, onValueChange = onShowTailChanged)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.account_show_tail_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.account_show_tail_help),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(SoltelematicSpacing.md))
            Switch(checked = showTail, onCheckedChange = null)
        }
    }
}

/** Mismos colores/forma que AssetFilterChipsRow (AssetListControls.kt) -- un solo estilo de chip en toda la app. */
@Composable
private fun VolumeUnitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = SoltelematicPillShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.surface
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.heightIn(min = SoltelematicMinTouchTarget)
    )
}

/**
 * Espacio que antes quedaba vacío entre la tarjeta de perfil y "Acerca de" -- enlace útil,
 * anclado arriba de ese espacio (no centrados ni pegados abajo, ver AccountScreen). La URL de la
 * plataforma web sale de BASE_URL vía serverRootUrl (no hardcodeada).
 *
 * Defensa, no caso esperado: serverRootUrl solo devuelve null si BASE_URL no es una URL http(s)
 * válida (p. ej. SOLTELEMATIC_BASE_URL ausente en local.properties, que deja BASE_URL = ""). Con un
 * build bien configurado no ocurre; si ocurriera, se oculta la sección entera para no dejar un
 * encabezado SOPORTE sin contenido.
 */
@Composable
private fun SupportSection() {
    val context = LocalContext.current
    val webUrl = remember { serverRootUrl(BuildConfig.BASE_URL) } ?: return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.account_support_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LocalSoltelematicColors.current.inkFaint,
            modifier = Modifier.padding(bottom = SoltelematicSpacing.sm)
        )
        SupportRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            title = stringResource(R.string.account_support_web_title),
            subtitle = webUrl,
            onClick = { openExternalUrl(context, webUrl) }
        )
    }
}

/** Fila título + subtítulo + chevron, con el mínimo táctil de 48dp que exige el sistema de diseño. */
@Composable
private fun SupportRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SoltelematicSpacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SoltelematicMinTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = SoltelematicSpacing.sm)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = LocalSoltelematicColors.current.inkFaint)
    }
}

/** Intent externo (navegador). Devuelve false si no hay app que lo resuelva. */
private fun openExternalUrl(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

/**
 * Único bloque de la app (junto con la app bar) que se re-tematiza con el BrandConfig real -- pero
 * no existe ninguna llamada de red en la app que lo obtenga todavía (ver comentario de BrandConfig
 * en Theme.kt), así que brand = null cae al mismo default de siempre. La versión sí es real:
 * BuildConfig.VERSION_NAME/VERSION_CODE, no el texto del mockup.
 */
@Composable
private fun AboutSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.account_about_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LocalSoltelematicColors.current.inkFaint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = SoltelematicSpacing.sm)
        )
        BrandLogo(brand = null)
        Spacer(modifier = Modifier.height(SoltelematicSpacing.sm))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.account_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.labelLarge,
            color = LocalSoltelematicColors.current.inkFaint
        )
    }
}

@Composable
private fun LogoutButton(onClick: () -> Unit) {
    // Mismo tratamiento que ErrorBanner en LoginScreen (errorContainer/error), pero como botón.
    Button(
        onClick = onClick,
        shape = SoltelematicShapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SoltelematicMinTouchTarget)
    ) {
        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
        Spacer(modifier = Modifier.width(SoltelematicSpacing.sm))
        Text(stringResource(R.string.account_logout), style = MaterialTheme.typography.labelLarge)
    }
}

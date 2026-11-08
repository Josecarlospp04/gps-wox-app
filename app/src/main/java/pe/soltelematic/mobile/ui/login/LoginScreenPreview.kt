package pe.soltelematic.mobile.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import pe.soltelematic.mobile.ui.theme.SoltelematicTheme

// BrandBlock llama BrandLogo(brand = null) -- LoginScreen nunca tiene un BrandConfig real (eso
// solo pasa en "Acerca de", Cuenta), así que estos 4 previews deben mostrar siempre el logo
// local (ic_brand_logo, el pin rojo), nunca el monograma de respaldo.
@Preview(name = "Login - Claro", showBackground = true, heightDp = 800)
@Composable
private fun LoginScreenPreviewLight() {
    SoltelematicTheme(darkTheme = false) {
        LoginContentPreview(uiState = LoginUiState(email = "operaciones@empresa.pe"))
    }
}

@Preview(name = "Login - Oscuro", showBackground = true, heightDp = 800)
@Composable
private fun LoginScreenPreviewDark() {
    SoltelematicTheme(darkTheme = true) {
        LoginContentPreview(uiState = LoginUiState(email = "operaciones@empresa.pe"))
    }
}

@Preview(name = "Login - Cargando", showBackground = true, heightDp = 800)
@Composable
private fun LoginScreenPreviewLoading() {
    SoltelematicTheme(darkTheme = false) {
        LoginContentPreview(uiState = LoginUiState(email = "operaciones@empresa.pe", isLoading = true))
    }
}

@Preview(name = "Login - Error de credenciales", showBackground = true, heightDp = 800)
@Composable
private fun LoginScreenPreviewError() {
    SoltelematicTheme(darkTheme = false) {
        LoginContentPreview(
            uiState = LoginUiState(
                email = "operaciones@empresa.pe",
                password = "12345",
                generalError = LoginError.InvalidCredentials
            )
        )
    }
}

@Composable
private fun LoginContentPreview(uiState: LoginUiState) {
    LoginContent(
        uiState = uiState,
        onEmailChange = {},
        onPasswordChange = {},
        onTogglePasswordVisibility = {},
        onSubmit = {},
        onNavigateToForgotPassword = {}
    )
}

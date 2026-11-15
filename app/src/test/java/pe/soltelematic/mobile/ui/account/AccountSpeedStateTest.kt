package pe.soltelematic.mobile.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pe.soltelematic.mobile.domain.model.SpeedRanges
import pe.soltelematic.mobile.domain.model.SpeedRangesError

class AccountSpeedStateTest {

    @Test
    fun `sin editar se muestran los limites guardados y nada se puede guardar`() {
        val state = AccountUiState(speedRanges = SpeedRanges(10, 30, 50))

        assertEquals(SpeedLimitsDraft("10", "30", "50"), state.speedFields)
        assertNull(state.speedError)
        assertFalse(state.canSaveSpeedRanges)
        assertEquals(SpeedRanges(10, 30, 50), state.previewSpeedRanges)
    }

    @Test
    fun `un borrador valido y distinto se puede guardar y la leyenda ya lo muestra`() {
        val state = AccountUiState(speedDraft = SpeedLimitsDraft("15", "45", "90"))

        assertTrue(state.canSaveSpeedRanges)
        assertNull(state.speedError)
        assertEquals(SpeedRanges(15, 45, 90), state.previewSpeedRanges)
    }

    @Test
    fun `un borrador invalido no se guarda, explica el motivo y la leyenda conserva lo guardado`() {
        val state = AccountUiState(speedDraft = SpeedLimitsDraft("60", "20", "80"))

        assertFalse(state.canSaveSpeedRanges)
        assertEquals(SpeedRangesError.NOT_ASCENDING, state.speedError)
        assertEquals(SpeedRanges.DEFAULT, state.previewSpeedRanges)
        assertEquals(SpeedRangesError.NOT_A_NUMBER, AccountUiState(speedDraft = SpeedLimitsDraft("", "60", "80")).speedError)
        assertEquals(SpeedRangesError.NOT_POSITIVE, AccountUiState(speedDraft = SpeedLimitsDraft("0", "60", "80")).speedError)
    }

    @Test
    fun `restaurar solo esta disponible si hay algo distinto de los valores por defecto`() {
        assertFalse(AccountUiState().canResetSpeedRanges)
        assertTrue(AccountUiState(speedRanges = SpeedRanges(10, 30, 50)).canResetSpeedRanges)
        assertTrue(AccountUiState(speedDraft = SpeedLimitsDraft("21", "60", "80")).canResetSpeedRanges)
    }
}

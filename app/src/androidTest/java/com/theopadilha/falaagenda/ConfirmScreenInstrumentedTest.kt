package com.theopadilha.falaagenda

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.capture.ConfirmDraftScreen
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test

class ConfirmScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mostraCamposAusentes() {
        composeRule.setContent {
            FalaAgendaTheme {
                ConfirmDraftScreen(
                    initial = ParsedTaskDraft(
                        title = "",
                        localDate = null,
                        localTime = null,
                        confidence = 0.2,
                        missingFields = setOf(
                            MissingDraftField.TITLE,
                            MissingDraftField.DATE,
                            MissingDraftField.TIME,
                        ),
                        ambiguous = false,
                        transcript = "lembrar",
                    ),
                    onCancel = {},
                    onSave = {},
                )
            }
        }
        composeRule.onNodeWithText("Confira antes de salvar").assertIsDisplayed()
        composeRule.onNodeWithText("Toque para escolher a data").assertIsDisplayed()
        composeRule.onNodeWithText("Toque para escolher o horário").assertIsDisplayed()
        composeRule.onNodeWithText("Falta preencher: o que precisa ser feito, a data, o horário.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Só uma vez").assertIsDisplayed()
        composeRule.onNodeWithText("Todo dia").assertIsDisplayed()
        composeRule.onNodeWithText("Toda semana").assertIsDisplayed()
    }

    @Test
    fun confirmacaoRapidaMostraSalvarEMudar() {
        composeRule.setContent {
            FalaAgendaTheme {
                com.theopadilha.falaagenda.ui.capture.QuickConfirmDialog(
                    draft = ParsedTaskDraft(
                        title = "Tomar remédio",
                        localDate = java.time.LocalDate.of(2026, 8, 21),
                        localTime = java.time.LocalTime.of(8, 0),
                        confidence = 1.0,
                        missingFields = emptySet(),
                        ambiguous = false,
                        transcript = "tomar remédio amanhã às 8h",
                    ),
                    saving = false,
                    onSave = {},
                    onEdit = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithText("Pode salvar?").assertIsDisplayed()
        composeRule.onNodeWithText("Tomar remédio").assertIsDisplayed()
        composeRule.onNodeWithText("Salvar").assertIsDisplayed()
        composeRule.onNodeWithText("Mudar").assertIsDisplayed()
    }
}

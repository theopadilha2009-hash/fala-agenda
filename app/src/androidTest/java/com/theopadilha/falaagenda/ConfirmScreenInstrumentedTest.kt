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
    }
}

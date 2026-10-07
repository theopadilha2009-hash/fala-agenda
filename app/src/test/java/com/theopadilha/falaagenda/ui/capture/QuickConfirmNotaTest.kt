package com.theopadilha.falaagenda.ui.capture

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theopadilha.falaagenda.data.remote.ParseReminderClient
import com.theopadilha.falaagenda.data.remote.SupabaseConfig
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A caixa "Pode salvar?" precisa mostrar a nota que explica o descarte da IA.
 *
 * O defeito: `ConfirmDraftScreen` renderiza `draft.notes` em vermelho, e a caixa rápida — o
 * caminho do salvamento **silencioso**, o que motivou a validação de faixa — não tinha uma única
 * ocorrência de `notes`. O rascunho chegava com a nota "A ajuda extra devolveu uma data fora do
 * calendário", a caixa salvava, e ela nunca via o aviso.
 *
 * O rascunho entra pelo cliente de verdade (`ParseReminderClient` contra um servidor local), então
 * o que a caixa mostra é o texto que a produção produz — não uma string montada no teste.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class QuickConfirmNotaTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = MockWebServer().apply { start() }

    /** A resposta medida da IA para o defeito: `month_of_year = 13` com o resto resolvido. */
    private fun rascunhoComFaixaInvalida(): ParsedTaskDraft = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {
                      "title": "Compromisso",
                      "local_date": "2026-10-25",
                      "local_time": "10:00",
                      "recurrence": {
                        "kind": "YEARLY",
                        "week_days": [],
                        "day_of_month": 5,
                        "month_of_year": 13
                      },
                      "confidence": 0.9,
                      "ambiguous": false,
                      "missing_fields": [],
                      "notes": []
                    }
                    """.trimIndent(),
                )
                .setHeader("Content-Type", "application/json"),
        )
        ParseReminderClient(
            config = SupabaseConfig(url = server.url("/").toString(), anonKey = "k"),
            tokenProvider = { "t" },
            http = OkHttpClient(),
        ).parse(
            transcript = "todo dia 5 de maio",
            nowIso = "2026-10-06T13:00:00Z",
            timezone = "America/Sao_Paulo",
            locale = "pt-BR",
        )
    }

    @Test
    fun aCaixaRapidaMostraANotaDoDescarte() {
        val draft = rascunhoComFaixaInvalida()
        // O rascunho que salva em silêncio é exatamente este — se ele deixasse de ser, a caixa
        // não seria o caminho do defeito e o teste estaria medindo outra tela.
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                QuickConfirmDialog(
                    draft = draft,
                    saving = false,
                    onSave = {},
                    onEdit = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText("A ajuda extra devolveu uma data fora do calendário. Ficou sem essa parte.")
            .assertIsDisplayed()
    }

    /** E o texto quebrado não pode aparecer na caixa: `"Todo 5 de ?"` é o defeito de origem. */
    @Test
    fun aCaixaRapidaNaoMostraOTextoQuebrado() {
        val draft = rascunhoComFaixaInvalida()

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                QuickConfirmDialog(
                    draft = draft,
                    saving = false,
                    onSave = {},
                    onEdit = {},
                    onCancel = {},
                )
            }
        }

        compose.onNodeWithText("Todo 5 de ?").assertDoesNotExist()
    }
}

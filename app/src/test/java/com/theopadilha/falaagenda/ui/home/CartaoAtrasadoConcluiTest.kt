package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.SaveResult
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * O aviso que já passou é o que mais precisa do gesto de um toque — e era o único sem ele.
 *
 * O cartão pendente sempre teve "Concluir" na home; o MISSED só oferecia o toque no cartão
 * inteiro, que abre "Editar tarefa". Lá o "Concluir" existe, mas vive no fim de um formulário
 * de ~1390 dp, ~460 dp abaixo de uma tela de ~470 dp de altura: o caminho real era toque no
 * cartão + rolar + toque em "Concluir", com três botões verdes no meio. A ocorrência atrasada
 * — justamente a que ficou para trás — era a que exigia o caminho mais longo.
 *
 * O modelo sempre permitiu concluir um MISSED (`TaskRepository.complete` aceita PENDING ou
 * MISSED, e a tela de edição já oferece "Concluir" para ele). O que faltava era o atalho na
 * home: `section(...)` do loop de `missedSections(...)` não recebia `onComplete`, e o item
 * escondia o botão para tudo que não fosse PENDING.
 *
 * O teste sobe a home de verdade — `HomeScreen` + `AppContainer` + Room real — e mede o que
 * importa: existe exatamente um "Concluir" no cartão do atrasado, e tocar nele conclui a
 * tarefa em vez de abrir a edição.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
    // Tela realista: é nela que a seção do atrasado fica abaixo da dobra e o teste precisa
    // rolar até o cartão, como ela faria.
    qualifiers = "w360dp-h800dp-xhdpi",
)
class CartaoAtrasadoConcluiTest {

    private val escopo = TestViewModelScopeRule()
    private val compose = createComposeRule()

    /**
     * A regra do escopo é a mais EXTERNA de propósito: é o `after()` do `ComposeContentTestRule`
     * que descarta a composição, e é esse gesto que leva a assinatura da `agendaUi` a zero e arma
     * o `WhileSubscribed(5_000)`. Cancelar antes dele deixaria o timer nascer depois do
     * cancelamento, e ele voltaria a cruzar a fronteira do teste. Ver [TestViewModelScopeRule].
     */
    @get:Rule
    val regras: RuleChain = RuleChain.outerRule(escopo).around(compose)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private val edicoesAbertas = mutableListOf<String>()

    @Before
    fun setUp() {
        container = AppContainer(context)
    }

    /**
     * O atrasado é o rascunho de ontem sem repetição: o repositório arquiva a ocorrência
     * vencida como MISSED (`DraftSchedule.bornWithoutReminder`). Nenhum aviso chegou a sair
     * (`lastReminderAt` nulo), então a seção é "Não consegui avisar" — mesmo cartão, mesmo
     * caminho de `missedSections`.
     */
    private fun semearAtrasado(titulo: String = "Tomar remédio das 8h"): SaveResult = runBlocking {
        container.tasks.saveDraft(
            ParsedTaskDraft(
                title = titulo,
                localDate = LocalDate.now().minusDays(1),
                localTime = LocalTime.of(8, 0),
                recurrence = RecurrenceRule(),
                confidence = 1.0,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = titulo,
            ),
        )
    }

    private fun semearPendente(titulo: String): SaveResult = runBlocking {
        container.tasks.saveDraft(
            ParsedTaskDraft(
                title = titulo,
                localDate = LocalDate.now().plusDays(1),
                localTime = LocalTime.of(10, 0),
                recurrence = RecurrenceRule(RecurrenceKind.NONE),
                confidence = 1.0,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = titulo,
            ),
        )
    }

    private fun comporHome(viewModel: HomeViewModel) {
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen(
                        viewModel = viewModel,
                        voice = VoiceCaptureController(context),
                        themeMode = ThemeMode.SYSTEM,
                        onThemeMode = {},
                        onOpenSettings = {},
                        onOpenMonth = {},
                        onOpenUpdate = {},
                        onWrite = {},
                        onQuick = {},
                        onDraftReady = {},
                        onEditItem = { item -> edicoesAbertas.add(item.occurrence.id) },
                    )
                }
            }
        }
    }

    /** O cartão vem do banco: espera ele chegar à tela antes de perguntar pelo botão. */
    private fun esperarOTexto(texto: String) {
        esperar("o texto \"$texto\" na home") {
            compose.onAllNodesWithText(texto).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun esperar(descricao: String, condicao: () -> Boolean) {
        repeat(100) {
            if (condicao()) return
            Thread.sleep(100)
        }
        throw AssertionError("Esperei $descricao e não aconteceu.")
    }

    private fun rolarAte(texto: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(texto))
    }

    private fun statusDe(id: String): String? =
        runBlocking { container.db.occurrenceDao().get(id)?.status }

    /**
     * O defeito medido: com o item atrasado na home, `onAllNodesWithText("Concluir")` era 0.
     * A ocorrência vencida — a que precisa do gesto de um toque, e não do formulário de 1390 dp
     * — era a única sem ele.
     */
    @Test
    fun cartaoAtrasadoTemUmConcluirNaHome() {
        val atrasado = semearAtrasado()
        assertThat(statusDe(atrasado.occurrence.id)).isEqualTo(OccurrenceStatus.MISSED.name)

        comporHome(escopo.rastrear(HomeViewModel(container)))
        esperarOTexto("Tomar remédio das 8h")
        rolarAte("Tomar remédio das 8h")

        compose.onAllNodesWithText("Concluir").assertCountEquals(1)
    }

    /**
     * O que o botão faz: conclui a ocorrência atrasada e **não** abre a edição. Sem a segunda
     * metade, um "Concluir" que na verdade chamasse `onClick(item)` passaria.
     */
    @Test
    fun toqueNoConcluirDoAtrasadoConcluiSemAbrirAEdicao() {
        val atrasado = semearAtrasado()

        comporHome(escopo.rastrear(HomeViewModel(container)))
        esperarOTexto("Tomar remédio das 8h")
        rolarAte("Tomar remédio das 8h")

        compose.onNodeWithText("Concluir").performClick()

        esperar("a conclusão aterrissar no banco") {
            statusDe(atrasado.occurrence.id) == OccurrenceStatus.COMPLETED.name
        }
        // O toque foi do "Concluir", não do cartão: a edição não abriu.
        assertThat(edicoesAbertas).isEmpty()
        // E o cartão saiu das seções de atraso: sem "Concluir" na home, agora que a ocorrência
        // está em "Concluídas" (que não tem o botão).
        esperar("o botão sair da home") {
            compose.onAllNodesWithText("Concluir").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Concluídas").assertExists()
    }

    /** O pendente não muda: continua com o "Concluir" de um toque, e ele conclui. */
    @Test
    fun cartaoPendenteContinuaComOConcluir() {
        val pendente = semearPendente("Beber água")

        comporHome(escopo.rastrear(HomeViewModel(container)))
        esperarOTexto("Beber água")
        rolarAte("Beber água")

        compose.onAllNodesWithText("Concluir").assertCountEquals(1)
        compose.onNodeWithText("Concluir").performClick()

        esperar("a conclusão aterrissar no banco") {
            statusDe(pendente.occurrence.id) == OccurrenceStatus.COMPLETED.name
        }
        assertThat(edicoesAbertas).isEmpty()
    }

    /**
     * O botão não invade o que já foi feito: a ocorrência concluída vive em "Concluídas", e
     * lá não há "Concluir" nenhum para tocar.
     */
    @Test
    fun cartaoConcluidoNaoTemConcluir() {
        val salvo = semearPendente("Beber água")
        runBlocking { container.tasks.complete(salvo.occurrence.id) }
        assertThat(statusDe(salvo.occurrence.id)).isEqualTo(OccurrenceStatus.COMPLETED.name)

        comporHome(escopo.rastrear(HomeViewModel(container)))
        esperarOTexto("Concluídas")
        rolarAte("Beber água")

        compose.onNodeWithText("Beber água").assertExists()
        compose.onAllNodesWithText("Concluir").assertCountEquals(0)
    }
}

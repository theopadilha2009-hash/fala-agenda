package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.speech.VoiceEngine
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.FalaAgendaRoot
import com.theopadilha.falaagenda.ui.TRUNCATED_NOTICE
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * O recado que o app cortou tem de dizer que foi o app.
 *
 * O defeito medido: "tomar… remédio… de pressão" pausado virava o recado "Tomar", e a tela
 * pedia data e hora como se fosse o que ela disse. Depois do fix do motor, o texto entregue
 * de um parcial (ou do prazo vencido) chega marcado — e é aqui que a marca vira uma frase
 * que ela lê.
 *
 * A frase não pode culpar quem falou: quem parou de ouvir foi o app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class RecadoCortadoTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val anunciado =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        VoiceEngine.forgetOfflineCondemnation()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Composable
    private fun MicDockTeste(truncated: Boolean) {
        FalaAgendaTheme(darkTheme = false) {
            MicDock(
                state = VoiceState.IDLE,
                partial = "",
                error = null,
                onMic = {},
                onWrite = {},
                onQuick = {},
                truncated = truncated,
            )
        }
    }

    @Test
    fun oRecadoCortadoDizQueAEscutaFoiInterrompida() {
        compose.setContent { MicDockTeste(truncated = true) }

        compose.onNodeWithText(TRUNCATED_NOTICE).assertIsDisplayed()
    }

    /**
     * O anúncio da leitura de tela vai para a linha do corte, e sai do texto de espera: dois
     * anúncios no mesmo instante é pior que o silêncio (ver `FalaAnunciadaTest`).
     */
    @Test
    fun oAnuncioDoCorteSaiDoTextoDoCorte() {
        compose.setContent { MicDockTeste(truncated = true) }

        compose.onNodeWithText(TRUNCATED_NOTICE).assert(anunciado)
        compose.onNodeWithText("Toque no microfone e fale")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun oRecadoInteiroNaoDizQueFoiCortado() {
        compose.setContent { MicDockTeste(truncated = false) }

        compose.onNodeWithText(TRUNCATED_NOTICE).assertDoesNotExist()
        compose.onNodeWithText("Toque no microfone e fale").assert(anunciado)
    }

    /** O texto do corte fala do app, não de um defeito dela. */
    @Test
    fun oTextoDoCorteNaoCulpaQuemFalou() {
        compose.setContent { MicDockTeste(truncated = true) }

        compose.onNodeWithText(TRUNCATED_NOTICE)
            .assertTextEquals(TRUNCATED_NOTICE)
    }

    /**
     * O fio inteiro, da escuta à tela: o corte que o controller marca tem de chegar no
     * microfone da home. Este caso sobe a `HomeScreen` de verdade porque o fio não é
     * alcançável por composição direta — é a `MicDock` que recebe o parâmetro de dentro
     * dela, e um `truncated = false` fixo no caminho passava despercebido pelos outros.
     */
    @Test
    fun aHomeMostraOAvisoQuandoAEscutaFoiCortada() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val controller = VoiceCaptureController(context)

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                HomeScreen(
                    viewModel = HomeViewModel(AppContainer(context)),
                    voice = controller,
                    themeMode = ThemeMode.SYSTEM,
                    onThemeMode = {},
                    onOpenSettings = {},
                    onOpenMonth = {},
                    onOpenUpdate = {},
                    onWrite = {},
                    onQuick = {},
                    onDraftReady = {},
                    onEditItem = {},
                )
            }
        }

        // A escuta de verdade: ela fala, e o motor para no meio com o parcial na mão.
        controller.start(context)
        idle()
        val engine = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        engine.triggerOnReadyForSpeech(Bundle())
        engine.triggerOnPartialResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("tomar"))
            },
        )
        engine.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle()
        compose.waitForIdle()

        compose.onNodeWithText(TRUNCATED_NOTICE).assertIsDisplayed()
    }

    /**
     * A caixa rápida fecha o recado num toque, sem mostrar campo nenhum — e não tem onde
     * dizer que a escuta parou no meio. Um recado cortado que passasse por ela seria
     * confirmado como se estivesse completo, que é o defeito medido.
     */
    @Test
    fun oRecadoCortadoNaoPodeSerConfirmadoNaCaixaRapida() {
        val rascunho = rascunho()

        assertThat(mayQuickConfirm(rascunho, truncated = true, agora, zone)).isFalse()
    }

    /**
     * O aviso de corte tem de chegar na tela onde ela decide se o recado está certo.
     *
     * O buraco do PR: todo recado cortado tem texto não-vazio, então ele produz rascunho e a
     * home navega para a confirmação — e o `NavHost` só compõe o destino atual. O aviso, que
     * vivia só na `MicDock` da home, saía da composição no mesmo instante. Ela lia "Você
     * disse: "Tomar"" na tela onde aperta "Salvar", sem nenhuma menção ao corte: a queixa
     * original de novo, com o aviso existindo só por um intervalo.
     *
     * O caminho é o de verdade: a fala inteira atravessa o controller, o parse roda no
     * `HomeViewModel` e a navegação acontece. Um teste que parasse na home não prenderia
     * nada — foi exatamente ele que deixou este buraco passar.
     */
    @Test
    fun oAvisoDeCorteChegaNaTelaOndeElaConfirma() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val container = AppContainer(context)
        // Sem o onboarding a raiz abre nele em vez da home, e o recado nunca chegaria na
        // confirmação. A tela de boas-vindas não é o assunto deste teste.
        runBlocking { container.settings.setOnboardingComplete() }

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                FalaAgendaRoot(container = container)
            }
        }
        compose.waitForIdle()

        // A escuta de verdade, com o motor parando no meio: o parcial "tomar" é o recado.
        container.voice.start(context)
        idle()
        val engine = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        engine.triggerOnReadyForSpeech(Bundle())
        engine.triggerOnPartialResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("tomar"))
            },
        )
        engine.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)

        // O parse (local, sem IA) e a navegação: a tela de confirmação é a que a espera
        // termina mostrando, e é ela que o teste precisa alcançar.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(TRUNCATED_NOTICE).fetchSemanticsNodes().isNotEmpty()
        }

        // Estar na confirmação é parte do que se afirma: o aviso da `MicDock` da home também
        // casa com o texto, e um teste que parasse aqui passaria com o app ainda na home —
        // exatamente o buraco que este caso existe para fechar. A tela onde ela confirma é a
        // que tem o título e o "Você disse" do recado cortado, e é nela que o aviso tem de
        // estar; a `MicDock` da home já saiu da composição.
        compose.onNodeWithText("Confira antes de salvar").assertIsDisplayed()
        compose.onNodeWithText("Você disse: “tomar”").assertIsDisplayed()
        compose.onNodeWithText(TRUNCATED_NOTICE).assertIsDisplayed()
    }

    /**
     * O recado inteiro **não** pode levar o aviso para a confirmação.
     *
     * O caminho do recado cortado e o do recado inteiro passam pelo mesmo `onDraftReady` da
     * home — a marca não pode ser do caminho, e sim da escuta que produziu aquele rascunho.
     * Um `true` fixo ali faria toda fala normal chegar na confirmação dizendo que o app parou
     * de ouvir, o que é uma mentira nova no lugar do silêncio antigo.
     */
    @Test
    fun oRecadoInteiroNaoLevaOAvisoParaATelaOndeElaConfirma() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val container = AppContainer(context)
        runBlocking { container.settings.setOnboardingComplete() }

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                FalaAgendaRoot(container = container)
            }
        }
        compose.waitForIdle()

        container.voice.start(context)
        idle()
        val engine = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        engine.triggerOnReadyForSpeech(Bundle())
        engine.triggerOnPartialResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("tomar"))
            },
        )
        // O fim normal da fala: o motor devolve o texto, e ele não veio de um parcial.
        engine.triggerOnEndOfSpeech()
        engine.triggerOnResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("tomar"))
            },
        )

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Confira antes de salvar").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Você disse: “tomar”").assertIsDisplayed()
        compose.onNodeWithText(TRUNCATED_NOTICE).assertDoesNotExist()
    }

    /** O caminho normal continua igual: quem decide é o `canQuickConfirm` de sempre. */
    @Test
    fun oRecadoInteiroContinuaPodendoSerConfirmadoNaCaixaRapida() {
        val rascunho = rascunho()

        assertThat(rascunho.canQuickConfirm(agora, zone)).isTrue()
        assertThat(mayQuickConfirm(rascunho, truncated = false, agora, zone)).isTrue()
    }

    /** Sem data ou sem horário a caixa rápida já não valia, e não é o corte que decide isso. */
    @Test
    fun oRascunhoSemHorarioNaoEntraNaCaixaRapidaNemSemCorte() {
        val semHora = rascunho().copy(localTime = null, missingFields = emptySet())

        assertThat(mayQuickConfirm(semHora, truncated = false, agora, zone)).isFalse()
    }

    /**
     * O aviso de corte não pode sobreviver à tela que o viu nascer.
     *
     * `draftTruncated` é `rememberSaveable` na raiz, e a confirmação o lê direto: ele não morre
     * quando a tela de confirmação sai, nem quando a rota muda. O caminho da escrita — que não
     * ouviu nada — herdava a marca da fala anterior e a confirmação da tarefa **digitada**
     * dizia "Ouvi só uma parte", uma afirmação falsa sobre o app no lugar onde ela decide.
     *
     * O fio é o de verdade, na ordem em que acontece: fala cortada → Cancelar → "Escrever
     * tarefa" → texto → Continuar. A tela de escrever não recebe a marca de ninguém; quem a
     * limpa é a origem (a rota de escrita), e é isso que este caso prende.
     */
    @Test
    fun aTarefaDigitadaNaoHerdaOAvisoDeCorteDaFalaAnterior() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val container = AppContainer(context)
        runBlocking { container.settings.setOnboardingComplete() }

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                FalaAgendaRoot(container = container)
            }
        }
        compose.waitForIdle()

        // 1) A fala cortada, como no caso de cima: o motor para no meio e o parcial é o recado.
        falarCortado(container)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(TRUNCATED_NOTICE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Confira antes de salvar").assertIsDisplayed()
        compose.onNodeWithText(TRUNCATED_NOTICE).assertIsDisplayed()

        // 2) "Cancelar" na confirmação: volta para a home com a marca ainda guardada.
        //    `performScrollTo`: os botões do fim desta tela ficam fora da janela do teste, e um
        //    clique num nó fora dela não aterrissa em nada — a mesma razão do
        //    `ConfirmDraftScreenTest`.
        compose.onNodeWithText("Cancelar").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Escrever tarefa").fetchSemanticsNodes().isNotEmpty()
        }

        // 3) "Escrever tarefa" e o texto digitado — nenhuma escuta neste caminho.
        compose.onNodeWithText("Escrever tarefa").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Continuar").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("comprar pão")
        compose.onNodeWithText("Continuar").performScrollTo().performClick()

        // 4) A confirmação da tarefa digitada: o título é o dela e NÃO há aviso de corte.
        //    "Comprar pão" é o título que o parser monta do texto digitado — a asserção
        //    positiva de que a confirmação é a da tarefa escrita, e não outra tela qualquer.
        //
        //    `assertExists` (e não `assertIsDisplayed`) nos dois nós do topo: o campo de texto
        //    recebeu o foco e a tela rolou até ele, então o cabeçalho está na árvore mas fora da
        //    janela. Existir é o que se afirma aqui; a visibilidade seria medida do scroll do
        //    teste, não da tela. O aviso, esse sim, é negado por existência — e é ela que o
        //    fix tira da tela.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Comprar pão").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Confira antes de salvar").assertExists()
        compose.onNodeWithText("Comprar pão").assertExists()
        compose.onNodeWithText(TRUNCATED_NOTICE).assertDoesNotExist()
    }

    /**
     * O outro lado do fix: limpar a marca não pode virar "nunca mais avisa".
     *
     * Depois de uma tarefa digitada, uma fala cortada nova tem de voltar a mostrar o aviso. Sem
     * este caso, apagar `draftTruncated = ...` de `onDraftReady` — em vez de limpar na escrita —
     * deixaria o primeiro teste verde e este vermelho.
     */
    @Test
    fun depoisDeEscreverUmaFalaCortadaVoltaAMostrarOAviso() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val container = AppContainer(context)
        runBlocking { container.settings.setOnboardingComplete() }

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                FalaAgendaRoot(container = container)
            }
        }
        compose.waitForIdle()

        // Ela escreve e confirma a tarefa digitada: nenhum aviso, e a marca limpa na origem.
        compose.onNodeWithText("Escrever tarefa").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Continuar").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("comprar pão")
        compose.onNodeWithText("Continuar").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Comprar pão").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Comprar pão").assertExists()
        compose.onNodeWithText(TRUNCATED_NOTICE).assertDoesNotExist()
        compose.onNodeWithText("Cancelar").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Escrever tarefa").fetchSemanticsNodes().isNotEmpty()
        }

        // Agora a fala cortada: o aviso tem de voltar.
        falarCortado(container)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(TRUNCATED_NOTICE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Confira antes de salvar").assertExists()
        compose.onNodeWithText(TRUNCATED_NOTICE).assertExists()
    }

    /**
     * A escuta de verdade, com o motor parando no meio: o parcial "tomar" é o recado cortado.
     * O mesmo fio que os outros casos deste arquivo montam à mão.
     */
    private fun falarCortado(container: AppContainer) {
        container.voice.start(context)
        idle()
        val engine = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        engine.triggerOnReadyForSpeech(Bundle())
        engine.triggerOnPartialResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("tomar"))
            },
        )
        engine.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
    }

    private val agora: Instant = Instant.parse("2026-10-06T12:00:00Z")
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    private fun rascunho() = ParsedTaskDraft(
        title = "Cabelo",
        localDate = LocalDate.of(2026, 10, 8),
        localTime = LocalTime.of(9, 0),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = "cabelo quinta às nove",
    )
}

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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.speech.VoiceEngine
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

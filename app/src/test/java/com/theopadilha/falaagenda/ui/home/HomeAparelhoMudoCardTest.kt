package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.reminders.NotificationHelper
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * O cartão do aparelho mudo **na home**, e não só o predicado que decide o texto.
 *
 * O `ReminderAlertCardTest` prende a frase e o `OracleSanidadeTest` prende o veredito, mas nenhum
 * dos dois toca a ligação entre eles: `HomeScreen.kt` chama `reminderAlertCard(alerts)` e desenha
 * o resultado. Um veredito certo que a home não desenha é a mesma queixa dela — o lembrete sai
 * mudo e o aplicativo não diz nada.
 *
 * O defeito que este arquivo trava é o "avisos OK" num aparelho que não toca: o canal está certo
 * e o volume de alarme do celular está no mínimo. O teste sobe a home de verdade e exige o cartão
 * na árvore, com o texto que diz onde aumentar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeAparelhoMudoCardTest {

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

    /**
     * Deixa os outros dois cartões fora do caminho e o canal no ar.
     *
     * O canal é criado aqui, como o `FalaAgendaApplication` faz no aparelho, e não é detalhe:
     * sem canal, `reminderAlerts` responde `OK` por "ainda não criado" — e o teste negativo
     * passaria por um motivo que não é o dele. Com o canal saudável criado, a única variável
     * entre os dois casos é o volume do alarme.
     */
    private fun semOsOutrosCartoes() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        NotificationHelper.ensureChannel(context)
    }

    private fun volumeDoAlarme(valor: Int) {
        context.getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_ALARM, valor, 0)
    }

    private fun comporHome() {
        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
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
                    onEditItem = {},
                )
            }
        }
    }

    /**
     * O cartão aparece. O volume do alarme zerado é o defeito, e a home tem que dizer — com o
     * texto que ela consegue seguir. `assertExists` depois de rolar até ele, e não
     * `assertIsDisplayed`: o cartão é um `item` do `LazyColumn` no fim da lista, e a janela do
     * Robolectric é baixa. O que se prova é que ele chegou à árvore da home.
     */
    @Test
    fun oAparelhoMudoChegaAteOCartaoDaHome() {
        semOsOutrosCartoes()
        volumeDoAlarme(0)

        comporHome()

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText("Aumentar o volume"))
        compose.onNodeWithText("O volume do alarme está no mínimo").assertExists()
        compose.onNodeWithText("Aumentar o volume").assertExists()
    }

    /**
     * O caminho inverso, que é o que prova que a home lê o aparelho e não desenha o cartão por
     * constante: com o volume audível, não há o que avisar e o cartão não aparece. Sem este caso,
     * um cartão aceso para todo mundo passaria — e o falso positivo é justamente o que ensina a
     * ignorar o cartão.
     */
    @Test
    fun comOVolumeAudivelOCartaoNaoAparece() {
        semOsOutrosCartoes()
        val audio = context.getSystemService(AudioManager::class.java)
        volumeDoAlarme(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))

        comporHome()

        compose.onNodeWithText("Aumentar o volume").assertDoesNotExist()
    }
}

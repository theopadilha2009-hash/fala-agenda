package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
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
 * O cartão de saúde do alarme **na home**, e não só o predicado que decide o texto.
 *
 * O `AlarmHealthCardTest` prende a frase e o `HomeAlarmHealthTest` prende a leitura da
 * permissão, mas nenhum dos dois toca a ligação entre os dois: `HomeScreen.kt` passa
 * `batteryOk`/`canScheduleExact` para `alarmHealthCard` e desenha o resultado. Trocar uma
 * dessas variáveis por constante, ou ler a errada, mantém os dois testes acima verdes e o
 * cartão simplesmente deixa de aparecer — que é o dano que o cartão existe para impedir
 * (num aparelho de fabricante agressivo o alarme não toca e ela nunca fica sabendo).
 *
 * O teste sobe a home de verdade: o cartão é um `item` do `LazyColumn`, e é a posição dele na
 * composição que prova que ele chegou à tela.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeAlarmHealthCardTest {

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

    private fun bateriaRestrita(restrita: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, !restrita)
    }

    private fun comporHome(viewModel: HomeViewModel) {
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
     * A bateria restrita é o problema maior — o sistema pode matar o alarme de vez, e não só
     * atrasar. Com ela ligada, o cartão tem de estar na tela da home.
     */
    @Test
    fun bateriaRestritaChegaAteACartaoDaHome() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        bateriaRestrita(restrita = true)
        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))

        comporHome(viewModel)

        // O cartão é `item` de um `LazyColumn`: fora da viewport ele nem é composto, então o
        // teste precisa rolar até ele — e é essa rolagem que prova que ele está na lista.
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(SAUDE_BATERIA_BOTAO))
        // `assertExists`, e não `assertIsDisplayed`: a janela do Robolectric é baixa e o cartão
        // fica no fim da lista. O que o teste prova é que ele chegou à árvore da home.
        compose.onNodeWithText(SAUDE_BATERIA_BOTAO).assertExists()
    }

    /**
     * A segunda fonte do cartão, que os dois testes acima **não** prendem: com a bateria
     * liberada, quem faz o cartão aparecer é a falta do alarme exato.
     *
     * A revisão provou por mutação que trocar `canScheduleExact` por constante no call-site
     * deixava a suíte inteira verde: o teste de bateria restrita pinava uma fonte só, e o
     * negativo com a bateria liberada não distingue "lê o aparelho" de "só olha a bateria".
     * Sem este caso, o cartão do aviso atrasado podia sumir para sempre sem nada acusar —
     * que é justamente o insumo que o fix do aviso velho introduziu.
     */
    @Test
    fun semAlarmeExatoOCartaoDoAtrasoChegaAteAHome() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        bateriaRestrita(restrita = false)
        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))

        comporHome(viewModel)

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(AVISO_INEXATO_BOTAO))
        compose.onNodeWithText(AVISO_INEXATO_BOTAO).assertExists()
    }

    /**
     * O caminho inverso, que é o que prova que o cartão lê o aparelho e não uma constante: com
     * a bateria liberada e a permissão de alarme exato concedida, não há o que avisar e o
     * cartão não aparece.
     */
    @Test
    fun semProblemaNaoHaCartaoNaHome() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        bateriaRestrita(restrita = false)
        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))

        comporHome(viewModel)

        compose.onNodeWithText(SAUDE_BATERIA_BOTAO).assertDoesNotExist()
    }
}

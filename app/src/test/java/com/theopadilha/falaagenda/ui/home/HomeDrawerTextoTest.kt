package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * O item do menu lateral que leva aos ajustes de bateria.
 *
 * O rótulo de antes — "Não matar alarmes" — pedia a ela que "matasse" alguma coisa, e o
 * aparelho não mata alarme nenhum: o que o sistema faz, sem o ajuste, é adiar o aviso. O
 * texto novo descreve o benefício do toque, que é o que ela quer. Este caso prende o rótulo
 * novo: reverter para o antigo deixa a suíte vermelha.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeDrawerTextoTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * O caminho de ponta a ponta do mesmo vocabulário: a gaveta diz "Fazer os avisos
     * tocarem sempre" e o diálogo que **ela abre** dizia "Não matar alarmes". Era a mesma
     * superfície com dois nomes, e o de dentro pedindo que ela "matasse" alguma coisa.
     *
     * O teste sobe a home de verdade, abre a gaveta pelo botão de menu, toca o item e lê o
     * título do diálogo — é a única forma de prender o fio, porque o diálogo não é
     * alcançável por composição direta (o estado `batteryHelp` é interno da `HomeScreen`).
     */
    @Test
    fun oDialogoQueAMenuAbreNaoFalaEmMatar() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Bateria restrita: é o ramo `else` do título, o que trazia o jargão.
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        val viewModel = HomeViewModel(AppContainer(context))

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

        // A gaveta é animada e em Robolectric o relógio da composição não anda sozinho: sem
        // o `advanceTimeBy` ela fica fora da tela (x negativo) e o toque no item não acha
        // nada. Este era o buraco que deixava o teste verde sem prender nada.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        // `performClick` faz hit-testing e, com a gaveta recém-aberta, o toque caía fora do
        // nó. A ação de semântica chama o `onClick` do item direto, que é o que interessa.
        compose.onNodeWithText("Fazer os avisos tocarem sempre")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // O "Entendi" é o botão que só existe no diálogo: sem ele, o diálogo nem abriu.
        compose.onNodeWithText("Entendi").assertExists()
        // O título do diálogo é o mesmo texto da gaveta que o abriu — duas ocorrências. Com o
        // vocabulário antigo de volta, sobra só a da gaveta e este assert cai.
        compose.onAllNodesWithText("Fazer os avisos tocarem sempre").assertCountEquals(2)
        compose.onNodeWithText("matar", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    private fun menu(batteryOk: Boolean) {
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                HomeDrawerSheet(
                    themeMode = ThemeMode.SYSTEM,
                    batteryOk = batteryOk,
                    onMonth = {},
                    onUpdate = {},
                    onShare = {},
                    onShareDay = {},
                    onBattery = {},
                    onWidget = {},
                    onSettings = {},
                    onThemeMode = {},
                )
            }
        }
    }

    @Test
    fun semOAjusteOMenuPrometeOAvisoENaoFalaEmMatar() {
        menu(batteryOk = false)

        compose.onNodeWithText("Fazer os avisos tocarem sempre").assertIsDisplayed()
        compose.onNodeWithText("matar", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    /** Com o ajuste em ordem o rótulo antigo continua valendo: nada mudou nesse ramo. */
    @Test
    fun comOAjusteOMenuSegueDizendoQueOsAvisosEstaoLiberados() {
        menu(batteryOk = true)

        compose.onNodeWithText("Avisos liberados").assertIsDisplayed()
        compose.onNodeWithText("Fazer os avisos tocarem sempre").assertDoesNotExist()
    }
}

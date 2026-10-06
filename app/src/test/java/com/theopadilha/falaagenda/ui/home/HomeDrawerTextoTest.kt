package com.theopadilha.falaagenda.ui.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

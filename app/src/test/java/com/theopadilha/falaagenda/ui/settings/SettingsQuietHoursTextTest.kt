package com.theopadilha.falaagenda.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O texto do horário de silêncio.
 *
 * Ele tinha três frases e a última repetia o que a primeira já dizia — "quando o silêncio
 * termina, o aplicativo volta a repetir" é a outra face de "não repete os lembretes que já
 * tocaram". O nome do ajuste ("silêncio") também soa como se nada tocasse, então a
 * informação que precisa sobreviver é justamente essa: o primeiro aviso de cada tarefa toca.
 * Este caso prende o texto curto e, com ele, a informação.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class SettingsQuietHoursTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun tela() {
        val container = AppContainer(context)
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                SettingsScreen(container = container, onBack = {})
            }
        }
    }

    @Test
    fun oTextoDizQueOPrimeiroAvisoAindaToca() {
        tela()

        compose.onNodeWithText(
            "Neste período, os avisos já dados não repetem. O primeiro aviso de cada tarefa ainda toca.",
        ).assertIsDisplayed()
    }

    /**
     * A terceira frase saiu: "volta a repetir" era a primeira dita ao contrário, e a tela
     * ficava com três frases para um ajuste de dois números.
     */
    @Test
    fun aTerceiraFraseNaoVoltou() {
        tela()

        compose.onNodeWithText("volta a repetir", substring = true).assertDoesNotExist()
    }
}

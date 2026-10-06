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
 *
 * E toca **no horário marcado**, não no fim do silêncio: `ReminderPolicy.firstReminder`
 * devolve `fireAt = occurrenceScheduledAt` sem passar pelo `shiftOutOfQuietHours`. A revisão
 * pegou que a primeira versão deste texto tinha perdido essa garantia — numa mãe que configura
 * 22h–8h e tem um remédio às 23h, "isso vai me acordar às 8h em vez das 23h?" é dúvida real.
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
    fun oTextoDizQueOPrimeiroAvisoAindaTocaNoHorarioMarcado() {
        tela()

        compose.onNodeWithText(
            "Neste período, os avisos já dados não repetem. O primeiro aviso de cada tarefa ainda toca no horário marcado.",
        ).assertIsDisplayed()
    }

    /**
     * A garantia de que o primeiro aviso toca na hora dela, e não no fim do silêncio: sem
     * esta metade, o texto responde *se* toca e deixa em aberto *quando*.
     */
    @Test
    fun oTextoDizQueOPrimeiroAvisoTocaNaHoraMarcada() {
        tela()

        compose.onNodeWithText("no horário marcado", substring = true).assertExists()
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

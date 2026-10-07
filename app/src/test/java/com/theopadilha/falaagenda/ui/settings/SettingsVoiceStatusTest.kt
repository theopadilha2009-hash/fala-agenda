package com.theopadilha.falaagenda.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.speech.OfflineVoiceStatus
import com.theopadilha.falaagenda.speech.VoskModel
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O que a tela de ajustes diz sobre a voz do celular.
 *
 * Ela reclamava que "o áudio nunca funciona", e o caminho da voz offline não tinha
 * superfície nenhuma: nenhuma tela, nenhum estado, nenhuma frase. O modelo de 31 MB
 * podia estar baixando, ter falhado, ou nunca ter sido tentado, e a tela era idêntica
 * nos três casos — e a diferença entre "o app ainda não tem o modelo" e "o aparelho não
 * sabe ouvir" não aparecia em lugar nenhum.
 *
 * Quem lê é uma pessoa idosa, então o texto é curto e concreto, sem termo técnico, e a
 * regra de produto que governa o vocabulário é uma só: o app nunca faz ela achar que fez
 * algo errado. O teste prende o que não pode voltar — a culpa e o jargão.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class SettingsVoiceStatusTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun limparModelo() {
        VoskModel.dir(context).deleteRecursively()
    }

    private fun tela() {
        val container = AppContainer(context)
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                SettingsScreen(container = container, onBack = {})
            }
        }
    }

    private fun escreverModelo() {
        val dir = VoskModel.dir(context)
        dir.mkdirs()
        File(dir, "final.mdl").writeText("peso")
        File(dir, "mfcc.conf").writeText("--sample-frequency=16000")
    }

    @Test
    fun oCartaoDaVozExiste() {
        tela()

        compose.onNodeWithText("Voz do celular").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun semModeloEDizQueAindaNaoFoiPreparada() {
        tela()

        compose.onNodeWithText(voiceOfflineMessage(OfflineVoiceStatus.NaoInstalado))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun comOModeloNoLugarDizQueEstaPronta() {
        escreverModelo()

        tela()

        compose.onNodeWithText(voiceOfflineMessage(OfflineVoiceStatus.Pronto))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * Os três estados, cada um com a frase dele. O de "preparando" é o que ela mais vai
     * ver (são 31 MB, e o download pode durar) e o de falha é o que ela vai ver quando a
     * conexão cair no meio — nenhum dos dois pode sair igual ao outro.
     */
    @Test
    fun cadaEstadoTemAFraseDele() {
        val frases = listOf(
            OfflineVoiceStatus.NaoInstalado,
            OfflineVoiceStatus.Instalando,
            OfflineVoiceStatus.Pronto,
            OfflineVoiceStatus.Falhou("a rede caiu"),
        ).map { voiceOfflineMessage(it) }

        assertThat(frases.toSet()).hasSize(4)
        frases.forEach { assertThat(it).isNotEmpty() }
    }

    /**
     * A causa real da reclamação: o download de 31 MB sem barra de progresso nem aviso.
     * Enquanto ele corre, ela precisa saber que pode usar o microfone normalmente — e
     * não que algo está errado com o aparelho dela.
     */
    @Test
    fun enquantoPreparaDizQuePodeUsarOMicrofoneNormalmente() {
        val frase = voiceOfflineMessage(OfflineVoiceStatus.Instalando)

        assertThat(frase).contains("Pode usar o microfone")
    }

    /**
     * A falha é o caso em que o app tem a chance de culpar ela, e não pode. A saída tem
     * que ser a de verdade — usar o microfone outra vez, que é o que tenta de novo —, e
     * não um "tente mais tarde" que não diz o que fazer.
     */
    @Test
    fun aFalhaNaoCulpaElaEDizQueTentarDeNovoEUsarOMicrofone() {
        val frase = voiceOfflineMessage(OfflineVoiceStatus.Falhou("a rede caiu"))

        assertThat(frase).contains("microfone")
        assertThat(frase).doesNotContain("erro")
        assertThat(frase).doesNotContain("falhou")
    }

    /**
     * A regra de produto: o app nunca faz ela achar que fez algo errado. Sem esta
     * asserção, a primeira revisão de texto que passear por aqui pode reintroduzir a
     * culpa — foi o que aconteceu com "reconhecimento do aparelho" em `voiceErrorMessage`.
     */
    @Test
    fun nenhumTextoDaTelaJogaACulpaNela() {
        tela()

        listOf("você errou", "você não", "tente de novo", "falhou", "erro").forEach { culpa ->
            compose.onNodeWithText(culpa, substring = true, ignoreCase = true).assertDoesNotExist()
        }
    }

    /**
     * O vocabulário do app não usa jargão: "reconhecimento do aparelho" já foi removido
     * de propósito do erro da escuta. Aqui a mesma regra, pelo mesmo motivo — quem lê
     * não sabe o que é modelo, motor ou download.
     */
    @Test
    fun oTextoDaVozNaoUsaJargao() {
        tela()

        listOf("modelo", "motor", "download", "instala", "reconhecimento").forEach { jargao ->
            compose.onNodeWithText(jargao, substring = true, ignoreCase = true).assertDoesNotExist()
        }
    }
}

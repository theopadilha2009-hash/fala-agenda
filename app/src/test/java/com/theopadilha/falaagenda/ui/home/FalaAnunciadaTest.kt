package com.theopadilha.falaagenda.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O que a leitura de tela anuncia enquanto a fala acontece. O anúncio sai do texto que ela
 * vê, e não da descrição do microfone: aquela descreve a *ação* do toque, e em PREPARING já
 * é "Parar de ouvir" — a mesma string de LISTENING. Com o anúncio pendurado nela, o
 * "Pode falar agora" ficava mudo justamente quando ela precisa falar.
 *
 * O teste fixa os dois lados disso: o texto anunciado muda de PREPARING para LISTENING (o
 * defeito de antes, em que nada mudava), e o microfone não é região viva nenhuma vez — um
 * segundo anúncio no mesmo instante é pior que o silêncio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FalaAnunciadaTest {

    @get:Rule
    val compose = createComposeRule()

    private val anunciado =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Composable
    private fun MicDockTeste(state: VoiceState, onMic: (() -> Unit)?) {
        FalaAgendaTheme(darkTheme = false) {
            MicDock(
                state = state,
                partial = "",
                error = null,
                onMic = onMic,
                onWrite = {},
                onQuick = {},
            )
        }
    }

    @Test
    fun oTextoAnunciadoMudaDeEsperarParaOuvir() {
        val state = mutableStateOf(VoiceState.PREPARING)
        compose.setContent { MicDockTeste(state.value, onMic = {}) }

        compose.onNode(anunciado).assertTextEquals("Espera um instante…")

        state.value = VoiceState.LISTENING
        // Antes: nenhum nó era região viva e a descrição do microfone continuava "Parar de
        // ouvir" nos dois estados — este segundo assert não tinha o que encontrar.
        compose.onNode(anunciado).assertTextEquals("ASSERCAO QUEBRADA DE PROPOSITO")
    }

    @Test
    fun oMicrofoneDescreveAAcaoENaoEAnunciado() {
        compose.setContent { MicDockTeste(VoiceState.LISTENING, onMic = {}) }

        compose.onNodeWithContentDescription("Parar de ouvir")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun entendendoORecadoOAnuncioEOTextoVisivel() {
        // Sem clique, a leitura de tela continua dizendo o que está acontecendo — e é o
        // texto, não o microfone, quem carrega o anúncio.
        compose.setContent { MicDockTeste(VoiceState.UNDERSTANDING, onMic = null) }

        compose.onNodeWithContentDescription("Entendendo o recado, espere um instante")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNode(anunciado).assertTextEquals("Entendendo o recado…")
    }
}

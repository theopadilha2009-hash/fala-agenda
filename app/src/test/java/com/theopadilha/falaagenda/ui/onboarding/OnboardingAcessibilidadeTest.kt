package com.theopadilha.falaagenda.ui.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tela de abertura com a fonte do sistema grande.
 *
 * O defeito: o `Column` da tela é `fillMaxSize` **sem rolagem**, e as duas mensagens de recusa
 * (microfone e avisos negados) entram no meio dele. Com a fonte grande elas empurram as duas
 * saídas da tela para fora — medido na auditoria de 05/10/2026, "Continuar" ficava com 4 dp de
 * altura em 1,5x e as duas saíam da tela em 2,0x, com
 * `onAllNodes(hasScrollAction()).size = 0`. Era o primeiro contato dela com o aplicativo, e não
 * havia como sair dele.
 *
 * Como o texto é medido de verdade: o Robolectric em modo legado devolve métricas de fonte
 * falsas (medido: o mesmo texto com 18sp sai com 36px de altura em 1,0x e 1,5x, e o
 * `LocalDensity` trocado à mão não muda o `sp` do M3), então nada aqui reproduz o defeito.
 * Com `GraphicsMode.NATIVE` a medição é a real (26px em 1,0x, 70px em 1,5x, 88px em 2,0x para a
 * mesma frase) e o `@Config(fontScale = …)` escala o `sp` — é o caminho suportado do Robolectric
 * 4.14.1 (`Config.fontScale`). Sem os dois, o teste passaria no código quebrado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
    qualifiers = "pt-rBR-w411dp-h891dp",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingAcessibilidadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fundoDoTexto = "Fala o recado. Avisa na hora. Fica só neste aparelho."

    /**
     * Monta a tela e a leva ao estado medido na auditoria: microfone e avisos negados, que é
     * quando as duas mensagens de recusa aparecem e empurram os botões para baixo. Os botões
     * não são tocados depois: quem responde é o sistema, e o teste só lê a árvore.
     */
    private fun telaComAsRecusasNaTela() {
        val settings = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                OnboardingScreen(onFinished = {}, settings = settings)
            }
        }

        compose.onNodeWithText("Começar").performClick()
        compose.waitForIdle()

        val mic = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(
                mic.requestCode,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        compose.waitForIdle()

        val avisos = shadowOf(compose.activity).lastRequestedPermission
        compose.runOnUiThread {
            compose.activity.onRequestPermissionsResult(
                avisos.requestCode,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        compose.waitForIdle()

        // A premissa do cenário: as duas mensagens estão na tela. Sem elas o teste mediria
        // outra tela e passaria no código quebrado por acidente.
        assertThat(
            compose.onAllNodes(hasText("precisa do microfone", substring = true))
                .fetchSemanticsNodes().size,
        ).isEqualTo(1)
        assertThat(
            compose.onAllNodes(hasText("permissão para mostrar avisos", substring = true))
                .fetchSemanticsNodes().size,
        ).isEqualTo(1)
    }

    private fun alturaDe(rotulo: String): Float {
        val nos = compose.onAllNodes(hasText(rotulo, substring = true)).fetchSemanticsNodes()
        assertThat(nos).hasSize(1)
        return nos.first().size.height.toFloat()
    }

    /**
     * Em 1,5x o "Continuar" tem que continuar alcançável: era o primeiro que sumia da tela
     * (a auditoria mediu 4 dp de altura). Com a rolagem ele volta inteiro — e o
     * `performScrollTo()` só funciona porque o `Column` agora tem ação de rolagem; antes ele
     * estourava com "Semantic Node has no parent layout with a Scroll SemanticsAction".
     */
    @Test
    @Config(fontScale = 1.5f)
    fun emFonteGrandeACaixaDeRecusaNaoPrendeAsSaidas() {
        telaComAsRecusasNaTela()

        assertThat(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()).isNotEmpty()

        compose.onNodeWithText("Continuar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Agora não").performScrollTo().assertIsDisplayed()
    }

    /**
     * O caso extremo da auditoria: em 2,0x as **duas** saídas ficavam com bounds
     * `(0,0,0,0)`, fora da tela, sem nenhuma rolagem. Aqui as duas precisam aparecer inteiras
     * depois de rolar, e com altura de alvo de toque (o `minHeight` de 56 dp do botão vale com
     * a fonte grande — quem cresce é o texto).
     */
    @Test
    @Config(fontScale = 2.0f)
    fun emFonteMuitoGrandeAsDuasSaidasContinuamAlcancaveis() {
        telaComAsRecusasNaTela()

        assertThat(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()).isNotEmpty()

        compose.onNodeWithText("Continuar").performScrollTo().assertIsDisplayed()
        assertThat(alturaDe("Continuar")).isAtLeast(56f)

        compose.onNodeWithText("Agora não").performScrollTo().assertIsDisplayed()
        assertThat(alturaDe("Agora não")).isAtLeast(56f)
    }

    /**
     * O outro lado do conserto, e o cuidado que a auditoria pediu: em 1,0x a rolagem não pode
     * mexer no que já estava certo. Com o `weight` dos dois espaçadores dentro de um `Column`
     * rolável sem piso de altura, os pesos colapsam, o conteúdo encolhe para o tamanho do texto
     * e o botão sobe para o meio da tela — o rodapé deixa de ser rodapé. O piso (`heightIn`) é
     * o que mantém o miolo centrado e as saídas no fundo, e este caso é o que prende isso.
     */
    @Test
    @Config(fontScale = 1.0f)
    fun emFonteNormalAsSaidasContinuamNoPeDaTela() {
        val settings = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                OnboardingScreen(onFinished = {}, settings = settings)
            }
        }

        compose.onNodeWithText(fundoDoTexto).assertIsDisplayed()

        val raiz = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val comecar = compose.onAllNodes(hasText("Começar", substring = true))
            .fetchSemanticsNodes().first().boundsInRoot
        val agoraNao = compose.onAllNodes(hasText("Agora não", substring = true))
            .fetchSemanticsNodes().first().boundsInRoot

        // O rodapé continua no rodapé: sem o piso, o "Começar" subia para o meio da tela.
        assertThat(comecar.bottom).isAtLeast(raiz.bottom * 0.75f)
        assertThat(agoraNao.bottom).isAtLeast(comecar.bottom)
        assertThat(agoraNao.bottom).isAtMost(raiz.bottom)
    }
}

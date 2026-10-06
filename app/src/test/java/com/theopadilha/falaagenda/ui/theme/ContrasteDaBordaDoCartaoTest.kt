package com.theopadilha.falaagenda.ui.theme

import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.pow

/**
 * O cartão da agenda só se distinguia do fundo por uma borda de 1,18:1 — abaixo dos 3:1 que a
 * WCAG 1.4.11 pede para o limite de um componente. O preenchimento do cartão é branco sobre o
 * creme (1,13:1) e a `Surface` do cartão tem `shadowElevation = 0.dp`/`tonalElevation = 0.dp`
 * (`UiBits.kt:42-43`), então não há sombra que sustente a separação: quem diz onde o cartão
 * começa e termina é a borda.
 *
 * A razão é calculada aqui a partir das cores do **tema de verdade** — o teste entra na
 * composição e lê `MaterialTheme.colorScheme`, em vez de repetir hex na mão. Trocar o valor no
 * tema move o teste junto, e o que fica preso é a propriedade (o limite se enxerga), não a cor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class ContrasteDaBordaDoCartaoTest {

    @get:Rule
    val compose = createComposeRule()

    /** A mesma exigência da WCAG para limite de componente e de elemento gráfico. */
    private val minimoDaBorda = 3.0

    /** Texto pequeno sobre um contêiner, o que o botão desabilitado é. */
    private val minimoDoTexto = 4.5

    private fun esquema(darkTheme: Boolean): ColorScheme {
        lateinit var cores: ColorScheme
        compose.setContent {
            FalaAgendaTheme(darkTheme = darkTheme) { cores = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        return cores
    }

    /**
     * A borda do cartão contra os dois fundos que ela encosta: o branco do próprio cartão
     * (`surface`) e o creme da página (`background`). Os dois lados contam — a borda fica entre
     * eles e é o único sinal da separação.
     */
    @Test
    fun noTemaClaroABordaDoCartaoSeEnxergaContraOsDoisFundos() {
        val cores = esquema(darkTheme = false)

        assertThat(razaoDeContraste(cores.outline, cores.surface)).isAtLeast(minimoDaBorda)
        assertThat(razaoDeContraste(cores.outline, cores.background)).isAtLeast(minimoDaBorda)
    }

    @Test
    fun noTemaEscuroABordaDoCartaoSeEnxergaContraOsDoisFundos() {
        val cores = esquema(darkTheme = true)

        assertThat(razaoDeContraste(cores.outline, cores.surface)).isAtLeast(minimoDaBorda)
        assertThat(razaoDeContraste(cores.outline, cores.background)).isAtLeast(minimoDaBorda)
    }

    /**
     * O outro lado do conserto, e o cuidado que a auditoria pediu (a cor nova não pode conflitar
     * com os outros usos de `Line`/`outline`). Dois usos dependiam de a borda ser **clara**, e
     * escurecê-la os apagava:
     *
     * - o disco do microfone sem ação (`MicMark.kt:56`) é pintado com o `outline` e o ícone por
     *   cima usa `onSurfaceVariant`: com a borda nova a razão cai de 5,0:1 para 1,8:1, e o
     *   ícone some dentro do disco;
     * - o contêiner do botão desabilitado (`UiBits.kt:81`) é o `outline` com o texto em
     *   `onSurfaceVariant` — o comentário registra 5,0:1 no claro e 5,3:1 no escuro.
     *
     * Os dois passaram a usar `outlineVariant`, que guarda os valores antigos da linha. Este
     * caso prende o número: o texto do botão desabilitado continua legível sobre ele.
     */
    @Test
    fun osUsosQueDependiamDaLinhaClaraContinuamLegiveisNoClaro() {
        val cores = esquema(darkTheme = false)

        assertThat(razaoDeContraste(cores.onSurfaceVariant, cores.outlineVariant))
            .isAtLeast(minimoDoTexto)
    }

    @Test
    fun osUsosQueDependiamDaLinhaClaraContinuamLegiveisNoEscuro() {
        val cores = esquema(darkTheme = true)

        assertThat(razaoDeContraste(cores.onSurfaceVariant, cores.outlineVariant))
            .isAtLeast(minimoDoTexto)
    }

    /**
     * E o `outlineVariant` não é decorativo de fachada: se os dois tokens ficarem com o mesmo
     * valor, os casos de cima continuam verdes e o disco do microfone volta a sumir. Aqui se
     * prende que os dois são cores distintas.
     */
    @Test
    fun oTokenDecorativoNaoEOMesmoDoContornoNoClaro() {
        val cores = esquema(darkTheme = false)

        assertThat(cores.outlineVariant).isNotEqualTo(cores.outline)
    }

    @Test
    fun oTokenDecorativoNaoEOMesmoDoContornoNoEscuro() {
        val cores = esquema(darkTheme = true)

        assertThat(cores.outlineVariant).isNotEqualTo(cores.outline)
    }

    /**
     * Quem usa cada token: o contêiner do botão desabilitado e o disco do microfone precisam
     * apontar para o `outlineVariant`. O teste lê o código-fonte porque a cor não aparece na
     * árvore de semântica — mesmo caminho do `WidgetThemeTest`, que lê o `colors.xml`.
     *
     * A comparação é sobre o fonte com os espaços normalizados (`fonteNormalizada`). A versão
     * anterior casava a string com o `\n` no fim, e por isso só pegava o texto quebrado naquele
     * ponto exato: `disabledContainerColor =\n MaterialTheme.colorScheme.outline,` — uma quebra
     * de linha no meio — passava verde usando o token errado. O `=` vira `= ` e os espaços
     * múltiplos viram um só, então a quebra de linha deixa de importar.
     */
    @Test
    fun oCodigoUsaOTokenDecorativoNosUsosQuePrecisamDoClaro() {
        val uiBits = fonteNormalizada("app/src/main/java/com/theopadilha/falaagenda/ui/components/UiBits.kt")
        val micMark = fonteNormalizada("app/src/main/java/com/theopadilha/falaagenda/ui/components/MicMark.kt")

        assertThat(uiBits).contains("disabledContainerColor = MaterialTheme.colorScheme.outlineVariant")
        assertThat(uiBits).doesNotContain("disabledContainerColor = MaterialTheme.colorScheme.outline,")
        assertThat(uiBits).doesNotContain("disabledContainerColor = MaterialTheme.colorScheme.outline)")

        assertThat(micMark).contains("else MaterialTheme.colorScheme.outlineVariant")
        assertThat(micMark).doesNotContain("else MaterialTheme.colorScheme.outline,")
    }

    /**
     * O divisor de seção da gaveta (`HomeDrawer.kt`) lê o `outlineVariant` por padrão
     * (`DividerDefaults` do M3), que no tema é a linha clara: sobre o creme da gaveta dá
     * 1,18:1 — o divisor some. O `outline` é a linha que o tema escureceu para se enxergar
     * sobre o fundo (3,26:1 no claro), e é o token que o divisor tem de usar. Sem o `color`
     * explícito ele volta para o default e some de novo — este caso é o que prende isso.
     */
    @Test
    fun oDivisorDaGavetaUsaALinhaQueSeEnxergaSobreOFundo() {
        val homeDrawer = fonteNormalizada("app/src/main/java/com/theopadilha/falaagenda/ui/home/HomeDrawer.kt")

        assertThat(homeDrawer).contains("HorizontalDivider(")
        assertThat(homeDrawer).contains("color = MaterialTheme.colorScheme.outline,")
        assertThat(homeDrawer).doesNotContain("color = MaterialTheme.colorScheme.outlineVariant,")
    }

    /**
     * Luminância relativa da WCAG 2.x e a razão entre duas cores, na ordem que a definição pede
     * (mais clara sobre mais escura, com o 0,05 somado nos dois lados).
     */
    private fun razaoDeContraste(a: Color, b: Color): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        val clara = maxOf(la, lb)
        val escura = minOf(la, lb)
        return (clara + 0.05) / (escura + 0.05)
    }

    private fun luminancia(cor: Color): Double =
        0.2126 * canal(cor.red) + 0.7152 * canal(cor.green) + 0.0722 * canal(cor.blue)

    private fun canal(valor: Float): Double {
        val c = valor.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun fonteDe(caminho: String): String =
        (listOf(File(caminho), File("../$caminho")).firstOrNull { it.isFile }
            ?: error("não achei $caminho a partir de ${File(".").absolutePath}")).readText()

    /**
     * O fonte com os espaços normalizados, para o casamento não depender de formatação: o `=`
     * ganha um espaço depois (como o Kotlin escreveria) e toda sequência de espaços — incluindo
     * quebra de linha — vira um espaço só. Sem isso, uma quebra de linha no meio da atribuição
     * escondia o uso do token errado do `doesNotContain`.
     */
    private fun fonteNormalizada(caminho: String): String =
        fonteDe(caminho).replace("=", "= ").replace(Regex("\\s+"), " ")
}

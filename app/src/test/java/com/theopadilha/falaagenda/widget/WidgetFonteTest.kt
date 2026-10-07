package com.theopadilha.falaagenda.widget

import android.app.Application
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theopadilha.falaagenda.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A fonte do widget — a superfície que ela mais vê sem abrir o aplicativo.
 *
 * O widget era a menor fonte do app: kicker de 12sp, "quando" de 13sp, título e botão de 16sp,
 * contra 17-18sp do corpo de texto no Compose (`Theme.kt:82-83`). Aqui se prende o tamanho novo
 * e a consequência dele, que é o que a auditoria pediu para medir: o `RemoteViews` não faz
 * reflow como o Compose, e o texto que cresce tem que caber no que o widget recebe.
 *
 * A medição é a de verdade: `GraphicsMode.NATIVE` (no modo legado o Robolectric devolve métricas
 * de fonte falsas) e o layout inflado e medido **na geometria que o aparelho entrega** — não no
 * `minWidth` declarado, que não é o tamanho de nenhum aparelho.
 *
 * A partir do Android 12 quem manda é `targetCellWidth`/`targetCellHeight`, e eles **ignoram**
 * `minWidth`/`minHeight` (doc oficial: "If defined, these attributes are used instead of
 * minWidth or minHeight"). No ≤11 não existem células declaradas: o launcher sobe o
 * `minWidth`/`minHeight` para a célula da tabela oficial. Os dois regimes são medidos aqui, e
 * as células declaradas têm que bater com o mínimo declarado — senão o XML conta duas histórias
 * e um dos regimes fica sem espaço (era o caso: `minWidth=150dp` = 3 células contra
 * `targetCellWidth=2`, e o Android 12+ entregava 110dp, truncando o título).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetFonteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** O kicker e o "quando" são secundários; o título e o "Falar" são o que ela lê de longe. */
    private val minimoSecundario = 16f
    private val minimoPrincipal = 18f

    private val xmlDoWidget: String by lazy {
        val arquivo = File("app/src/main/res/xml/agenda_widget_info.xml").takeIf { it.isFile }
            ?: File("../app/src/main/res/xml/agenda_widget_info.xml")
        arquivo.readText()
    }

    private fun atributoDp(nome: String): Int =
        Regex("""android:$nome="(\d+)dp"""").find(xmlDoWidget)!!.groupValues[1].toInt()

    private fun atributoCelula(nome: String): Int =
        Regex("""android:$nome="(\d+)"""").find(xmlDoWidget)!!.groupValues[1].toInt()

    /**
     * A tabela oficial de células ("Widget sizes and grid cells", medida num Pixel 4):
     * a largura de `n` células é `73n - 16` dp e a altura de `m` células é `118m - 16` dp.
     * É a conta que o launcher faz com o `minWidth`/`minHeight` no Android ≤11 — o tamanho
     * real de um aparelho, não o `minWidth` declarado (que é o piso, não o que se recebe).
     */
    private fun celulasParaLarguraDp(dp: Int): Int =
        (1..5).first { 73 * it - 16 >= dp }

    private fun celulasParaAlturaDp(dp: Int): Int =
        (1..5).first { 118 * it - 16 >= dp }

    private data class Geometria(val nome: String, val larguraDp: Int, val alturaDp: Int) {
        override fun toString(): String = "$nome ($larguraDp x $alturaDp dp)"
    }

    /** O que o Android 12+ entrega: as células declaradas, ignorando o `minWidth`/`minHeight`. */
    private val geometriaAndroid12 = Geometria(
        nome = "Android 12+",
        larguraDp = 73 * atributoCelula("targetCellWidth") - 16,
        alturaDp = 118 * atributoCelula("targetCellHeight") - 16,
    )

    /** O que o Android ≤11 entrega: o `minWidth`/`minHeight` subindo para a célula da tabela. */
    private val geometriaAndroid11 = Geometria(
        nome = "Android ≤11",
        larguraDp = 73 * celulasParaLarguraDp(atributoDp("minWidth")) - 16,
        alturaDp = 118 * celulasParaAlturaDp(atributoDp("minHeight")) - 16,
    )

    private data class Estado(val nome: String, val titulo: String, val quando: String)

    private val estadosReais = listOf(
        // O vazio é o texto mais longo do "quando" — "Toque para abrir a agenda" sobe para 3
        // linhas em célula estreita e é ele quem aperta o título (que é quem cede espaço).
        Estado("vazio", "Nada marcado", "Toque para abrir a agenda"),
        Estado("próxima", "Tomar remédio de pressão", "Hoje · 08:00"),
        Estado("atrasada", "Tomar remédio", "Atrasada — Ontem · 08:00"),
    )

    /**
     * O layout do widget inflado e medido na geometria que o aparelho entrega, com o conteúdo
     * real de cada estado. O título tem `layout_weight = 1` e é ele quem cede espaço; o
     * `layout_weight` só distribui se o `measure`/`layout` acontecerem no tamanho certo.
     */
    private fun layoutMedido(
        geometria: Geometria,
        titulo: String = "Tomar remédio de pressão",
        quando: String = "Hoje · 08:00",
    ): LinearLayout {
        val raiz = LayoutInflater.from(context)
            .inflate(R.layout.widget_agenda, null) as LinearLayout
        raiz.findViewById<TextView>(R.id.widget_title).text = titulo
        raiz.findViewById<TextView>(R.id.widget_when).text = quando

        val densidade = context.resources.displayMetrics.density
        val largura = (geometria.larguraDp * densidade).toInt()
        val altura = (geometria.alturaDp * densidade).toInt()
        raiz.measure(
            View.MeasureSpec.makeMeasureSpec(largura, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(altura, View.MeasureSpec.EXACTLY),
        )
        raiz.layout(0, 0, largura, altura)
        return raiz
    }

    private fun campo(raiz: LinearLayout, id: Int): TextView = raiz.findViewById(id)

    private fun cortado(visao: TextView): Int =
        visao.layout.getEllipsisCount(visao.lineCount - 1)

    private fun spDe(raiz: LinearLayout, id: Int): Float =
        raiz.findViewById<TextView>(id).textSize / context.resources.displayMetrics.scaledDensity

    private fun cortadoDoTitulo(geometria: Geometria, estado: Estado): Int =
        cortado(campo(layoutMedido(geometria, titulo = estado.titulo, quando = estado.quando), R.id.widget_title))

    private fun cortadoDoQuando(geometria: Geometria, estado: Estado): Int =
        cortado(campo(layoutMedido(geometria, titulo = estado.titulo, quando = estado.quando), R.id.widget_when))

    @Test
    fun aFonteDoWidgetNaoEAMenorDoAplicativo() {
        val raiz = layoutMedido(geometriaAndroid12)

        assertThat(spDe(raiz, R.id.widget_kicker)).isAtLeast(minimoSecundario)
        assertThat(spDe(raiz, R.id.widget_when)).isAtLeast(minimoSecundario)
        assertThat(spDe(raiz, R.id.widget_title)).isAtLeast(minimoPrincipal)
        assertThat(spDe(raiz, R.id.widget_speak)).isAtLeast(minimoPrincipal)
    }

    /**
     * As células declaradas e o mínimo declarado têm que descrever o **mesmo** tamanho. Era a
     * incoerência que escondia o defeito: `minWidth = 150dp` pede 3 células no ≤11, mas
     * `targetCellWidth = 2` pedia 2 no 12+ — o aparelho dela (13+) entregava 110dp e o título
     * saía com reticências, justamente o defeito que o widget dizia ter fechado.
     */
    @Test
    fun asCelulasDeclaradasBatemComOMinimoDeclarado() {
        assertThat(atributoCelula("targetCellWidth"))
            .isEqualTo(celulasParaLarguraDp(atributoDp("minWidth")))
        assertThat(atributoCelula("targetCellHeight"))
            .isEqualTo(celulasParaAlturaDp(atributoDp("minHeight")))
    }

    /**
     * O título da próxima tarefa ("Tomar remédio de pressão") é o texto que a auditoria mediu
     * truncado, e ele tem `maxLines = 2`/`ellipsize = end` de propósito — mas não pode chegar a
     * usá-los no tamanho que o aparelho entrega.
     */
    @Test
    fun noAndroid12OTituloDaProximaTarefaNaoSaiCortado() {
        val titulo = campo(layoutMedido(geometriaAndroid12), R.id.widget_title)

        assertThat(titulo.lineCount).isAtLeast(1)
        assertThat(cortado(titulo)).isEqualTo(0)
    }

    @Test
    fun noAndroid11OTituloDaProximaTarefaNaoSaiCortado() {
        val titulo = campo(layoutMedido(geometriaAndroid11), R.id.widget_title)

        assertThat(titulo.lineCount).isAtLeast(1)
        assertThat(cortado(titulo)).isEqualTo(0)
    }

    /**
     * O título é quem cede espaço (`height = 0dp` com `layout_weight = 1`), e é ele que some
     * quando a parte fixa cresce. O colapso acontece no **estado vazio**, e não no da próxima
     * tarefa: no vazio o "quando" ("Toque para abrir a agenda") sobe para 3 linhas em célula
     * estreita e come a altura que sobra para o título — medido, o título caía para 12px contra
     * uma linha de 21px. O guarda antigo media o estado da próxima tarefa, onde o título tem
     * 50px e sempre passa: estava apontado para o estado errado.
     */
    @Test
    fun noAndroid12OEstadoVazioNaoColapsaOTitulo() {
        val raiz = layoutMedido(
            geometriaAndroid12,
            titulo = "Nada marcado",
            quando = "Toque para abrir a agenda",
        )
        val titulo = campo(raiz, R.id.widget_title)

        assertThat(titulo.lineHeight).isGreaterThan(0)
        assertThat(titulo.height).isAtLeast(titulo.lineHeight)
    }

    @Test
    fun noAndroid11OEstadoVazioNaoColapsaOTitulo() {
        val raiz = layoutMedido(
            geometriaAndroid11,
            titulo = "Nada marcado",
            quando = "Toque para abrir a agenda",
        )
        val titulo = campo(raiz, R.id.widget_title)

        assertThat(titulo.lineHeight).isGreaterThan(0)
        assertThat(titulo.height).isAtLeast(titulo.lineHeight)
    }

    /**
     * Nenhum dos estados reais pode sair cortado em nenhum dos dois regimes — nem o "quando"
     * mais longo, nem o kicker mais longo ("Atrasada").
     */
    @Test
    fun nenhumEstadoSaiCortadoNoAndroid12() {
        for (estado in estadosReais) {
            assertWithMessage("título do estado ${estado.nome} em $geometriaAndroid12")
                .that(cortadoDoTitulo(geometriaAndroid12, estado))
                .isEqualTo(0)
            assertWithMessage("quando do estado ${estado.nome} em $geometriaAndroid12")
                .that(cortadoDoQuando(geometriaAndroid12, estado))
                .isEqualTo(0)
        }
    }

    @Test
    fun nenhumEstadoSaiCortadoNoAndroid11() {
        for (estado in estadosReais) {
            assertWithMessage("título do estado ${estado.nome} em $geometriaAndroid11")
                .that(cortadoDoTitulo(geometriaAndroid11, estado))
                .isEqualTo(0)
            assertWithMessage("quando do estado ${estado.nome} em $geometriaAndroid11")
                .that(cortadoDoQuando(geometriaAndroid11, estado))
                .isEqualTo(0)
        }
    }

    /**
     * O botão "Falar" é a ação principal do widget e o alvo de toque tem que continuar com 56dp
     * (`minHeight` do XML, que vale com `wrap_content`) — nos dois regimes, porque em célula
     * estreita é ele o primeiro a ser cortado pela base do root.
     */
    @Test
    fun oBotaoFalarContinuaComAlvoDeToque() {
        val minimo = (56 * context.resources.displayMetrics.density).toInt()

        assertThat(campo(layoutMedido(geometriaAndroid12), R.id.widget_speak).height)
            .isAtLeast(minimo)
        assertThat(campo(layoutMedido(geometriaAndroid11), R.id.widget_speak).height)
            .isAtLeast(minimo)
    }
}

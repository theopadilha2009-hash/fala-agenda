package com.theopadilha.falaagenda.widget

import android.app.Application
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
 * de fonte falsas) e o layout inflado e medido no tamanho declarado em `agenda_widget_info.xml`.
 * O tamanho mínimo não é repetido aqui: é lido do XML, e é isso que prende os dois juntos — baixar
 * o `minWidth` sem remedir quebra este teste.
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

    private val tamanhoMinimoDeclarado: Pair<Int, Int> by lazy {
        val xml = File("app/src/main/res/xml/agenda_widget_info.xml").takeIf { it.isFile }
            ?: File("../app/src/main/res/xml/agenda_widget_info.xml")
        val texto = xml.readText()
        val largura = Regex("""android:minWidth="(\d+)dp"""").find(texto)!!.groupValues[1].toInt()
        val altura = Regex("""android:minHeight="(\d+)dp"""").find(texto)!!.groupValues[1].toInt()
        largura to altura
    }

    /**
     * O layout do widget inflado e medido no tamanho mínimo que ele declara, com o conteúdo que
     * ele mostra de verdade. Os quatro campos são os estados reais: o vazio (que é o texto mais
     * longo do "quando"), a próxima tarefa e a atrasada (o kicker mais longo, "Atrasada").
     */
    private fun layoutMedido(
        titulo: String = "Tomar remédio de pressão",
        quando: String = "Hoje · 08:00",
    ): LinearLayout {
        val raiz = LayoutInflater.from(context)
            .inflate(R.layout.widget_agenda, null) as LinearLayout
        raiz.findViewById<TextView>(R.id.widget_title).text = titulo
        raiz.findViewById<TextView>(R.id.widget_when).text = quando

        val (larguraDp, alturaDp) = tamanhoMinimoDeclarado
        val largura = (larguraDp * context.resources.displayMetrics.density).toInt()
        val altura = (alturaDp * context.resources.displayMetrics.density).toInt()
        raiz.measure(
            View.MeasureSpec.makeMeasureSpec(largura, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(altura, View.MeasureSpec.EXACTLY),
        )
        raiz.layout(0, 0, largura, altura)
        return raiz
    }

    private fun campo(raiz: LinearLayout, id: Int): TextView = raiz.findViewById(id)

    private fun spDe(id: Int): Float =
        layoutMedido().findViewById<TextView>(id).textSize /
            context.resources.displayMetrics.scaledDensity

    @Test
    fun aFonteDoWidgetNaoEAMenorDoAplicativo() {
        assertThat(spDe(R.id.widget_kicker)).isAtLeast(minimoSecundario)
        assertThat(spDe(R.id.widget_when)).isAtLeast(minimoSecundario)
        assertThat(spDe(R.id.widget_title)).isAtLeast(minimoPrincipal)
        assertThat(spDe(R.id.widget_speak)).isAtLeast(minimoPrincipal)
    }

    /**
     * O título é quem cede espaço (`height = 0dp` com `layout_weight = 1`), e era ele que sumia
     * quando a parte fixa crescia: em 2 células o `agenda_widget_info.xml` registra que ele
     * encolhia até zero. Com a fonte nova isso volta a acontecer se a largura mínima ficar em
     * 110dp — medido, o título colapsava para 12px contra uma linha de 21px. Este caso é o que
     * garante que o tamanho declarado no XML dá altura para o título existir.
     */
    @Test
    fun oTituloTemAlturaDePeloMenosUmaLinhaNoTamanhoMinimo() {
        val titulo = campo(layoutMedido(), R.id.widget_title)

        assertThat(titulo.lineHeight).isGreaterThan(0)
        assertThat(titulo.height).isAtLeast(titulo.lineHeight)
    }

    /**
     * E o texto maior não pode sair cortado com reticências no tamanho mínimo declarado: o
     * nome da próxima tarefa tem que aparecer inteiro. O título tem `maxLines = 2` e
     * `ellipsize = end` de propósito, mas não pode chegar a usá-los aqui.
     */
    @Test
    fun oTextoDoTituloNaoSaiCortadoNoTamanhoMinimo() {
        val titulo = campo(layoutMedido(titulo = "Tomar remédio de pressão"), R.id.widget_title)

        assertThat(titulo.lineCount).isAtLeast(1)
        assertThat(titulo.layout.getEllipsisCount(titulo.lineCount - 1)).isEqualTo(0)
    }

    /**
     * O "quando" é o texto mais longo do widget no estado vazio ("Toque para abrir a agenda") e
     * o kicker mais longo é "Atrasada". Nenhum dos dois pode sair cortado.
     */
    @Test
    fun oKickerEOResumoNaoSaemCortadosNoTamanhoMinimo() {
        val vazio = layoutMedido(titulo = "Nada marcado", quando = "Toque para abrir a agenda")
        val quando = campo(vazio, R.id.widget_when)
        assertThat(quando.layout.getEllipsisCount(quando.lineCount - 1)).isEqualTo(0)

        val atrasada = campo(
            layoutMedido(titulo = "Tomar remédio", quando = "Atrasada — Ontem · 08:00"),
            R.id.widget_kicker,
        )
        assertThat(atrasada.layout.getEllipsisCount(atrasada.lineCount - 1)).isEqualTo(0)
    }

    /**
     * O botão "Falar" é a ação principal do widget e o alvo de toque tem que continuar com 56dp
     * (`minHeight` do XML, que vale com `wrap_content`).
     */
    @Test
    fun oBotaoFalarContinuaComAlvoDeToque() {
        val botao = campo(layoutMedido(), R.id.widget_speak)

        assertThat(botao.height)
            .isAtLeast((56 * context.resources.displayMetrics.density).toInt())
    }
}

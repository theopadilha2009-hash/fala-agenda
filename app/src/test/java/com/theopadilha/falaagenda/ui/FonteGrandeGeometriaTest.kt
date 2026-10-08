package com.theopadilha.falaagenda.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.SaveResult
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.ui.capture.QuickConfirmDialog
import com.theopadilha.falaagenda.ui.home.HomeScreen
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A janela pequena da cacada: 360dp x 800dp em densidade 2 (720 x 1600 px). */
const val JANELA_PEQUENA = "w360dp-h800dp-xhdpi"

/** O aparelho dela: 411dp x 891dp em densidade 3 (1233 x 2673 px). */
const val JANELA_DELA = "pt-rBR-w411dp-h891dp-xxhdpi"

/**
 * A fonte grande do sistema — o ajuste que ela mais usa, e o que faz sumir o caminho de um toque.
 *
 * A cacada adversarial de 07/10/2026 mediu 32 geometrias (4 escalas x 2 janelas) e achou dois P1,
 * os dois com a lista ou o botao saindo da tela:
 *
 * - F1, na home: a `MicDock` e o `bottomBar` do `Scaffold` e nao tinha teto nem rolagem. Em 2,0x
 *   o conteudo dela mede 983 px dos 1600 da janela e sobram 228 px para a lista — menos que um
 *   cartao (280 px), entao nenhuma tarefa aparece inteira e o "Concluir" do cartao sai da arvore
 *   de toque (`boundsInRoot = (0,0,0,0)`). O `CartaoAtrasadoConcluiTest` mede esse botao so na
 *   escala default: a regressao em fonte grande era invisivel para os 761 testes de app.
 * - F2, na caixa "Pode salvar?": o `AlertDialog` do M3 nao da rolagem ao conteudo nem reserva o
 *   rodape, e o que estoura empurra os botoes para fora da janela. Em 1,5x — e em 2,0x no
 *   aparelho dela — o "Mudar" fica com 0 px de altura. E o unico caminho para corrigir o recado:
 *   o `onDismissRequest` so cancela e o "Salvar" grava o que esta.
 *
 * Como a medicao e real: `GraphicsMode.NATIVE` (no modo legado o Robolectric devolve metricas de
 * fonte falsas) e `@Config(fontScale = ...)`, o caminho suportado do Robolectric 4.14.1. Sem os
 * dois o teste passaria no codigo quebrado — e a mesma receita de `OnboardingAcessibilidadeTest`.
 *
 * O que este arquivo prende, e por que a assercao e geometrica: "nao crashou" passa no codigo
 * quebrado. O que importa e a altura real do botao e o lugar dele na janela. Em 1,0x os numeros
 * sao presos por igualdade: o conserto nao pode mexer no que ja estava certo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
    qualifiers = JANELA_PEQUENA,
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FonteGrandeGeometriaTest {

    private val escopo = TestViewModelScopeRule()
    private val compose = createComposeRule()

    /** A regra do escopo e a mais externa — ver [TestViewModelScopeRule]. */
    @get:Rule
    val regras: RuleChain = RuleChain.outerRule(escopo).around(compose)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(context)
    }

    private val density: Float
        get() = context.resources.displayMetrics.density

    private fun px(dp: Float): Float = dp * density

    private fun janelaEmPx(): Rect = Rect(
        0f,
        0f,
        context.resources.displayMetrics.widthPixels.toFloat(),
        context.resources.displayMetrics.heightPixels.toFloat(),
    )

    private fun imprimir(escala: String, janela: String, rotulo: String, r: Rect) {
        println(
            "MEDIDO [$janela] escala=$escala $rotulo " +
                "layout=${r.width.toInt()}x${r.height.toInt()} " +
                "bounds=(${r.left.toInt()}, ${r.top.toInt()}, ${r.right.toInt()}, ${r.bottom.toInt()})",
        )
    }

    private fun esperar(descricao: String, condicao: () -> Boolean) {
        repeat(100) {
            if (condicao()) return
            Thread.sleep(100)
        }
        throw AssertionError("Esperei $descricao e nao aconteceu.")
    }

    // ------------------------------------------------------------------ home (F1)

    /**
     * Uma tarefa futura e PENDING, com o atalho "Concluir" no cartao. Um horario ja passado nasce
     * MISSED (`DraftSchedule.bornWithoutReminder`) e desce para as secoes de atraso, que nao sao o
     * assunto desta medicao.
     */
    private fun semearPendente(titulo: String): SaveResult = runBlocking {
        container.tasks.saveDraft(
            ParsedTaskDraft(
                title = titulo,
                localDate = LocalDate.now().plusDays(1),
                localTime = LocalTime.of(9, 0),
                recurrence = RecurrenceRule(RecurrenceKind.NONE),
                confidence = 1.0,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = titulo,
            ),
        )
    }

    private fun comporHome(viewModel: HomeViewModel) {
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
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
        }
    }

    /**
     * A lista do dia — a `LazyColumn` da home. E a unica area rolavel da tela que **nao** e o
     * dock: com a fonte grande a `MicDock` tambem passa a rolar (e o conserto), e um
     * `hasScrollAction()` cru acharia as duas. O dock e reconhecido pelo "Escrever tarefa",
     * que so ele tem.
     */
    private fun noDaLista() = compose.onNode(
        hasScrollAction() and hasAnyDescendant(hasText("Escrever tarefa")).not(),
    )

    private fun boundsDaLista(): Rect = noDaLista().fetchSemanticsNode().boundsInRoot

    private fun boundsDoTexto(texto: String): Rect =
        compose.onAllNodesWithText(texto).fetchSemanticsNodes().first().boundsInRoot

    /**
     * O tamanho **proprio** do no, medido no lugar dele na arvore — nao o que sobra depois do
     * recorte da janela.
     *
     * Esta e a distincao que este arquivo existe para fazer. `boundsInRoot` e o retangulo do no
     * **cortado** pelas bordas de rolagem: com a fonte em 2,0x o cartao e mais alto que a lista,
     * entao o "Concluir" que esta no fim dele aparece com a altura que sobrou — 60 px — mesmo
     * tendo os mesmos 119 px de altura de layout que tem quando cabe inteiro. Ou seja: a altura
     * recortada depende do que esta acima do botao na tela, e um piso em cima dela mede a lista,
     * nao o botao.
     *
     * O defeito real do pre-fix nao era "o botao ficou curto": era `boundsInRoot = (0,0,0,0)`,
     * porque a lista de 228 px nem compunha o cartao inteiro e o `TextButton` saia da arvore de
     * toque. `size` e o mesmo (276x119) nos dois estados — quem separa o defeito do conserto e o
     * lugar do no, nao a altura dele.
     */
    private fun tamanhoDoTexto(texto: String): IntSize =
        compose.onAllNodesWithText(texto).fetchSemanticsNodes().first().size

    /**
     * O cenario dela: a lista do dia tem uma tarefa, e o atalho "Concluir" tem que continuar
     * tocavel. A assercao e a geometria real — altura > 0 e o botao dentro da janela —, nao
     * "a tela nao crashou".
     */
    private fun medirHome(janela: String, escala: String) {
        semearPendente("Tomar remedio")
        comporHome(escopo.rastrear(HomeViewModel(container)))
        // A espera e pela manchete, que diz o titulo da proxima tarefa: ela fica no topo da
        // tela e e composta em qualquer escala. Esperar por um texto da lista falharia
        // justamente no caso que este arquivo existe para medir — em 2,0x o cartao fica
        // abaixo da dobra e a `LazyColumn` nem o compoe.
        esperar("a agenda chegar do banco") {
            compose.onAllNodesWithText("Tomar remedio", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Rola ate o cartao como ela faria: e o gesto que a cacada mede ("Concluir" rolado).
        noDaLista().performScrollToNode(hasText("Tomar remedio"))
        compose.waitForIdle()

        val janelaPx = janelaEmPx()
        val lista = boundsDaLista()
        val concluir = boundsDoTexto("Concluir")
        imprimir(escala, janela, "lista", lista)
        imprimir(escala, janela, "dock-label", boundsDoTexto("Toque no microfone e fale"))
        imprimir(escala, janela, "dock-escrever", boundsDoTexto("Escrever tarefa"))
        imprimir(escala, janela, "Concluir", concluir)
        println(
            "MEDIDO [$janela] escala=$escala janela=${janelaPx.width.toInt()}x${janelaPx.height.toInt()} " +
                "lista/janela=${"%.1f".format(lista.height / janelaPx.height * 100)}%",
        )

        // O defeito medido foi "nenhuma tarefa aparece inteira": em 2,0x a lista ficava com
        // 228 px, menos que um cartao (280 px). O piso e esse — a lista tem que caber pelo
        // menos um cartao, senao a tarefa do dia nao existe na tela.
        assertWithMessage("a lista em $escala ($janela) tem que caber um cartao")
            .that(lista.height).isAtLeast(280f)
        // O atalho de um toque. O defeito do pre-fix e `boundsInRoot = (0,0,0,0)` em 2,0x: com a
        // lista em 228 px o cartao nao e composto inteiro e o botao sai da arvore de toque. O
        // que descreve isso e a **presenca** do no, nao a altura que a janela deixa ver: com o
        // cartao mais alto que o viewport, o "Concluir" aparece recortado pela borda de baixo —
        // 60 px dos 119 de layout, medido igual no macOS e no CI (Linux) —, e essa sobra depende
        // do que esta acima dele na tela, nao do botao. Um piso de 48 dp em cima do recorte mede
        // a lista (e a metrica de fonte do SO), nao o atalho: por isso ele falhava no codigo ja
        // consertado, com o mesmo valor nos dois SOs.
        assertWithMessage("o \"Concluir\" em $escala ($janela) nao pode sair da arvore de toque")
            .that(concluir.height).isGreaterThan(0f)
        assertWithMessage("o \"Concluir\" em $escala ($janela) tem que estar dentro da janela")
            .that(concluir.top).isAtLeast(0f)
        assertWithMessage("o \"Concluir\" em $escala ($janela) tem que estar dentro da janela")
            .that(concluir.bottom).isAtMost(janelaPx.height)
        // O alvo de toque minimo se mede no layout do botao, nao no recorte: `size` e 276x119 no
        // defeito e no conserto, entao ele nao pega a regressao sozinho — prende que o botao
        // continua com altura de alvo de toque, o que o `heightIn(min = 56.dp)` do `TextButton`
        // garante em qualquer metrica de fonte (112 px no pior caso, o piso de 56 dp).
        assertWithMessage("o \"Concluir\" em $escala ($janela) tem que manter o alvo de toque")
            .that(tamanhoDoTexto("Concluir").height.toFloat()).isAtLeast(px(48f))
        compose.onNodeWithText("Concluir").assertIsDisplayed()

        // O outro lado: em 1,0x nada muda. Os numeros sao os medidos antes do conserto, e a
        // home continua com **uma** area rolavel — o teto/rolagem do dock so existem com a
        // fonte aumentada, e e isso que impede o conserto de mexer na tela normal.
        if (escala == "1.0") {
            val listaEsperada = if (janela == "360x800") 721f else 1354f
            assertWithMessage("em 1,0x ($janela) a lista nao pode mudar de tamanho")
                .that(lista.height).isWithin(2f).of(listaEsperada)
            assertWithMessage("em 1,0x ($janela) o \"Concluir\" nao pode mudar de altura")
                .that(concluir.height).isWithin(2f).of(px(56f))
            assertWithMessage("em 1,0x ($janela) a home tem que ter uma unica area rolavel")
                .that(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()).hasSize(1)
        }
    }

    @Test
    @Config(fontScale = 1.0f)
    fun em1xNaJanelaPequenaAListaEOConcluirSobrevivem() = medirHome("360x800", "1.0")

    @Test
    @Config(fontScale = 1.3f)
    fun em1_3xNaJanelaPequenaAListaEOConcluirSobrevivem() = medirHome("360x800", "1.3")

    @Test
    @Config(fontScale = 1.5f)
    fun em1_5xNaJanelaPequenaAListaEOConcluirSobrevivem() = medirHome("360x800", "1.5")

    @Test
    @Config(fontScale = 2.0f)
    fun em2xNaJanelaPequenaAListaEOConcluirSobrevivem() = medirHome("360x800", "2.0")

    @Test
    @Config(fontScale = 1.0f, qualifiers = JANELA_DELA)
    fun em1xNaJanelaDelaAListaEOConcluirSobrevivem() = medirHome("411x891", "1.0")

    @Test
    @Config(fontScale = 1.3f, qualifiers = JANELA_DELA)
    fun em1_3xNaJanelaDelaAListaEOConcluirSobrevivem() = medirHome("411x891", "1.3")

    @Test
    @Config(fontScale = 1.5f, qualifiers = JANELA_DELA)
    fun em1_5xNaJanelaDelaAListaEOConcluirSobrevivem() = medirHome("411x891", "1.5")

    @Test
    @Config(fontScale = 2.0f, qualifiers = JANELA_DELA)
    fun em2xNaJanelaDelaAListaEOConcluirSobrevivem() = medirHome("411x891", "2.0")

    // --------------------------------------------------- caixa "Pode salvar?" (F2/F3)

    /**
     * O rascunho cheio da cacada: titulo longo, data que a regra descarta, recorrencia, duas
     * notas, valor e observacao. E o volume que empurra o "Mudar" para fora da janela.
     */
    private fun rascunhoCheio() = ParsedTaskDraft(
        title = "Tomar remedio de pressao e o remedio do coracao",
        localDate = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY)),
        localTime = LocalTime.of(9, 0),
        recurrence = RecurrenceRule(RecurrenceKind.WEEKDAYS),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = "tomar remedio de pressao e o remedio do coracao sabado dias uteis as nove",
        notes = listOf(
            "Entendi \"sabado\" no lugar do proximo dia util.",
            "Nao consegui entender o valor que voce falou.",
        ),
        amountCents = 2500,
        observation = "Tomar com um copo de agua, depois do cafe da manha.",
    )

    private fun comporCaixa() {
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                QuickConfirmDialog(
                    draft = rascunhoCheio(),
                    saving = false,
                    onSave = {},
                    onEdit = {},
                    onCancel = {},
                )
            }
        }
    }

    /**
     * "Mudar" e o unico caminho para corrigir o recado — e em 1,5x ele saia da janela. A assercao
     * e a da cacada: `assertIsDisplayed()`, mais os numeros.
     */
    private fun medirCaixa(janela: String, escala: String) {
        comporCaixa()
        val mudar = boundsDoTexto("Mudar")
        val salvar = boundsDoTexto("Salvar")
        val cancelar = boundsDoTexto("Cancelar")
        imprimir(escala, janela, "Mudar", mudar)
        imprimir(escala, janela, "Salvar", salvar)
        imprimir(escala, janela, "Cancelar", cancelar)
        println(
            "MEDIDO [$janela] escala=$escala sobreposicao-salvar-cancelar=" +
                "${(salvar.bottom - cancelar.top).toInt()}px",
        )

        compose.onNodeWithText("Mudar").assertIsDisplayed()

        // F3 (P2, ainda aberto): em 2,0x os dois botoes do rodape se sobrepoem — medido 8 px na
        // janela pequena e 12 px na dela, e o mesmo antes do conserto (o defeito e do rodape do
        // M3, nao do conteudo). Nao esta consertado aqui; o que se prende e que **nao piorou**,
        // para o numero medido da cacada virar teto em vez de folclore.
        val sobreposicao = salvar.bottom - cancelar.top
        val tetoDaCacada = if (janela == "360x800") 8f else 12f
        if (escala == "2.0") {
            assertWithMessage("a sobreposicao em 2,0x ($janela) nao pode piorar")
                .that(sobreposicao).isAtMost(tetoDaCacada)
        } else {
            assertWithMessage("abaixo de 2,0x os botoes nao podem se sobrepor ($escala, $janela)")
                .that(sobreposicao).isAtMost(0f)
        }

        // O outro lado: em 1,0x a caixa nao pode mudar de tamanho.
        if (escala == "1.0" && janela == "360x800") {
            assertWithMessage("em 1,0x o \"Mudar\" da caixa nao pode mudar de tamanho")
                .that(mudar.height).isWithin(2f).of(112f)
        }
    }

    @Test
    @Config(fontScale = 1.0f)
    fun em1xNaJanelaPequenaOMudarDaCaixaContinuaTocavel() = medirCaixa("360x800", "1.0")

    @Test
    @Config(fontScale = 1.3f)
    fun em1_3xNaJanelaPequenaOMudarDaCaixaContinuaTocavel() = medirCaixa("360x800", "1.3")

    @Test
    @Config(fontScale = 1.5f)
    fun em1_5xNaJanelaPequenaOMudarDaCaixaContinuaTocavel() = medirCaixa("360x800", "1.5")

    @Test
    @Config(fontScale = 2.0f)
    fun em2xNaJanelaPequenaOMudarDaCaixaContinuaTocavel() = medirCaixa("360x800", "2.0")

    @Test
    @Config(fontScale = 1.0f, qualifiers = JANELA_DELA)
    fun em1xNaJanelaDelaOMudarDaCaixaContinuaTocavel() = medirCaixa("411x891", "1.0")

    @Test
    @Config(fontScale = 1.3f, qualifiers = JANELA_DELA)
    fun em1_3xNaJanelaDelaOMudarDaCaixaContinuaTocavel() = medirCaixa("411x891", "1.3")

    @Test
    @Config(fontScale = 1.5f, qualifiers = JANELA_DELA)
    fun em1_5xNaJanelaDelaOMudarDaCaixaContinuaTocavel() = medirCaixa("411x891", "1.5")

    @Test
    @Config(fontScale = 2.0f, qualifiers = JANELA_DELA)
    fun em2xNaJanelaDelaOMudarDaCaixaContinuaTocavel() = medirCaixa("411x891", "2.0")
}

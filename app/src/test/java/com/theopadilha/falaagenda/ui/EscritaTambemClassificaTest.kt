package com.theopadilha.falaagenda.ui

import android.app.Application
import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.capture.WriteStep
import com.theopadilha.falaagenda.ui.capture.writeStepFor
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * A tela "Escrever tarefa" passa pela MESMA classificação de intenção que a fala da home.
 *
 * O #48 criou a camada de intenção e a ligou em `HomeViewModel.understandSpeech`, mas deixou
 * uma segunda porta de texto aberta: a rota `"write"` chamava `speech.understand` direto, o
 * caminho cru da captura. Escrever "cancela o médico" — o caminho que o próprio app oferece
 * quando o microfone não está disponível, e a rota do ditado pelo teclado — criava a tarefa
 * "Cancela o médico", e ela acreditava ter cancelado: a consulta continuava marcada. É o
 * mesmo defeito do #48, pela outra porta.
 *
 * O que se prova aqui é o gesto que a rota chama ([confirmWrite]) contra um [HomeViewModel]
 * de verdade: um comando escrito não cria tarefa, a resposta fica publicada para a home, e a
 * tela de escrita não fica esperando um rascunho que não vem. A tela de escrita (o texto
 * sair legível) é da composição, e esta suíte não roda Compose — o `FalaAgendaRoot` precisa
 * do `NavHost` e do Room para renderizar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class EscritaTambemClassificaTest {

    /**
     * Dona do `Dispatchers.Main` e do escopo do `HomeViewModel`: sem o cancelamento no fim do
     * caso, o `stateIn(…, WhileSubscribed(5_000))` da home, a sessão de fala e o `withContext(IO)`
     * do `init` seguem armados num timer de verdade e cruzam a fronteira do teste. Ver
     * [TestViewModelScopeRule].
     */
    @get:Rule
    val escopo = TestViewModelScopeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        container = AppContainer(context)
        viewModel = escopo.rastrear(HomeViewModel(container))
    }

    // --- o defeito: comando escrito não vira tarefa ---------------------------------

    @Test
    fun comandoEscritoSaiDaTelaENaoCriaTarefa() {
        val nav = navEspiao()
        confirmWrite(nav, viewModel, "cancela o médico")

        val agenda = agenda()
        assertThat(agenda.today).isEmpty()
        assertThat(agenda.upcoming).isEmpty()
        assertThat(proximoRecado()).contains("Não achei")
        // A tela de escrita não fica esperando um rascunho que não vem: sem isto a rota
        // "write" seguiria na frente da resposta publicada na home, e ela não veria nada.
        assertThat(writeStepFor(viewModel.speech.state.value)).isEqualTo(WriteStep.Waiting)
        // E ela precisa estar VENDO a resposta: sem o pop a rota "write" continua na frente
        // e o snackbar da home — que é onde o recado aparece — não está visível.
        assertThat(nav.pops).isEqualTo(1)
    }

    /**
     * O caminho inverso, que é o que prova que o pop é do comando e não de todo
     * `confirmWrite`: um recado escrito segue para a confirmação e a rota NÃO pode sair.
     */
    @Test
    fun recadoEscritoSegueNaTelaESemPop() {
        val nav = navEspiao()
        confirmWrite(nav, viewModel, "tomar remédio amanhã às 9h")

        assertThat(nav.pops).isEqualTo(0)
    }

    @Test
    fun perguntaEscritaRespondeEmVezDeCriarTarefa() {
        confirmWrite(nav(), viewModel, "o que tenho hoje?")

        val agenda = agenda()
        assertThat(agenda.today).isEmpty()
        assertThat(agenda.upcoming).isEmpty()
        assertThat(proximoRecado()).contains("Não tem nada marcado para hoje")
    }

    @Test
    fun apagarEscritoDizQueNaoSabeEmVezDeCriarTarefa() {
        confirmWrite(nav(), viewModel, "apaga isso")

        val agenda = agenda()
        assertThat(agenda.today + agenda.upcoming).isEmpty()
        assertThat(proximoRecado()).contains("Ainda não sei apagar")
    }

    /**
     * "apaga o remédio" pela porta da escrita, com a rotina na agenda: é comando, a tarefa sai
     * e a rota "write" sai da frente para ela ver o desfecho na home. Sem o pop, a resposta
     * ficaria publicada numa tela que não está visível.
     */
    @Test
    fun apagarPeloNomeEscritoApagaENaoCriaTarefa() {
        val nav = navEspiao()
        val salvo = runBlocking { container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().plusDays(1))) }

        confirmWrite(nav, viewModel, "apaga o remédio")

        assertThat(nav.pops).isEqualTo(1)
        // A resolução do alvo lê o banco: espera a exclusão aterrissar antes de olhar a agenda.
        runBlocking {
            withTimeout(TEMPO_LIMITE) {
                var atual = agenda()
                while (atual.find(salvo.occurrence.id) != null) {
                    kotlinx.coroutines.delay(20)
                    atual = agenda()
                }
            }
        }
        assertThat(agenda().find(salvo.occurrence.id)).isNull()
        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
    }

    /**
     * O outro lado: "apaga a luz" sem alvo na agenda é um RECADO, e a tarefa nasce.
     *
     * A decisão de comando-vs-recado do `EraseNamed` depende da agenda, e a leitura do banco é
     * assíncrona — `confirmWrite` não pode esperar por ela. Então o `understandSpeech` devolve
     * "comando" (a rota sai da frente, como em `Cancel`) e, quando não há alvo, o rascunho
     * volta pela sessão e a HOME o leva para a confirmação. O destino dela é o mesmo; o que
     * importa é que a tarefa não seja engolida.
     */
    @Test
    fun apagarPeloNomeSemAlvoEscritoViraRecadoNaConfirmacao() {
        val nav = navEspiao()

        confirmWrite(nav, viewModel, "apaga a luz")

        assertThat(nav.pops).isEqualTo(1)
        val draft = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNotNull()
        assertThat(draft!!.title.lowercase()).contains("luz")
    }

    private fun recado(titulo: String, data: LocalDate) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = LocalTime.of(8, 30),
        recurrence = RecurrenceRule(),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
    )

    // --- o recado escrito continua indo para a confirmação --------------------------

    @Test
    fun recadoEscritoSegueParaAConfirmacao() {
        confirmWrite(nav(), viewModel, "tomar remédio amanhã às 9h")

        val draft = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNotNull()
        assertThat(draft!!.title.lowercase()).contains("remédio")
        assertThat(writeStepFor(viewModel.speech.state.value)).isInstanceOf(WriteStep.Ready::class.java)
    }

    // --- o rascunho que sobrou não sequestra a próxima abertura ---------------------

    /**
     * Sair da escrita por um comando precisa do mesmo descarte do "Cancelar" (ver
     * `cancelWrite`): o rascunho que ficou na sessão manda a próxima abertura da tela para a
     * confirmação de um recado que ela não escreveu.
     */
    @Test
    fun oComandoEscritoDescartaORascunhoQueSobrou() {
        runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.understand("tomar remédio amanhã às 9h")
                viewModel.speech.state.filter { it.draft != null }.first()
            }
        }
        assertThat(writeStepFor(viewModel.speech.state.value)).isInstanceOf(WriteStep.Ready::class.java)

        confirmWrite(nav(), viewModel, "cancela o médico")

        assertThat(writeStepFor(viewModel.speech.state.value)).isEqualTo(WriteStep.Waiting)
    }

    // --- a porta única ---------------------------------------------------------------

    /** A classificação é uma só: o retorno diz se o texto seguiu o caminho de captura. */
    @Test
    fun entenderFalaDizSeFoiCaptura() {
        assertThat(viewModel.understandSpeech("cancela o médico")).isFalse()
        assertThat(viewModel.understandSpeech("o que tenho hoje?")).isFalse()
        assertThat(viewModel.understandSpeech("apaga isso")).isFalse()
        assertThat(viewModel.understandSpeech("tomar remédio amanhã às 9h")).isTrue()
    }

    // --- helpers ---------------------------------------------------------------------

    private fun nav() = NavHostController(context)

    /**
     * Um `NavController` de verdade que conta os pops. O `NavHostController` do teste
     * anterior não tinha grafo, então `popBackStack()` era um no-op silencioso: dava para
     * apagar a linha do `confirmWrite` que faz o comando voltar para a home e os testes
     * seguiam verdes — a resposta publicada ficaria numa tela que ela não está vendo.
     */
    private fun navEspiao() = object : NavController(context) {
        var pops = 0
            private set

        override fun popBackStack(): Boolean {
            pops++
            return true
        }
    }

    private fun agenda() = runBlocking { container.tasks.snapshotAgenda() }

    private fun proximoRecado(): String =
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.statusMessage.filterNotNull().first().text } }

    private companion object {
        const val TEMPO_LIMITE = 15_000L
    }
}

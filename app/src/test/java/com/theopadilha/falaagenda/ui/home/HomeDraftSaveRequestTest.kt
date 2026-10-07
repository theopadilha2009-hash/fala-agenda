package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
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
 * O desfecho de uma gravação de rascunho tem a identidade do **pedido** que a pediu, criada
 * no momento do pedido — não na conclusão, e não "a tela tal".
 *
 * Sem ela, quem casava o desfecho com a tela era um booleano da composição (`savePending`) e
 * a origem. O back do sistema popava a tela no meio da gravação (o botão Cancelar estava
 * desabilitado, o gesto do sistema não), o desfecho velho ficava no slot sem consumidor, e a
 * tela nova — recriada pelo giro, com o booleano restaurado verdadeiro — consumia o desfecho
 * *velho* como se fosse o dela: anunciava a frase antiga, saía da tela, e o desfecho novo (a
 * falha da gravação que ela acabou de pedir) não tinha mais quem o mostrasse.
 *
 * A outra metade — a tela bloquear a saída e o botão — é da composição, e esta suíte não
 * roda Compose. O que se prova aqui é o que a tela lê: o id do pedido que o desfecho
 * carrega, e o pedido que continua pendente (a tela chama isso de "Salvando…") até alguém
 * mostrar o desfecho dele.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeDraftSaveRequestTest {

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

    /** O pedido devolve a identidade que o desfecho dele carrega: é o que a tela compara. */
    @Test
    fun oDesfechoDizQualPedidoOProduziu() {
        val pedido = viewModel.saveDraft(recado(), DraftSaveOrigin.QUICK_REMIND)

        val desfecho = desfecho()
        assertThat(desfecho.requestId).isEqualTo(pedido)
        assertThat(desfecho.requestId).isNotEqualTo(NO_SAVE_REQUEST)
    }

    /**
     * Dois pedidos da mesma tela, o primeiro sem consumidor (a tela dele saiu): o desfecho
     * de um não pode ser dado como o do outro — é a troca que anunciava a frase antiga e
     * deixava a falha da gravação nova sem aviso.
     */
    @Test
    fun oDesfechoDeUmPedidoNaoValePeloDeOutro() {
        val primeiro = viewModel.saveDraft(recado(), DraftSaveOrigin.QUICK_REMIND)
        val desfechoDoPrimeiro = desfecho()

        val segundo = viewModel.saveDraft(recado("tomar água"), DraftSaveOrigin.QUICK_REMIND)
        val desfechoDoSegundo = desfechoDiferenteDe(desfechoDoPrimeiro)

        assertThat(segundo).isNotEqualTo(primeiro)
        assertThat(desfechoDoPrimeiro.requestId).isEqualTo(primeiro)
        assertThat(desfechoDoSegundo.requestId).isEqualTo(segundo)
    }

    /** Salvar uma mudança também é um pedido, com identidade própria. */
    @Test
    fun aMudancaSalvaTambemTemPedidoProprio() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        val pedido = viewModel.edit(
            id = salvo.occurrence.id,
            title = "tomar remédio",
            date = LocalDate.of(2026, 9, 28),
            time = LocalTime.of(9, 0),
            recurrence = RecurrenceRule(),
        )

        assertThat(desfecho().requestId).isEqualTo(pedido)
    }

    /**
     * Enquanto o desfecho não saiu na tela, o pedido continua pendente: é o que a tela lê
     * para a gravação "estar em voo" — botões fora da mão dela e saída bloqueada. A gravação
     * *desta* tela, e não o `busy` do ViewModel, que também fica verdadeiro para a escrita de
     * outra tela.
     */
    @Test
    fun oPedidoFicaPendenteAteODesfechoSairNaTela() {
        val pedido = viewModel.saveDraft(recado(), DraftSaveOrigin.QUICK_REMIND)
        assertThat(viewModel.pendingDraftSaves.value).contains(pedido)

        desfecho()
        // O desfecho chegou, mas ninguém o mostrou ainda: a tela continua presa a ele.
        assertThat(viewModel.pendingDraftSaves.value).contains(pedido)

        viewModel.consumeDraftSaveOutcome()
        assertThat(viewModel.pendingDraftSaves.value).doesNotContain(pedido)
    }

    /**
     * A falha tem o mesmo dono: o desfecho diz de que pedido ela é, e o pedido só deixa de
     * estar pendente quando a mensagem saiu na tela — nunca antes, senão o botão voltaria à
     * mão dela com o erro ainda por mostrar.
     */
    @Test
    fun aFalhaTambemDizDeQualPedidoElaE() {
        // Primeiro uma gravação que passa (é ela que abre o banco), e então o banco fechado.
        runBlocking { container.tasks.saveDraft(recado()) }
        container.db.close()

        val pedido = viewModel.saveDraft(recado(), DraftSaveOrigin.CONFIRM)

        val falha = desfecho() as DraftSaveOutcome.Failed
        assertThat(falha.requestId).isEqualTo(pedido)
        assertThat(falha.message).isEqualTo("Não consegui salvar o recado. Tente de novo.")
        assertThat(viewModel.pendingDraftSaves.value).contains(pedido)

        viewModel.consumeDraftSaveOutcome()
        assertThat(viewModel.pendingDraftSaves.value).doesNotContain(pedido)
    }

    /**
     * Um pedido que o ViewModel não conhece não está em voo: é o que a tela restaurada pela
     * morte do processo (o id dela sobrevive no Bundle, a gravação não) precisa saber para
     * não ficar presa em "Salvando…" — sem saída — para uma gravação que não existe mais.
     */
    @Test
    fun umPedidoQueOModeloNaoConheceNaoEstaEmVoo() {
        assertThat(viewModel.pendingDraftSaves.value).doesNotContain(7L)
        assertThat(viewModel.pendingDraftSaves.value).doesNotContain(NO_SAVE_REQUEST)
    }

    private fun desfecho(): DraftSaveOutcome =
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.draftSaveOutcome.filterNotNull().first() } }

    private fun desfechoDiferenteDe(anterior: DraftSaveOutcome): DraftSaveOutcome =
        runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.draftSaveOutcome.filterNotNull().first { it != anterior }
            }
        }

    private fun recado(titulo: String = "tomar remédio") = ParsedTaskDraft(
        title = titulo,
        localDate = LocalDate.of(2026, 9, 28),
        localTime = LocalTime.of(8, 30),
        recurrence = RecurrenceRule(RecurrenceKind.NONE),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
    )

    private companion object {
        const val TEMPO_LIMITE = 15_000L
    }
}

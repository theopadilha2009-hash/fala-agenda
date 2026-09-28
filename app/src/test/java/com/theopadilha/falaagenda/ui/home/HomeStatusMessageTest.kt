package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.SaveResult
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.QuickRemind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * O recado da última ação — "Feito.", "Tarefa excluída.", "Vai avisar amanhã às 8h." — mora
 * no ViewModel, e não no `onDone` da composição que pediu a ação.
 *
 * O `write` roda no escopo do ViewModel e atravessa o giro do aparelho; o `onDone` escrevia
 * num `MutableState` da composição já descartada, então a tarefa era concluída (ou excluída,
 * ou adiada) e o aviso caía num estado morto: ela fez a coisa e não ficou sabendo se valeu.
 * A falha tinha o mesmo destino, calada.
 *
 * A outra metade — mostrar o recado inteiro antes de dar por consumido — é da composição, e
 * esta suíte não roda Compose. O que se prova aqui é o que a home lê: o recado fica guardado
 * para quem voltar, não volta depois de consumido e diz se tem desfazer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeStatusMessageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        // Sem confinamento: cada passo do ViewModel acontece na hora, e o teste espera o
        // que é de IO de verdade (o banco) em vez de fingir um relógio.
        Dispatchers.setMain(Dispatchers.Unconfined)
        container = AppContainer(context)
        viewModel = HomeViewModel(container)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Concluir pela tela de edição: a escrita termina depois do giro, e o "Feito." — com o
     * desfazer que ele carrega — tem que estar esperando a home voltar.
     */
    @Test
    fun oFeitoEsperaAHomeVoltarParaATela() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.complete(itemDe(salvo))
        esperaAGravacaoTerminar()

        // Ninguém consumiu: é assim que a home recriada o encontra.
        val recado = viewModel.statusMessage.value
        assertThat(recado).isNotNull()
        assertThat(recado!!.text).isEqualTo("Feito.")
        assertThat(recado.undo).isEqualTo(StatusMessage.Undo.COMPLETE)
    }

    /** O recado consumido não volta numa recomposição qualquer. */
    @Test
    fun oRecadoConsumidoNaoVolta() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }
        viewModel.complete(itemDe(salvo))
        esperaAGravacaoTerminar()
        assertThat(viewModel.statusMessage.value).isNotNull()

        viewModel.consumeStatusMessage()

        assertThat(viewModel.statusMessage.value).isNull()
    }

    /**
     * Concluir duas tarefas seguidas dá a mesma frase duas vezes. O `StateFlow` não emite
     * valor igual ao atual: sem identidade própria, o segundo "Feito." não chegava a
     * ninguém e a tela ficava com o desfazer armado do primeiro — desfazendo a tarefa
     * errada.
     */
    @Test
    fun doisRecadosIguaisNaoViramUmSo() {
        val primeira = runBlocking { container.tasks.saveDraft(recado()) }
        val segunda = runBlocking { container.tasks.saveDraft(recado("tomar água")) }

        viewModel.complete(itemDe(primeira))
        esperaAGravacaoTerminar()
        val primeiro = viewModel.statusMessage.value

        viewModel.complete(itemDe(segunda))
        esperaAGravacaoTerminar()

        val segundo = viewModel.statusMessage.value
        assertThat(segundo).isNotEqualTo(primeiro)
        // O que se repete é o conteúdo: é o cenário do defeito, não um caso montado.
        assertThat(segundo!!.text).isEqualTo(primeiro!!.text)
    }

    /**
     * A exclusão é o recado que traz o desfazer na mesma frase; excluir deixa o item ao
     * alcance até alguém desfazer ou o aviso sair da tela.
     */
    @Test
    fun aExclusaoCarregaODesfazer() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.delete(itemDe(salvo))
        esperaAGravacaoTerminar()

        val recado = viewModel.statusMessage.value
        assertThat(recado!!.text).isEqualTo("Tarefa excluída.")
        assertThat(recado.undo).isEqualTo(StatusMessage.Undo.DELETE)
    }

    /** O aviso saiu da tela sem desfazer: aquela exclusão deixou de estar ao alcance. */
    @Test
    fun aExclusaoEsquecidaNaoVoltaNumDesfazerQualquer() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }
        viewModel.delete(itemDe(salvo))
        esperaAGravacaoTerminar()
        viewModel.forgetUndoDelete()

        viewModel.undoDelete()
        esperaAGravacaoTerminar()

        assertThat(viewModel.writeError.value).isNull()
        assertThat(runBlocking { container.tasks.snapshotAgenda().find(salvo.occurrence.id) }).isNull()
    }

    /**
     * Adiar: a frase diz para quando o aviso foi empurrado e é montada no ViewModel, que é
     * quem sabe o resultado da gravação — não a tela que a pediu.
     */
    @Test
    fun oAdiamentoDizParaQuandoEmpurrou() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.snooze(salvo.occurrence.id, 30)
        esperaAGravacaoTerminar()

        assertThat(viewModel.statusMessage.value!!.text).contains("Vai avisar")
        assertThat(viewModel.writeError.value).isNull()
    }

    /** "Fazer hoje" responde com a data para onde a tarefa foi remarcada. */
    @Test
    fun oFazerHojeDizParaQuandoRemarcou() {
        // Não realizada: rascunho de ontem, que o próprio repositório marca como perdida.
        val salvo = runBlocking {
            container.tasks.saveDraft(recado(data = LocalDate.now().minusDays(1)))
        }

        viewModel.retryMissed(salvo.occurrence.id)
        esperaAGravacaoTerminar()

        assertThat(viewModel.statusMessage.value!!.text).contains("Vai avisar")
    }

    /**
     * A série encerrada não tem desfazer: a frase sai sem botão, e o `busy` volta a soltar
     * os botões da tela.
     */
    @Test
    fun oFimDaSerieNaoTemDesfazer() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.endSeries(salvo.series.id)
        esperaAGravacaoTerminar()

        val recado = viewModel.statusMessage.value
        assertThat(recado!!.text).isEqualTo("Série encerrada.")
        assertThat(recado.undo).isNull()
    }

    /**
     * `saveDraft` sempre cria uma série nova — não é idempotente. É por isso que o "Daqui N
     * min" não pode deixar o mesmo recado ser salvo duas vezes: dois toques viram duas
     * tarefas e dois alarmes no mesmo horário. A guarda é da tela (o ViewModel não a tem),
     * e este teste é o que diz que ela é carga, não enfeite.
     */
    @Test
    fun doisToquesNoDaquiNMinViramDuasTarefas() {
        val recado = QuickRemind.draft("tomar água", 15, ZonedDateTime.now())

        viewModel.saveDraft(recado, DraftSaveOrigin.QUICK_REMIND)
        viewModel.saveDraft(recado, DraftSaveOrigin.QUICK_REMIND)

        val secoes = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                var atual = container.tasks.snapshotAgenda()
                // As duas gravações são independentes: espera as duas aterrissarem.
                while ((atual.today + atual.upcoming).size < 2) {
                    delay(20)
                    atual = container.tasks.snapshotAgenda()
                }
                atual
            }
        }
        assertThat((secoes.today + secoes.upcoming).map { it.series.title })
            .containsExactly("tomar água", "tomar água")
    }

    private fun recado(
        titulo: String = "tomar remédio",
        data: LocalDate = LocalDate.now().plusDays(1),
    ) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = LocalTime.of(8, 30),
        recurrence = RecurrenceRule(RecurrenceKind.NONE),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
    )

    private fun itemDe(salvo: SaveResult) = AgendaItem(occurrence = salvo.occurrence, series = salvo.series)

    /** O fim da gravação: é ele que solta os botões das telas. */
    private fun esperaAGravacaoTerminar() {
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.busy.first { !it } } }
    }

    private companion object {
        const val TEMPO_LIMITE = 15_000L
    }
}

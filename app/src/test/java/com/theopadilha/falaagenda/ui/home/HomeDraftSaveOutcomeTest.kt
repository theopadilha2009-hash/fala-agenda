package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
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

/**
 * O desfecho de uma gravação de rascunho não pertence à composição que a pediu: a escrita
 * pode terminar depois de o aparelho girar. Quando ele ia para o `onDone` da tela, o
 * `MutableState` já tinha sido descartado — a caixa "Pode salvar?" continuava cheia e sem
 * confirmação nenhuma, e o toque seguinte salvava o mesmo recado de novo (duas tarefas,
 * dois alarmes). A falha tinha o mesmo destino, calada.
 *
 * O que estes testes provam é a metade do ViewModel: o desfecho fica guardado para quem
 * voltar, sobrevive a uma composição nova e só sai de lá quando alguém o consome. A outra
 * metade — a caixa fechar, a tela navegar, o erro aparecer com o rascunho no lugar — é da
 * composição, e esta suíte não roda Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeDraftSaveOutcomeTest {

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

    @Test
    fun oDesfechoDaGravacaoEsperaAQuemVoltarParaATela() {
        viewModel.saveDraft(recado(), DraftSaveOrigin.HOME_QUICK)

        // Ninguém consumiu: é assim que a tela recriada o encontra.
        val saved = desfecho() as DraftSaveOutcome.Saved
        assertThat(saved.origin).isEqualTo(DraftSaveOrigin.HOME_QUICK)
        assertThat(saved.message).contains("Vai avisar")

        // E a gravação acabou de verdade: o `busy` volta a soltar o botão Salvar — ele
        // mora no ViewModel, então o giro não o reabilita antes da hora.
        esperaAGravacaoTerminar()
    }

    /** Desfecho consumido não volta a aparecer numa recomposição qualquer. */
    @Test
    fun oDesfechoConsumidoNaoVolta() {
        viewModel.saveDraft(recado(), DraftSaveOrigin.HOME_QUICK)
        desfecho()

        viewModel.consumeDraftSaveOutcome()

        assertThat(viewModel.draftSaveOutcome.value).isNull()
        // E a próxima gravação traz o desfecho dela, não a repetição da anterior.
        viewModel.saveDraft(recado("outro remédio"), DraftSaveOrigin.HOME_QUICK)
        assertThat(desfecho().origin).isEqualTo(DraftSaveOrigin.HOME_QUICK)
    }

    /**
     * A metade silenciosa do defeito: a gravação que falha no meio do giro também não
     * podia depender de uma tela viva para avisar. O rascunho continua com a tela; o
     * recado da falha fica aqui.
     */
    @Test
    fun aFalhaDaGravacaoTambemEsperaAQuemVoltarParaATela() {
        // Primeiro uma gravação que passa (é ela que abre o banco), e então o banco
        // fechado: a gravação seguinte estoura no meio, como um IO que falhou.
        runBlocking { container.tasks.saveDraft(recado()) }
        container.db.close()

        viewModel.saveDraft(recado(), DraftSaveOrigin.CONFIRM)

        val failed = desfecho() as DraftSaveOutcome.Failed
        assertThat(failed.origin).isEqualTo(DraftSaveOrigin.CONFIRM)
        assertThat(failed.message).isEqualTo("Não consegui salvar o recado. Tente de novo.")
        // Pelo canal genérico ela viraria um aviso solto na home, sem o rascunho à mão.
        assertThat(viewModel.writeError.value).isNull()
    }

    /**
     * O desfecho diz de que tela ele é: sem isso a home agiria sobre a gravação da
     * confirmação (e a confirmação, sobre a da home).
     */
    @Test
    fun oDesfechoDizDeQualTelaEleE() {
        viewModel.saveDraft(recado(), DraftSaveOrigin.QUICK_REMIND)

        assertThat(desfecho().origin).isEqualTo(DraftSaveOrigin.QUICK_REMIND)
    }

    /**
     * Dois desfechos iguais em sequência — o mesmo recado salvo dentro do mesmo minuto dá
     * exatamente a mesma frase — são dois eventos. O `StateFlow` não emite valor igual ao
     * atual: sem identidade própria, o segundo desfecho não chegava a ninguém, a tela que
     * o esperava ficava parada e salvava de novo a cada toque.
     */
    @Test
    fun doisDesfechosIguaisNaoViramUmSo() {
        viewModel.saveDraft(recado(), DraftSaveOrigin.QUICK_REMIND)
        // Ninguém consome o primeiro: ele fica pendente, como quando a tela que o pediu
        // já saiu.
        val primeiro = desfecho() as DraftSaveOutcome.Saved

        viewModel.saveDraft(recado(), DraftSaveOrigin.QUICK_REMIND)

        val segundo = desfechoDiferenteDe(primeiro) as DraftSaveOutcome.Saved
        assertThat(segundo).isNotEqualTo(primeiro)
        // O que se repete é o conteúdo — é o cenário do defeito, não um caso montado.
        assertThat(segundo.message).isEqualTo(primeiro.message)
        assertThat(segundo.origin).isEqualTo(primeiro.origin)
        assertThat(segundo.usedInexactAlarm).isEqualTo(primeiro.usedInexactAlarm)
    }

    /**
     * Salvar uma mudança não agenda alarme novo: o desfecho não tem opinião sobre o aviso
     * de alarme inexato, que fica como estava em vez de ser apagado da tela dela.
     */
    @Test
    fun salvarAMudancaNaoMexeNoAvisoDeAlarmeInexato() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.edit(
            id = salvo.occurrence.id,
            title = "tomar remédio",
            date = LocalDate.of(2026, 9, 28),
            time = LocalTime.of(9, 0),
            recurrence = RecurrenceRule(),
        )

        val saved = desfecho() as DraftSaveOutcome.Saved
        assertThat(saved.origin).isEqualTo(DraftSaveOrigin.CONFIRM)
        assertThat(saved.usedInexactAlarm).isNull()
    }

    private fun desfecho(): DraftSaveOutcome =
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.draftSaveOutcome.filterNotNull().first() } }

    /**
     * Espera um desfecho que não seja o [anterior]. Sem isto, ler o valor depois da segunda
     * gravação passaria pelo desfecho antigo (que continua no fluxo) e o teste veria o
     * desfecho errado — ou nenhum.
     */
    private fun desfechoDiferenteDe(anterior: DraftSaveOutcome): DraftSaveOutcome =
        runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.draftSaveOutcome.filterNotNull().first { it != anterior }
            }
        }

    /** O fim da gravação: é ele que solta o botão Salvar nas telas. */
    private fun esperaAGravacaoTerminar() {
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.busy.first { !it } } }
    }

    /** O recado tem que estar completo: `saveDraft` recusa rascunho sem data e horário. */
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

package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.ui.AgendaFormat
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

    /**
     * A escolha que já passou e não repete não gera alarme nenhum: o salvar arquiva a
     * ocorrência como não realizada (ver `TaskRepository.saveDraft`). O anúncio não pode
     * prometer o aviso — ele dizia "Vai avisar hoje às 08:00" e a home, no toque seguinte,
     * mostrava "Não consegui avisar" sobre a mesma tarefa.
     */
    @Test
    fun aEscolhaQueJaPassouNaoAnunciaAviso() {
        viewModel.saveDraft(recado(data = LocalDate.now().minusDays(1)), DraftSaveOrigin.CONFIRM)

        val saved = desfecho() as DraftSaveOutcome.Saved
        assertThat(saved.message).doesNotContain("Vai avisar")
        assertThat(saved.message).contains("já passou")
    }

    /**
     * A data do anúncio é a da ocorrência gravada, não a do rascunho. Com uma regra semanal, o
     * chip "Hoje" e a lista mostrando "01/10 às 08:30", o anúncio dizia "Vai avisar hoje às
     * 08:30." — a mesma contradição do defeito de origem, agora saindo do recado pós-salvar
     * com a data do rascunho de um lado e a ocorrência do banco do outro.
     */
    @Test
    fun aTarefaQueRepeteAnunciaODiaDaOcorrenciaGravada() {
        val hoje = LocalDate.now()
        val diaQueVale = hoje.plusDays(2)
        val regra = RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = setOf(diaQueVale.dayOfWeek))
        // A data que o repositório materializa para este rascunho — a outra ponta do anúncio.
        val gravada = runBlocking { container.tasks.saveDraft(recado(data = hoje, regra = regra)) }
            .occurrence.localDate
        assertThat(gravada).isEqualTo(diaQueVale)

        viewModel.saveDraft(recado(data = hoje, regra = regra), DraftSaveOrigin.CONFIRM)

        val saved = desfecho() as DraftSaveOutcome.Saved
        assertThat(saved.message).contains(AgendaFormat.dateLabel(gravada, hoje).lowercase())
        assertThat(saved.message).doesNotContain("Vai avisar hoje")
    }

    /**
     * O gêmeo da criação, no caminho da edição: mudar a tarefa para um horário que já passou
     * e não repete também não deixa alarme nenhum — `editOccurrence` arquiva a ocorrência como
     * não realizada (ver `DraftSchedule.bornWithoutReminder`). O anúncio dizia "Vai avisar" e
     * a home, no toque seguinte, mostrava "Não consegui avisar" sobre a tarefa recém-editada.
     */
    @Test
    fun aEdicaoParaDataPassadaNaoAnunciaAviso() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.edit(
            id = salvo.occurrence.id,
            title = "tomar remédio",
            date = LocalDate.now().minusDays(1),
            time = LocalTime.of(8, 30),
            recurrence = RecurrenceRule(RecurrenceKind.NONE),
        )

        val saved = desfecho() as DraftSaveOutcome.Saved
        assertThat(saved.message).doesNotContain("Vai avisar")
        assertThat(saved.message).contains("já passou")
    }

    /** E a correção não engole o caso que continua valendo: edição para frente avisa. */
    @Test
    fun aEdicaoParaDataFuturaContinuaAnunciandoAviso() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }

        viewModel.edit(
            id = salvo.occurrence.id,
            title = "tomar remédio",
            date = LocalDate.now().plusDays(2),
            time = LocalTime.of(8, 30),
            recurrence = RecurrenceRule(RecurrenceKind.NONE),
        )

        val saved = desfecho() as DraftSaveOutcome.Saved
        assertThat(saved.message).contains("Vai avisar")
    }

    /**
     * O gêmeo do teste acima no caminho da edição que repete: corrigir o remédio "todo dia" para
     * um horário de hoje já passado arquiva a ocorrência de hoje e arma a próxima data da regra
     * (`TaskRepository.occurrencesForChoice`). O anúncio dizia "Vai avisar hoje às 08:00" — um
     * aviso que não existe, porque nenhum alarme toca hoje.
     */
    @Test
    fun aEdicaoRecorrenteParaHorarioDeHojeJaPassadoAnunciaAProximaData() {
        val hoje = LocalDate.now()
        val salvo = runBlocking {
            container.tasks.saveDraft(
                recado(data = hoje.plusDays(1), regra = RecurrenceRule(RecurrenceKind.DAILY)),
            )
        }

        // Uma data de ontem com a regra que repete: a escolha vence em qualquer hora do dia, e a
        // próxima data da regra é que fica armada. Ontem (e não "hoje às 00:01") para o cenário
        // não depender da hora em que a suíte roda.
        viewModel.edit(
            id = salvo.occurrence.id,
            title = "tomar remédio",
            date = hoje.minusDays(1),
            time = LocalTime.of(8, 30),
            recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        )

        val saved = desfecho() as DraftSaveOutcome.Saved
        val amanha = hoje.plusDays(1)
        assertThat(saved.message).contains(AgendaFormat.dateLabel(amanha, hoje).lowercase())
        assertThat(saved.message).doesNotContain("Vai avisar hoje")
    }

    /**
     * O gêmeo do teste acima no caminho do tombstone: editar uma série que repete para um
     * horário já passado arma a próxima data da regra — mas se essa data foi excluída, a
     * próxima **viva** é outra, e é ela que o anúncio tem de citar. `ChoiceSchedule.plan`
     * só avança até a próxima viva quando recebe as datas excluídas, e quem as leva ao
     * `announceOfEdit` é o chamador (`FalaAgendaRoot`, com o `skippedDates` da série).
     *
     * O argumento vai **explícito** aqui de propósito: pelo default (`emptySet()`) a peça
     * devolveria a data do tombstone e o recado pós-salvar voltaria a prometer um aviso que
     * nenhum alarme toca — a mentira que este PR existe para matar, no caminho que ele mesmo
     * consertou. Nenhum teste exercitava este recado com tombstone, e trocar
     * `ChoiceSchedule.plan(recurrence, date, time, zone, now, skippedDates)` por
     * `plan(recurrence, date, time, zone, now)` dentro do `announceOfEdit` passava com a
     * suíte inteira verde.
     */
    @Test
    fun aEdicaoRecorrenteComTombstoneAnunciaAProximaDataViva() {
        val hoje = LocalDate.now()
        val escolhida = hoje.minusDays(1)
        val comTombstone = hoje.plusDays(1)
        val viva = hoje.plusDays(2)
        val hora = LocalTime.of(8, 30)
        val serie = serieComTombstone(comTombstone, escolhida, hora)

        viewModel.edit(
            id = OccurrenceIds.of(serie.id, escolhida),
            title = serie.title,
            date = escolhida,
            time = hora,
            recurrence = RecurrenceRule(RecurrenceKind.DAILY),
            skippedDates = setOf(comTombstone),
        )

        val saved = desfecho() as DraftSaveOutcome.Saved
        // A data viva é a que o recado cita — e "amanhã", a data do tombstone, não aparece:
        // é o que o `ChoiceSchedule.plan` sem as datas excluídas diria.
        assertThat(saved.message).contains(AgendaFormat.dateLabel(viva, hoje).lowercase())
        assertThat(saved.message).doesNotContain("amanhã")
        // E a data que o repositório armou é a mesma que o anúncio prometeu.
        val armada = runBlocking { container.db.occurrenceDao().get(OccurrenceIds.of(serie.id, viva)) }
        assertThat(armada).isNotNull()
        assertThat(armada!!.status).isEqualTo(OccurrenceStatus.PENDING.name)
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

    /**
     * A ocorrência que saiu do banco — o "Excluir" de outra tela, a varredura do start —
     * faz a gravação da edição virar no-op: a linha não está lá e nada é gravado. Anunciar
     * "Salvo" aí era dizer que o que ela digitou ficou guardado quando não ficou. A falha
     * sai com o rascunho no lugar, e não pelo canal genérico (que a mostraria longe da
     * tela onde ela está escrevendo).
     */
    @Test
    fun editarTarefaQueSaiuDaAgendaNaoAnunciaSalvo() {
        val salvo = runBlocking { container.tasks.saveDraft(recado()) }
        runBlocking { container.tasks.deleteOccurrence(salvo.occurrence.id) }

        viewModel.edit(
            id = salvo.occurrence.id,
            title = "tomar remédio",
            date = LocalDate.of(2026, 9, 28),
            time = LocalTime.of(9, 0),
            recurrence = RecurrenceRule(),
        )

        val failed = desfecho() as DraftSaveOutcome.Failed
        assertThat(failed.origin).isEqualTo(DraftSaveOrigin.CONFIRM)
        assertThat(failed.message).contains("não está mais na agenda")
        assertThat(viewModel.writeError.value).isNull()
    }

    /**
     * Uma série que já existe no banco com um tombstone na [comTombstone], e a ocorrência
     * [escolhida] (vencida) para o "Salvar" da edição encontrar. É o estado em que o
     * aplicativo fica depois de a pessoa excluir a data de amanhã: a série guarda o
     * tombstone e a data escolhida continua viva até a edição arquivá-la.
     */
    private fun serieComTombstone(
        comTombstone: LocalDate,
        escolhida: LocalDate,
        hora: LocalTime,
    ): TaskSeries {
        val agora = java.time.Instant.now()
        val serie = TaskSeries(
            id = java.util.UUID.randomUUID().toString(),
            title = "tomar remédio",
            zoneId = java.time.ZoneId.systemDefault(),
            localTime = hora,
            startLocalDate = escolhida,
            recurrence = RecurrenceRule(RecurrenceKind.DAILY),
            skippedDates = setOf(comTombstone),
            createdAt = agora,
            updatedAt = agora,
        )
        runBlocking {
            container.db.seriesDao().upsert(serie.toEntity())
            container.db.occurrenceDao().upsert(
                TaskOccurrence(
                    id = OccurrenceIds.of(serie.id, escolhida),
                    seriesId = serie.id,
                    localDate = escolhida,
                    scheduledAt = escolhida.atTime(hora).atZone(serie.zoneId).toInstant(),
                    status = OccurrenceStatus.PENDING,
                ).toEntity(),
            )
        }
        return serie
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
    private fun recado(
        titulo: String = "tomar remédio",
        // Amanhã, e não uma data fixa: o recado desta suíte é um aviso que ainda vai tocar,
        // que é o que o anúncio do salvar descreve. Com a data no passado e sem repetição o
        // desfecho é outro — arquivada como não realizada, sem alarme — e o anúncio diz isso.
        data: LocalDate = LocalDate.now().plusDays(1),
        regra: RecurrenceRule = RecurrenceRule(RecurrenceKind.NONE),
    ) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = LocalTime.of(8, 30),
        recurrence = regra,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
    )

    private companion object {
        const val TEMPO_LIMITE = 15_000L
    }
}

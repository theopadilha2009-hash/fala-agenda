package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * A fala passa pela camada de intenção antes de virar tarefa.
 *
 * O defeito que estes testes prendem: todo texto reconhecido ia direto para o parser, então
 * "cancela o médico" criava a tarefa "Cancela o médico" e ela acreditava que tinha cancelado.
 * Aqui a metade que importa é a do ViewModel — um comando NÃO cria tarefa, e uma captura
 * continua criando. A tela (o aviso sair legível, a caixa rápida abrir) é da composição, e
 * esta suíte não roda Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class FalaComandoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        container = AppContainer(context)
        viewModel = HomeViewModel(container)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- o defeito: comando não vira tarefa ------------------------------------------

    @Test
    fun cancelarSemAlvoAchadoNaoCriaTarefa() {
        viewModel.understandSpeech("cancela o médico")

        val agenda = agenda()
        assertThat(agenda.today).isEmpty()
        assertThat(agenda.upcoming).isEmpty()
        assertThat(proximoRecado()).contains("Não achei")
    }

    @Test
    fun jaTomeiNaoCriaTarefaJaTomei() {
        viewModel.understandSpeech("já tomei")

        val agenda = agenda()
        assertThat(agenda.today).isEmpty()
        assertThat(agenda.upcoming).isEmpty()
        // Sem nome nenhum, a matcher não escolhe: o app diz que não achou.
        assertThat(proximoRecado()).contains("Não achei")
    }

    @Test
    fun oQueTenhoHojeRespondeEmVezDeCriarTarefa() {
        viewModel.understandSpeech("o que tenho hoje?")

        val agenda = agenda()
        assertThat(agenda.today).isEmpty()
        assertThat(agenda.upcoming).isEmpty()
        assertThat(proximoRecado()).contains("Não tem nada marcado para hoje")
    }

    @Test
    fun oQueTenhoAmanhaListaOTitulo() {
        runBlocking { container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().plusDays(1))) }

        viewModel.understandSpeech("o que tenho amanhã?")

        val recado = proximoRecado()
        assertThat(recado).contains("amanhã")
        assertThat(recado).contains("Tomar remédio")
    }

    @Test
    fun oQueTenhoHojeNaoListaAmanha() {
        runBlocking { container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().plusDays(1))) }

        viewModel.understandSpeech("o que tenho hoje?")

        assertThat(proximoRecado()).contains("Não tem nada marcado para hoje")
    }

    // --- concluir e cancelar: agem de verdade ----------------------------------------

    @Test
    fun concluirUmAlvoUnicoMarcaComoFeito() {
        val salvo = runBlocking { container.tasks.saveDraft(recado("Consulta médica", LocalDate.now().plusDays(1))) }

        // "marca como feito", e não "conclui": o "conclui" nu é indistinguível do presente
        // ("conclui a faculdade em dezembro") e por isso virou captura (ver SpeechIntentTest).
        viewModel.understandSpeech("marca como feito a consulta médica")

        assertThat(proximoRecado()).isEqualTo("Feito.")
        val status = runBlocking { container.tasks.snapshotAgenda() }
            .find(salvo.occurrence.id)?.occurrence?.status
        assertThat(status).isEqualTo(OccurrenceStatus.COMPLETED)
    }

    @Test
    fun cancelarUmAlvoUnicoApaga() {
        val salvo = runBlocking { container.tasks.saveDraft(recado("Dentista", LocalDate.now().plusDays(1))) }

        viewModel.understandSpeech("cancela o dentista")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        val agenda = agenda()
        assertThat(agenda.today + agenda.upcoming).isEmpty()
        assertThat(agenda.find(salvo.occurrence.id)).isNull()
    }

    /**
     * Dois "remédio" NUNCA são escolhidos no chute: cancelar o errado é pior que não cancelar
     * nada. O app pergunta, e as duas continuam na agenda.
     */
    @Test
    fun nomeAmbiguoNaoCancelaNada() {
        runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().plusDays(1)))
            container.tasks.saveDraft(recado("Comprar remédio", LocalDate.now().plusDays(2)))
        }

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).contains("mais de uma")
        val agenda = agenda()
        assertThat(agenda.upcoming).hasSize(2)
    }

    /**
     * A rotina recorrente é o caso MAIS comum dela: "tomar remédio" todo dia. Uma série única
     * materializa várias pendentes (a varredura do start chama `spawnUpcomingPreview`), e
     * montar um candidato por ocorrência fazia "já tomei o remédio" virar ambíguo — a fala
     * mais provável de uma rotina não funcionava. O alvo é a série: a ocorrência eleita é a
     * pendente mais próxima, que é justamente a que `complete`/`deleteOccurrence` já tratam.
     */
    @Test
    fun rotinaRecorrenteConcluiNaProximaOcorrencia() {
        val hoje = LocalDate.now()
        val salvo = runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", hoje, diario())).also {
                // O start do processo materializa as próximas datas da mesma série.
                container.tasks.rescheduleAll()
            }
        }
        // O cenário do defeito: mais de uma pendente com o MESMO nome na agenda.
        assertThat(agenda().today + agenda().upcoming).hasSize(3)

        viewModel.understandSpeech("já tomei o remédio")

        assertThat(proximoRecado()).isEqualTo("Feito.")
        // QUAL data foi eleita — a mais próxima (hoje) —, e não só quantas sobraram: uma
        // eleição errada (a mais distante) concluiria a ocorrência de outro dia, calada.
        assertThat(statusDa(salvo.series.id, hoje)).isEqualTo(OccurrenceStatus.COMPLETED)
        assertThat(statusDa(salvo.series.id, hoje.plusDays(1))).isEqualTo(OccurrenceStatus.PENDING)
        assertThat(statusDa(salvo.series.id, hoje.plusDays(2))).isEqualTo(OccurrenceStatus.PENDING)
    }

    @Test
    fun rotinaRecorrenteCancelaANearest() {
        val hoje = LocalDate.now()
        val salvo = runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", hoje, diario())).also {
                container.tasks.rescheduleAll()
            }
        }

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        // A data eleita (a mais próxima) sai; as outras da MESMA série continuam de pé.
        assertThat(agenda().find(OccurrenceIds.of(salvo.series.id, hoje))).isNull()
        assertThat(agenda().find(OccurrenceIds.of(salvo.series.id, hoje.plusDays(1)))).isNotNull()
        assertThat(agenda().find(OccurrenceIds.of(salvo.series.id, hoje.plusDays(2)))).isNotNull()
    }

    /**
     * O desfazer ponta a ponta de um cancelamento por voz: o recado carrega o item eleito, e
     * desfazê-lo devolve a data certa — não outra da mesma série.
     */
    @Test
    fun desfazerOCancelamentoPorVozDevolveADataEleita() {
        val hoje = LocalDate.now()
        val salvo = runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", hoje, diario())).also {
                container.tasks.rescheduleAll()
            }
        }
        viewModel.understandSpeech("cancela o remédio")
        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(OccurrenceIds.of(salvo.series.id, hoje))).isNull()

        val undo = viewModel.statusMessage.value!!.undo
        assertThat(undo).isInstanceOf(StatusMessage.Undo.Delete::class.java)
        viewModel.undoDelete((undo as StatusMessage.Undo.Delete).item)
        esperaAGravacaoTerminar()

        // A data eleita volta; as outras nunca saíram.
        assertThat(agenda().find(OccurrenceIds.of(salvo.series.id, hoje))).isNotNull()
        assertThat(agenda().find(OccurrenceIds.of(salvo.series.id, hoje.plusDays(1)))).isNotNull()
    }

    // --- reconhecer e não fazer ------------------------------------------------------

    @Test
    fun mudarEhReconhecidoENaoExecutado() {
        viewModel.understandSpeech("muda pra quinta")

        assertThat(proximoRecado()).contains("Ainda não sei mudar")
        val agenda = agenda()
        assertThat(agenda.today + agenda.upcoming).isEmpty()
    }

    @Test
    fun apagarSemNomeEhReconhecidoENaoExecutado() {
        viewModel.understandSpeech("apaga isso")

        assertThat(proximoRecado()).contains("Ainda não sei apagar")
    }

    // --- "apaga o remédio": apagar pelo nome, decidido com a agenda ------------------

    /**
     * A fala mais provável dela criava a tarefa "Apaga remédio" e ela acreditava ter apagado.
     * Com a rotina na agenda, o verbo é comando: a tarefa sai e ela vê o desfazer.
     */
    @Test
    fun apagaORemedioApagaATarefaComEsseNome() {
        val salvo = runBlocking { container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().plusDays(1))) }

        viewModel.understandSpeech("apaga o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(salvo.occurrence.id)).isNull()
    }

    /**
     * A única ocorrência do alvo NÃO REALIZADA também é alvo.
     *
     * É o estado em que ela mais vê a tarefa: a dose de ontem que o aviso não alcançou, na
     * seção "Não realizadas", sem nenhuma pendente na agenda. O alvo não era achado — só as
     * pendentes eram candidatas — e "apaga o remédio" caía no caminho de captura: nascia o
     * rascunho "Apaga remédio" e a tarefa continuava lá, exatamente o defeito que esta camada
     * veio consertar.
     */
    @Test
    fun apagaORemedioNaoRealizadoApagaATarefa() {
        val salvo = runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().minusDays(1)))
        }
        // O cenário do revisor: ontem, sem aviso, e nenhuma pendente.
        assertThat(salvo.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(agenda().today + agenda().upcoming).isEmpty()

        viewModel.understandSpeech("apaga o remédio")

        // O defeito tem duas caras e a asserção pega a primeira que aparece: sem alvo, a fala
        // segue o caminho de captura e o rascunho "Apaga remédio" nasce. A espera é curta de
        // propósito: o parse local responde rápido, e no caminho certo nenhum rascunho vem.
        val draft = runBlocking {
            withTimeoutOrNull(2_000L) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNull()
        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(salvo.occurrence.id)).isNull()
    }

    /**
     * O mesmo buraco pelo outro verbo: "cancela o remédio" também não achava o alvo quando a
     * única ocorrência era a não realizada. Os dois caminhos usam o mesmo `lookupTarget`, e é
     * isso que os dois testes prendem.
     */
    @Test
    fun cancelaORemedioNaoRealizadoApagaATarefa() {
        val salvo = runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().minusDays(1)))
        }
        assertThat(salvo.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(salvo.occurrence.id)).isNull()
    }

    /**
     * O outro lado, e o que impede a correção de virar defeito: "apaga a luz" NÃO tem alvo na
     * agenda, então é um recado de verdade. Tratá-lo como comando engoliria a tarefa que ela
     * queria cadastrar — o mesmo engano silencioso, pelo avesso.
     */
    @Test
    fun apagaALuzSemTarefaViraRecadoNovo() {
        viewModel.understandSpeech("apaga a luz")

        val draft = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNotNull()
        assertThat(draft!!.title.lowercase()).contains("luz")
        assertThat(agenda().today + agenda().upcoming).isEmpty()
    }

    /**
     * Dois "remédio" nunca são escolhidos no chute, e a regra vale também para o apagar pelo
     * nome: o app pergunta, e as duas continuam na agenda.
     */
    @Test
    fun apagaONomeAmbiguoNaoApagaNada() {
        runBlocking {
            container.tasks.saveDraft(recado("Tomar remédio", LocalDate.now().plusDays(1)))
            container.tasks.saveDraft(recado("Comprar remédio", LocalDate.now().plusDays(2)))
        }

        viewModel.understandSpeech("apaga o remédio")

        assertThat(proximoRecado()).contains("mais de uma")
        assertThat(agenda().upcoming).hasSize(2)
    }

    // --- a captura continua captura --------------------------------------------------

    @Test
    fun umaTarefaDeVerdadeContinuaVirandoRascunho() {
        viewModel.understandSpeech("Tomar remédio às 8")

        val draft = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNotNull()
        assertThat(draft!!.title.lowercase()).contains("remédio")
    }

    // --- helpers ---------------------------------------------------------------------

    private fun agenda() = runBlocking { container.tasks.snapshotAgenda() }

    private fun statusDa(seriesId: String, data: LocalDate): OccurrenceStatus? =
        agenda().find(OccurrenceIds.of(seriesId, data))?.occurrence?.status

    private fun esperaAGravacaoTerminar() {
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.busy.first { !it } } }
    }

    private fun proximoRecado(): String =
        runBlocking { withTimeout(TEMPO_LIMITE) { viewModel.statusMessage.filterNotNull().first().text } }

    private fun recado(
        titulo: String,
        data: LocalDate,
        recorrencia: RecurrenceRule = RecurrenceRule(RecurrenceKind.NONE),
    ) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = LocalTime.of(8, 30),
        recurrence = recorrencia,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
    )

    private fun diario() = RecurrenceRule(RecurrenceKind.DAILY)

    private companion object {
        const val TEMPO_LIMITE = 15_000L
    }
}

package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A fala passa pela camada de intenção antes de virar tarefa.
 *
 * O defeito que estes testes prendem: todo texto reconhecido ia direto para o parser, então
 * "cancela o médico" criava a tarefa "Cancela o médico" e ela acreditava que tinha cancelado.
 * Aqui a metade que importa é a do ViewModel — um comando NÃO cria tarefa, e uma captura
 * continua criando. A tela (o aviso sair legível, a caixa rápida abrir) é da composição, e
 * esta suíte não roda Compose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class FalaComandoTest {

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
    private val hoje: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        container = AppContainer(context)
        viewModel = escopo.rastrear(HomeViewModel(container))
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

    /**
     * A resposta NÃO pode negar o que a MESMA tela está mostrando.
     *
     * O estado do defeito: uma ocorrência de HOJE que o aviso nunca alcançou
     * (`lastReminderAt` nulo, a seção "Não consegui avisar"), e nenhuma pendente. A manchete
     * conta as duas coisas de propósito ("Nada marcado agora. Não consegui avisar 1 tarefa."),
     * e a resposta falada contava só as pendentes — ela perguntava "o que tenho hoje?" e ouvia
     * "Não tem nada marcado para hoje", com o remédio de hoje logo abaixo. Ela conclui que
     * está em dia, e a dose que o celular não avisou some da cabeça dela.
     */
    @Test
    fun oQueTenhoHojeNaoNegaONaoAvisadoDeHoje() {
        val serie = serie("s-rem", "Tomar remédio", hoje)
        semear(serie, listOf(ocorrenciaNaoRealizada(serie, hoje, ultimoAviso = null)))

        // O estado como a tela o mostra: nada pendente, e a seção "Não consegui avisar" com o
        // remédio de hoje. Sem este par a asserção de baixo não prova nada.
        assertThat(agenda().today + agenda().upcoming).isEmpty()
        assertThat(missedSections(agenda().missed).first().items.map { it.series.title })
            .containsExactly("Tomar remédio")

        viewModel.understandSpeech("o que tenho hoje?")

        val resposta = proximoRecado()
        assertThat(resposta).doesNotContain("Não tem nada marcado")
        assertThat(resposta).contains("Não consegui avisar")
        assertThat(resposta).contains("Tomar remédio")
    }

    /**
     * O outro lado do mesmo ramo: com a pendente de hoje E a não avisada de hoje, a resposta
     * diz as duas — a pendente com o horário e a falha do app sem ele. Antes o aviso que não
     * saiu sumia por completo, e a resposta ficava mais otimista que a tela.
     */
    @Test
    fun oQueTenhoHojeDizAPendenteEONaoAvisado() {
        val pendente = serie("s-con", "Consulta médica", hoje)
        val naoAvisada = serie("s-rem", "Tomar remédio", hoje)
        semear(pendente, listOf(ocorrenciaPendente(pendente, hoje)))
        semear(naoAvisada, listOf(ocorrenciaNaoRealizada(naoAvisada, hoje, ultimoAviso = null)))

        viewModel.understandSpeech("o que tenho hoje?")

        val resposta = proximoRecado()
        assertThat(resposta).contains("Consulta médica")
        assertThat(resposta).contains("08:30")
        assertThat(resposta).contains("Não consegui avisar")
        assertThat(resposta).contains("Tomar remédio")
    }

    /**
     * A não realizada de hoje em que o aviso SAIU é outra coisa: a falta é dela, e a resposta
     * não pode acusar o aplicativo. A separação é o mesmo `missedReason` que a home usa para
     * montar as seções — uma segunda noção de "eu não avisei" aqui voltaria a contradizer a
     * tela, agora na resposta.
     */
    @Test
    fun oQueTenhoHojeNaoAcusaOAppDoQueElaNaoFez() {
        val serie = serie("s-rem", "Tomar remédio", hoje)
        semear(
            serie,
            listOf(
                ocorrenciaNaoRealizada(
                    serie,
                    hoje,
                    ultimoAviso = quando(serie, hoje).plusSeconds(600),
                ),
            ),
        )

        viewModel.understandSpeech("o que tenho hoje?")

        val resposta = proximoRecado()
        assertThat(resposta).doesNotContain("Não consegui avisar")
        // E também não pode dizer que não há nada: a dose de hoje está lá.
        assertThat(resposta).doesNotContain("Não tem nada marcado")
        assertThat(resposta).contains("Tomar remédio")
    }

    /**
     * A não avisada de AMANHÃ não entra na resposta de hoje: a janela da resposta é o dia
     * perguntado, como na lista da tela.
     */
    @Test
    fun oQueTenhoHojeIgnoraONaoAvisadoDeAmanha() {
        val serie = serie("s-rem", "Tomar remédio", hoje.plusDays(1))
        semear(serie, listOf(ocorrenciaNaoRealizada(serie, hoje.plusDays(1), ultimoAviso = null)))

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

    // --- o alvo que existe e já foi feito -------------------------------------------
    //
    // Ela concluiu o remédio ("já tomei o remédio" → "Feito.") e a tarefa continua na tela,
    // na seção "Concluídas". Falando de novo sobre ela, o app dizia "Não achei nenhuma tarefa
    // com esse nome" — para uma tarefa que existe, com o nome exato que ela falou. A decisão
    // de NÃO agir sobre uma concluída é certa; a frase é que descrevia um mundo que não é o
    // dela: ela conclui que nunca cadastrou e cadastra de novo.

    @Test
    fun cancelarAlvoJaConcluidoDizQueJaFoiFeitoEmVezDeNaoAchar() {
        val serie = serie("s-den", "Dentista", hoje)
        semear(serie, listOf(ocorrenciaConcluida(serie, hoje)))

        viewModel.understandSpeech("cancela o dentista")

        val resposta = proximoRecado()
        assertThat(resposta).doesNotContain("Não achei")
        assertThat(resposta).contains("já está feita")
        // A decisão de não agir continua: a linha está onde estava.
        assertThat(statusDa(serie.id, hoje)).isEqualTo(OccurrenceStatus.COMPLETED)
    }

    @Test
    fun concluirAlvoJaConcluidoDizQueJaFoiFeitoEmVezDeNaoAchar() {
        val serie = serie("s-rem", "Tomar remédio", hoje)
        semear(serie, listOf(ocorrenciaConcluida(serie, hoje)))

        viewModel.understandSpeech("já tomei o remédio")

        val resposta = proximoRecado()
        assertThat(resposta).doesNotContain("Não achei")
        assertThat(resposta).contains("já está feita")
        // E não é "Feito.": concluir de novo uma ocorrência já concluída é um no-op, e anunciar
        // sucesso aqui seria a resposta confirmar o que não aconteceu.
        assertThat(resposta).isNotEqualTo("Feito.")
        assertThat(statusDa(serie.id, hoje)).isEqualTo(OccurrenceStatus.COMPLETED)
    }

    /**
     * O outro lado, e o que impede o conserto de virar defeito: com a pendente viva da MESMA
     * série, a fala é a de sempre e o app age — a concluída de outro dia não pode transformar
     * a rotina em "já está feita".
     */
    @Test
    fun comPendenteVivaOAlvoConcluidoNaoMudaAFala() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(1), diario())
        semear(
            serie,
            listOf(
                ocorrenciaConcluida(serie, hoje.minusDays(1)),
                ocorrenciaPendente(serie, hoje),
            ),
        )

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje))).isNull()
        assertThat(statusDa(serie.id, hoje.minusDays(1))).isEqualTo(OccurrenceStatus.COMPLETED)
    }

    /** Nome que não existe em lugar nenhum continua dizendo que não achou. */
    @Test
    fun nomeQueNaoExisteContinuaDizendoNaoAchei() {
        val serie = serie("s-rem", "Tomar remédio", hoje)
        semear(serie, listOf(ocorrenciaPendente(serie, hoje)))

        viewModel.understandSpeech("cancela o dentista")

        assertThat(proximoRecado()).contains("Não achei nenhuma tarefa com esse nome")
    }

    /**
     * Duas concluídas com o mesmo nome: a resposta é a mesma do caso de uma só.
     *
     * A mensagem de ambiguidade existe para o app não escolher no chute o alvo sobre o qual
     * vai AGIR. Aqui não há ação nenhuma a tomar — as duas já estão feitas —, e "tem mais de
     * uma tarefa com esse nome" mandaria ela procurar uma escolha que não existe.
     */
    @Test
    fun duasConcluidasComOMesmoNomeDizemQueJaEstaoFeitas() {
        val uma = serie("s-rem-1", "Tomar remédio", hoje)
        val outra = serie("s-rem-2", "Tomar remédio", hoje)
        semear(uma, listOf(ocorrenciaConcluida(uma, hoje)))
        semear(outra, listOf(ocorrenciaConcluida(outra, hoje)))

        viewModel.understandSpeech("cancela o remédio")

        val resposta = proximoRecado()
        assertThat(resposta).doesNotContain("Não achei")
        assertThat(resposta).contains("já está feita")
        assertThat(agenda().completed).hasSize(2)
    }

    /**
     * O `apaga` com uma CONCLUÍDA homônima continua sendo um recado.
     *
     * O outro lado do [apagaALuzSemTarefaViraRecadoNovo], e o que aquele teste não cobria: com
     * uma "Tirar o lixo" já feita no banco, "tira o lixo" (querendo criar a de amanhã) tem de
     * nascer como rascunho do mesmo jeito. Recusar aqui engolia a captura para sempre — o
     * `sections.completed` não tem poda nem janela, então qualquer nome que colidisse com
     * qualquer concluída histórica nunca mais viraria tarefa, e ela não ficaria sabendo.
     *
     * O rascunho é recuperável (a confirmação mostra o título e ela não salva); o engolimento
     * não é. É a mesma assimetria de `SpeechIntent.Capture`: criar uma tarefa a mais é menos
     * grave que engolir uma que ela queria.
     */
    @Test
    fun apagaComConcluidaHomonimaContinuaVirandoRecado() {
        val antiga = serie("s-lixo", "Tirar o lixo", hoje.minusDays(1))
        semear(antiga, listOf(ocorrenciaConcluida(antiga, hoje.minusDays(1))))

        viewModel.understandSpeech("tira o lixo")

        val draft = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNotNull()
        assertThat(draft!!.title.lowercase()).contains("lixo")
        // A concluída continua onde estava: o rascunho não a tocou.
        assertThat(statusDa(antiga.id, hoje.minusDays(1))).isEqualTo(OccurrenceStatus.COMPLETED)
    }

    /**
     * A mesma colisão com um nome curto e uma concluída VELHA — o caso do revisor: "apaga a
     * luz" com uma "Luz" concluída há um mês. O tempo não pode ser o critério, porque não há
     * janela nenhuma em "Concluídas" para servir de referência.
     */
    @Test
    fun apagaALuzComLuzConcluidaAntigaContinuaVirandoRecado() {
        val antiga = serie("s-luz", "Luz", hoje.minusDays(30))
        semear(antiga, listOf(ocorrenciaConcluida(antiga, hoje.minusDays(30))))

        viewModel.understandSpeech("apaga a luz")

        val draft = runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.speech.state.filter { it.draft != null }.first().draft
            }
        }
        assertThat(draft).isNotNull()
        assertThat(draft!!.title.lowercase()).contains("luz")
        assertThat(statusDa(antiga.id, hoje.minusDays(30))).isEqualTo(OccurrenceStatus.COMPLETED)
    }

    /**
     * O `apaga` com pendente viva continua APAGANDO: a distinção acima vale só para o desfecho
     * sem alvo de pé, e a colisão com o histórico não pode roubar a ação de quem tem alvo.
     */
    @Test
    fun apagaComPendenteVivaApagaMesmoComConcluidaHomonima() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(1), diario())
        semear(
            serie,
            listOf(
                ocorrenciaConcluida(serie, hoje.minusDays(1)),
                ocorrenciaPendente(serie, hoje),
            ),
        )

        viewModel.understandSpeech("apaga o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje))).isNull()
        assertThat(statusDa(serie.id, hoje.minusDays(1))).isEqualTo(OccurrenceStatus.COMPLETED)
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

        // O defeito tem duas caras e as asserções pegam as duas, sem esperar por tempo: o
        // caminho de captura não publica recado nenhum ("Tarefa excluída." só sai daqui) e
        // deixaria a ocorrência na agenda. Só DEPOIS de o comando ter terminado é que o estado
        // da fala é lido — se ele tivesse ido parar no parser, o rascunho "Apaga remédio"
        // estaria publicado aqui. Um `withTimeoutOrNull { draft != null }` seguido de
        // `assertThat(draft).isNull()` passava verde mesmo capturando: bastava a captura demorar
        // mais que o limite para a espera devolver nulo e a asserção "provar" a ausência.
        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(salvo.occurrence.id)).isNull()
        assertThat(viewModel.speech.state.value.draft).isNull()
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

    // --- "Não realizadas": em qual DATA o verbo cai ----------------------------------
    //
    // A seção não realizações é uma lista, e a lista tem uma ordem: a mais nova em cima (o
    // `TaskRepository` ordena por `missedAt` descendente). A eleição do alvo tem de ser a mesma
    // linha que ela lê. Eleger a mais VELHA é a pior forma deste defeito — ela olha a linha de
    // cima, fala, e o app age na de baixo, calado.

    /**
     * Duas doses não realizadas da MESMA série, nenhuma pendente. A série é DAILY e o alvo é
     * falado: "já tomei o remédio" tem de concluir a dose que a tela mostra em cima.
     *
     * Antes: `(pendentes.ifEmpty { daSerie }).minBy { scheduledAt }` elegia exatamente a mais
     * velha — o último item da lista —, e o "Feito." respondia por uma data que ela não estava
     * olhando. Não há "Concluir" para não realizada na tela: o toque não desfaz esse engano.
     */
    @Test
    fun jaTomeiElegeANaoRealizadaDoTopoDaLista() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(2), diario())
        val maisVelha = ocorrenciaNaoRealizada(serie, hoje.minusDays(2))
        val maisNova = ocorrenciaNaoRealizada(serie, hoje.minusDays(1))
        semear(serie, listOf(maisVelha, maisNova))

        // O que a tela mostra, na ordem em que mostra: a mais nova em cima.
        assertThat(naoRealizadasDa(serie)).containsExactly(hoje.minusDays(1), hoje.minusDays(2)).inOrder()

        viewModel.understandSpeech("já tomei o remédio")

        assertThat(proximoRecado()).isEqualTo("Feito.")
        assertThat(statusDa(serie.id, hoje.minusDays(1))).isEqualTo(OccurrenceStatus.COMPLETED)
        assertThat(statusDa(serie.id, hoje.minusDays(2))).isEqualTo(OccurrenceStatus.MISSED)
    }

    /** O mesmo caminho pelo "apaga": a data excluída é a de cima, e a de baixo fica. */
    @Test
    fun apagaElegeANaoRealizadaDoTopoDaLista() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(2), diario())
        semear(
            serie,
            listOf(
                ocorrenciaNaoRealizada(serie, hoje.minusDays(2)),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(1)),
            ),
        )

        viewModel.understandSpeech("apaga o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje.minusDays(1)))).isNull()
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje.minusDays(2)))).isNotNull()
    }

    /**
     * O EMPATE de `missedAt` — e ele não é cenário exótico.
     *
     * A varredura da virada carimba TODAS as vencidas com o mesmo `now` de uma vez
     * (`OccurrenceLifecycle.advance`), então qualquer período sem abrir o app produz empate. A
     * tela ordena "Não realizadas" por `missedAt` com ordenação ESTÁVEL e, no empate, mantém a
     * ordem das linhas do Room — data CRESCENTE, a mais velha em cima. A eleição antiga
     * desempatava por `scheduledAt` descendente e elegia exatamente a de BAIXO: ela olhava a
     * linha de cima, falava "já tomei o remédio" e o app concluía a de baixo, calado.
     *
     * O teste mede os dois lados: a ordem que a TELA mostra e em QUAL data o verbo caiu. Se a
     * eleição voltar a ter critério próprio, o "caiu" deixa de ser o topo e isto fica vermelho.
     */
    @Test
    fun empateDeMissedAtElegeALinhaDoTopo() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(3), diario())
        val empate = quando(serie, hoje.minusDays(3)).plusSeconds(3_600)
        semear(
            serie,
            listOf(
                ocorrenciaNaoRealizada(serie, hoje.minusDays(3), missedAt = empate),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(2), missedAt = empate),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(1), missedAt = empate),
            ),
        )

        // O que a tela mostra, na ordem em que mostra: no empate, a ordem das linhas do Room.
        val noTopo = naoRealizadasDa(serie).first()
        assertThat(naoRealizadasDa(serie)).containsExactly(hoje.minusDays(3), hoje.minusDays(2), hoje.minusDays(1)).inOrder()

        viewModel.understandSpeech("já tomei o remédio")

        assertThat(proximoRecado()).isEqualTo("Feito.")
        assertThat(statusDa(serie.id, noTopo)).isEqualTo(OccurrenceStatus.COMPLETED)
        // As duas de baixo continuam não realizadas: só a linha de cima foi tocada.
        assertThat(statusDa(serie.id, hoje.minusDays(1))).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(statusDa(serie.id, hoje.minusDays(2))).isEqualTo(OccurrenceStatus.MISSED)
    }

    /**
     * O mesmo empate pelos outros dois verbos que usam o mesmo `lookupTarget`.
     */
    @Test
    fun empateDeMissedAtElegeALinhaDoTopoNoCancelar() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(3), diario())
        val empate = quando(serie, hoje.minusDays(3)).plusSeconds(3_600)
        semear(
            serie,
            listOf(
                ocorrenciaNaoRealizada(serie, hoje.minusDays(3), missedAt = empate),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(2), missedAt = empate),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(1), missedAt = empate),
            ),
        )
        val noTopo = naoRealizadasDa(serie).first()

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(OccurrenceIds.of(serie.id, noTopo))).isNull()
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje.minusDays(1)))).isNotNull()
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje.minusDays(2)))).isNotNull()
    }

    @Test
    fun empateDeMissedAtElegeALinhaDoTopoNoApagar() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(3), diario())
        val empate = quando(serie, hoje.minusDays(3)).plusSeconds(3_600)
        semear(
            serie,
            listOf(
                ocorrenciaNaoRealizada(serie, hoje.minusDays(3), missedAt = empate),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(2), missedAt = empate),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(1), missedAt = empate),
            ),
        )
        val noTopo = naoRealizadasDa(serie).first()

        viewModel.understandSpeech("apaga o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().find(OccurrenceIds.of(serie.id, noTopo))).isNull()
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje.minusDays(1)))).isNotNull()
        assertThat(agenda().find(OccurrenceIds.of(serie.id, hoje.minusDays(2)))).isNotNull()
    }

    /**
     * A série DIVIDIDA nas duas seções de "Não realizadas".
     *
     * A home não mostra as missed numa lista só: `missedSections` põe "Não consegui avisar"
     * primeiro, e a não avisada pode ser mais VELHA que a avisada. Ordenar a eleição só por
     * `missedAt` elegia a avisada (mais nova), enquanto o topo da tela é a não avisada — o
     * mesmo "agir na linha de baixo" do empate, por outro eixo.
     */
    @Test
    fun serieDivididaNasDuasSecoesElegeATopoSemAviso() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(2), diario())
        semear(
            serie,
            listOf(
                // A não avisada é mais velha e vem na PRIMEIRA seção; a avisada é mais nova e
                // vem na segunda. O topo da tela é a não avisada.
                ocorrenciaNaoRealizada(serie, hoje.minusDays(2), ultimoAviso = null),
                ocorrenciaNaoRealizada(serie, hoje.minusDays(1), ultimoAviso = quando(serie, hoje.minusDays(1))),
            ),
        )

        assertThat(missedSections(agenda().missed).first().items.map { it.occurrence.localDate })
            .containsExactly(hoje.minusDays(2))

        viewModel.understandSpeech("já tomei o remédio")

        assertThat(proximoRecado()).isEqualTo("Feito.")
        assertThat(statusDa(serie.id, hoje.minusDays(2))).isEqualTo(OccurrenceStatus.COMPLETED)
        assertThat(statusDa(serie.id, hoje.minusDays(1))).isEqualTo(OccurrenceStatus.MISSED)
    }

    /**
     * A não realizada entra como SEGUNDO turno, e não no mesmo pool: com uma pendente viva que
     * casa o alvo, uma tarefa esquecida em "Não realizadas" não pode derrubar a fala pedindo
     * para escolher entre as duas.
     *
     * O defeito medido: "Tomar remédio" pendente amanhã e "Remédio do coração" não realizada há
     * um mês. "cancela o remédio" — o caminho mais usado — respondia "Tem mais de uma tarefa com
     * esse nome" e NÃO CANCELAVA NADA. O comentário prometia que a não realizada só entra quando
     * a série não tem pendente, e isso vale por série; o pool, porém, era global.
     */
    @Test
    fun naoRealizadaDeOutraSerieNaoEnvenenaOCancelar() {
        val agendaDeAgora = serie("s-rem", "Tomar remédio", hoje.plusDays(1))
        val esquecida = serie("s-cor", "Remédio do coração", hoje.minusDays(30))
        semear(agendaDeAgora, listOf(ocorrenciaPendente(agendaDeAgora, hoje.plusDays(1))))
        semear(esquecida, listOf(ocorrenciaNaoRealizada(esquecida, hoje.minusDays(30))))

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().today + agenda().upcoming).isEmpty()
        // A esquecida continua onde estava: o alvo era a pendente, e só ela saiu.
        assertThat(agenda().missed.map { it.series.id }).containsExactly("s-cor")
    }

    /**
     * O mesmo pool dividido pelo outro lado — o `concluir`, que compartilha o `lookupTarget` e
     * é o que impede o conserto do "cancela" de virar defeito no "já tomei".
     */
    @Test
    fun naoRealizadaDeOutraSerieNaoEnvenenaOConcluir() {
        val agendaDeAgora = serie("s-rem", "Tomar remédio", hoje.plusDays(1))
        val esquecida = serie("s-cor", "Remédio do coração", hoje.minusDays(30))
        semear(agendaDeAgora, listOf(ocorrenciaPendente(agendaDeAgora, hoje.plusDays(1))))
        semear(esquecida, listOf(ocorrenciaNaoRealizada(esquecida, hoje.minusDays(30))))

        viewModel.understandSpeech("já tomei o remédio")

        assertThat(proximoRecado()).isEqualTo("Feito.")
        assertThat(statusDa(agendaDeAgora.id, hoje.plusDays(1))).isEqualTo(OccurrenceStatus.COMPLETED)
        assertThat(statusDa(esquecida.id, hoje.minusDays(30))).isEqualTo(OccurrenceStatus.MISSED)
    }

    /** E pelo terceiro verbo que usa o mesmo `lookupTarget`. */
    @Test
    fun naoRealizadaDeOutraSerieNaoEnvenenaOApagar() {
        val agendaDeAgora = serie("s-rem", "Tomar remédio", hoje.plusDays(1))
        val esquecida = serie("s-cor", "Remédio do coração", hoje.minusDays(30))
        semear(agendaDeAgora, listOf(ocorrenciaPendente(agendaDeAgora, hoje.plusDays(1))))
        semear(esquecida, listOf(ocorrenciaNaoRealizada(esquecida, hoje.minusDays(30))))

        viewModel.understandSpeech("apaga o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().today + agenda().upcoming).isEmpty()
        assertThat(agenda().missed.map { it.series.id }).containsExactly("s-cor")
    }

    /**
     * O outro lado do turno, e o que impede o conserto de fechar a porta que o PR abriu: sem
     * pendente nenhuma com o nome, a não realizada VOLTA a ser alvo — inclusive a esquecida de
     * outra série, que é o estado normal de uma casa com uma tarefa só atrasada.
     */
    @Test
    fun semPendenteANaoRealizadaVoltaASerAlvo() {
        val esquecida = serie("s-cor", "Remédio do coração", hoje.minusDays(30))
        semear(esquecida, listOf(ocorrenciaNaoRealizada(esquecida, hoje.minusDays(30))))

        viewModel.understandSpeech("cancela o remédio")

        assertThat(proximoRecado()).isEqualTo("Tarefa excluída.")
        assertThat(agenda().missed).isEmpty()
    }

    /**
     * O demonstrativo pontuado — a fala como o reconhecedor a entrega.
     *
     * "apaga essa." voltava a ser um NOME ("essa") em vez de apagar no escuro, o alvo não achava
     * tarefa nenhuma e a fala seguia para a captura: nascia a tarefa "Apaga essa" e ela
     * acreditava ter apagado. É a classe de defeito que esta camada existe para consertar,
     * reintroduzida pela âncora de fim de string do ramo novo.
     */
    @Test
    fun apagaEssaPontuadoNaoViraTarefa() {
        viewModel.understandSpeech("apaga essa.")

        assertThat(proximoRecado()).contains("Ainda não sei apagar")
        assertThat(agenda().today + agenda().upcoming).isEmpty()
        assertThat(viewModel.speech.state.value.draft).isNull()
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

    /** As não realizadas de uma série, na MESMA ordem em que a seção da tela as mostra. */
    private fun naoRealizadasDa(serie: TaskSeries): List<LocalDate> =
        agenda().missed.filter { it.series.id == serie.id }.map { it.occurrence.localDate }

    /**
     * Semeia a agenda pelo banco, e não pelo `saveDraft`: o estado que estes testes medem é o de
     * um aparelho que já viveu — a dose que o aviso não alcançou, a tarefa esquecida há um mês —
     * e a API de cadastro só sabe criar a ocorrência de agora. É o mesmo Room que o app lê, e o
     * `missedAt` entra explícito porque é ele que ordena a seção.
     */
    private fun semear(serie: TaskSeries, ocorrencias: List<TaskOccurrence>) = runBlocking {
        container.db.seriesDao().upsert(serie.toEntity())
        ocorrencias.forEach { container.db.occurrenceDao().upsert(it.toEntity()) }
    }

    private fun serie(
        id: String,
        titulo: String,
        inicio: LocalDate,
        recorrencia: RecurrenceRule = RecurrenceRule(RecurrenceKind.NONE),
    ) = TaskSeries(
        id = id,
        title = titulo,
        zoneId = ZoneId.systemDefault(),
        localTime = HORARIO,
        startLocalDate = inicio,
        recurrence = recorrencia,
        createdAt = CRIACAO,
        updatedAt = CRIACAO,
    )

    private fun ocorrenciaNaoRealizada(
        serie: TaskSeries,
        data: LocalDate,
        missedAt: Instant = quando(serie, data).plusSeconds(3_600),
        ultimoAviso: Instant? = null,
    ): TaskOccurrence {
        val quando = quando(serie, data)
        return TaskOccurrence(
            id = OccurrenceIds.of(serie.id, data),
            seriesId = serie.id,
            localDate = data,
            scheduledAt = quando,
            status = OccurrenceStatus.MISSED,
            missedAt = missedAt,
            lastReminderAt = ultimoAviso,
        )
    }

    private fun ocorrenciaPendente(serie: TaskSeries, data: LocalDate): TaskOccurrence {
        val quando = quando(serie, data)
        return TaskOccurrence(
            id = OccurrenceIds.of(serie.id, data),
            seriesId = serie.id,
            localDate = data,
            scheduledAt = quando,
            status = OccurrenceStatus.PENDING,
            nextReminderAt = quando,
        )
    }

    private fun ocorrenciaConcluida(
        serie: TaskSeries,
        data: LocalDate,
        completadaEm: Instant = quando(serie, data).plusSeconds(3_600),
    ): TaskOccurrence {
        val quando = quando(serie, data)
        return TaskOccurrence(
            id = OccurrenceIds.of(serie.id, data),
            seriesId = serie.id,
            localDate = data,
            scheduledAt = quando,
            status = OccurrenceStatus.COMPLETED,
            completedAt = completadaEm,
        )
    }

    private fun quando(serie: TaskSeries, data: LocalDate): Instant =
        LocalDateTime.of(data, serie.localTime).atZone(serie.zoneId).toInstant()

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
        val HORARIO: LocalTime = LocalTime.of(8, 30)
        val CRIACAO: Instant = Instant.parse("2026-08-01T10:00:00Z")
    }
}

package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import com.theopadilha.falaagenda.ui.capture.recurrenceFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A promessa da tela e a ocorrência que o salvar cria, conferidas uma contra a outra.
 *
 * Faltava justamente este teste — nenhum comparava as duas pontas — e a ausência explica o
 * defeito ter passado: a tela dizia "Vai avisar terça, 29/09" com a data do seletor e o
 * repositório nascia na primeira segunda (o `RecurrenceEngine.firstOnOrAfter` trata a data
 * escolhida como piso, não como resposta). Eram dois contratos para a mesma promessa, e
 * nenhum dos dois estava escrito.
 *
 * O que se prende aqui: dado o mesmo rascunho (data escolhida, hora e regra — montados como
 * os chips da tela os montam, ver `recurrenceFor`), o texto do resumo tem que citar a data
 * da ocorrência que o repositório gravou. Se a tela voltar a calcular por conta própria,
 * este teste cai.
 */
class PromessaDaTelaBateComOAgendamentoTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Terça-feira, 29 de setembro de 2026, 15:00 — o "hoje" do cenário do chip. */
    private val terca = LocalDate.of(2026, 9, 29)
    private val agora = terca.atTime(15, 0).atZone(zone).toInstant()

    private val scheduler = TestScheduler()

    private fun repo(now: Instant): TaskRepository = TaskRepository(
        FakeSeriesDao(),
        FakeOccurrenceDao(),
        FixedAppClock(now, zone),
        scheduler,
    )

    /**
     * Ela fala "toda segunda natação às 18h" e toca no chip "Hoje" (uma terça). A regra não
     * tem terça: o primeiro aviso é segunda, 05/10. O resumo e o botão têm que dizer isso.
     */
    @Test
    fun chipHojeComRegraSemanalPrometeODiaQueOVAIAvisar() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, terca, setOf(DayOfWeek.MONDAY))
        val draft = rascunho("Natação", terca, LocalTime.of(18, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        assertThat(saved.occurrence.localDate).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(promessa.recap).contains(AgendaFormat.longDate(saved.occurrence.localDate))
        assertThat(promessa.recap).contains("18:00")
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(terca))
        assertThat(promessa.saveLabel).contains(AgendaFormat.dateLabel(saved.occurrence.localDate, terca))
    }

    /**
     * A variante que se contradizia sozinha: "Dias úteis" com uma data de sábado. O resumo
     * dizia "Vai avisar Sábado, 3 de outubro..." e a regra não tem sábado — o alarme era
     * segunda. O par (data real, regra) lado a lado é o que impede a frase de se contradizer.
     */
    @Test
    fun diasUteisEmUmSabadoNaoPrometemOSabado() = runBlocking {
        val sabado = LocalDate.of(2026, 10, 3)
        val agoraDoSabado = sabado.atTime(15, 0).atZone(zone).toInstant()
        val rule = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        val draft = rascunho("Cabelo", sabado, LocalTime.of(9, 0), rule)

        val saved = repo(agoraDoSabado).saveDraft(draft)
        val promessa = promessa(draft, sabado, agoraDoSabado)

        assertThat(saved.occurrence.localDate).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(promessa.recap).contains(AgendaFormat.longDate(saved.occurrence.localDate))
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(sabado))
        assertThat(promessa.recap).contains(rule.describePtBr())
    }

    /**
     * A escolha descartada não pode sumir em silêncio: quando a data que ela tocou não é a
     * data que vai valer, a tela diz isso numa linha — senão o chip "Hoje" some da tela sem
     * explicação e ela não tem como entender nem corrigir.
     */
    @Test
    fun dataDescartadaApareceEmUmaLinha() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, terca, setOf(DayOfWeek.MONDAY))
        val draft = rascunho("Natação", terca, LocalTime.of(18, 0), rule)

        val promessa = promessa(draft, terca, agora)

        assertThat(promessa.droppedChoice).isNotNull()
        assertThat(promessa.droppedChoice!!).contains(AgendaFormat.longDate(LocalDate.of(2026, 10, 5)))
    }

    /** Quando a data escolhida é a que vale, não há o que explicar. */
    @Test
    fun semDataDescartadaNaoHaOLinhaAMais() {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, terca, setOf(DayOfWeek.TUESDAY))
        val draft = rascunho("Natação", terca, LocalTime.of(18, 0), rule)

        val promessa = promessa(draft, terca, agora)

        assertThat(promessa.droppedChoice).isNull()
        assertThat(promessa.recap).contains(AgendaFormat.longDate(terca))
    }

    /**
     * Todas as regras, comparadas com a ocorrência que o repositório grava — inclusive as em
     * que a data escolhida já vale, para este teste não passar por acerto de uma regra só.
     */
    @Test
    fun todasAsRegrasConcordamComAOccurrenceGravada() = runBlocking {
        val cenarios = listOf(
            RecurrenceKind.NONE to terca,
            RecurrenceKind.DAILY to terca,
            RecurrenceKind.WEEKDAYS to LocalDate.of(2026, 10, 3),
            RecurrenceKind.WEEKLY to terca,
            RecurrenceKind.MONTHLY to terca,
            RecurrenceKind.YEARLY to terca,
        )

        cenarios.forEach { (kind, escolhida) ->
            val semana = if (kind == RecurrenceKind.WEEKLY) setOf(DayOfWeek.MONDAY) else emptySet()
            val rule = recurrenceFor(kind, escolhida, semana)
            val agoraDoCenario = escolhida.atTime(15, 0).atZone(zone).toInstant()
            val draft = rascunho("Compromisso", escolhida, LocalTime.of(18, 0), rule)

            val saved = repo(agoraDoCenario).saveDraft(draft)
            val promessa = promessa(draft, escolhida, agoraDoCenario)

            assertThat(promessa.recap)
                .contains(AgendaFormat.longDate(saved.occurrence.localDate))
            // E quando a data escolhida não é a que vale, ela não pode aparecer no resumo: só o
            // `contains` de cima passaria com um texto que citasse as duas datas.
            if (saved.occurrence.localDate != escolhida) {
                assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(escolhida))
            }
        }
    }

    /**
     * O outro lado da mesma promessa: a escolha que já passou e não repete. Nenhum alarme é
     * criado (a ocorrência é arquivada como não realizada) e o resumo — e o botão — não podem
     * anunciar um aviso que não vai existir. O aplicativo dizia "Vai avisar hoje às 08:00" e,
     * na tela seguinte, mostrava "Não consegui avisar" sobre a mesma tarefa.
     */
    @Test
    fun escolhaQueJaPassouNaoPrometeAviso() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.NONE, terca, emptySet())
        val draft = rascunho("Remédio", terca, LocalTime.of(8, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        // O desfecho que o repositório decidiu: arquivada, sem alarme nenhum.
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(scheduler.scheduled).isEmpty()
        // E a promessa diz o mesmo.
        assertThat(promessa.recap).doesNotContain("Vai avisar")
        assertThat(promessa.recap).contains("já passou")
        assertThat(promessa.saveLabel).contains("sem aviso")
    }

    /** O mesmo horário de ontem: passado é passado em qualquer dia, não só "hoje". */
    @Test
    fun escolhaDeOntemTambemNaoPrometeAviso() = runBlocking {
        val ontem = terca.minusDays(1)
        val rule = recurrenceFor(RecurrenceKind.NONE, ontem, emptySet())
        val draft = rascunho("Remédio", ontem, LocalTime.of(8, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(promessa.recap).doesNotContain("Vai avisar")
        assertThat(promessa.saveLabel).contains("sem aviso")
    }

    /**
     * O defeito relatado, com os números dele: terça, 29/09/2026, 20:00, e o chip "Hoje" com
     * 18:00 numa regra que repete. O repositório gravava a ocorrência em 29/09 às 18:00 e
     * entregava esse instante ao `AlarmManager`, que dispara na hora — ela cadastrava "tomar
     * remédio, todo dia, às 18h" e o celular apitava no ato, seguindo a escada de repetições
     * até o fim do dia.
     *
     * A regra que repete não pode nascer num instante já vencido: a primeira ocorrência passa
     * para a próxima data da regra — a mesma conta que a fala já usa (`LocalTaskParser`:
     * "natação todo dia às 18h" dita às 20h nasce amanhã).
     *
     * Este teste afirmava o contrário ("o aviso chega, mesmo com a hora já passada") e passava
     * porque o `TestScheduler` só contava ids: ele consagrava a promessa que o `AlarmManager`
     * não cumpre. Agora ele prende o **instante**.
     */
    @Test
    fun escolhaPassadaQueRepeteNasceNaProximaData() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())
        val draft = rascunho("Remédio", terca, LocalTime.of(18, 0), rule)
        val agoraDaNoite = terca.atTime(20, 0).atZone(zone).toInstant()

        val saved = repo(agoraDaNoite).saveDraft(draft)
        val promessa = promessa(draft, terca, agoraDaNoite)

        val amanha = LocalDate.of(2026, 9, 30)
        // As asserções falam o horário da série, e não UTC: na falha, a mensagem diz
        // "expected: 2026-09-30T18:00 but was: 2026-09-29T18:00" — o instante vencido que ela
        // veria no relógio.
        val avisoNaSerie = LocalDateTime.of(2026, 9, 30, 18, 0)
        // Banco, promessa e alarme dizem o mesmo — e o mesmo é amanhã.
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
        assertThat(saved.occurrence.localDate).isEqualTo(amanha)
        assertThat(saved.occurrence.scheduledAt.atZone(zone).toLocalDateTime())
            .isEqualTo(avisoNaSerie)
        assertThat(scheduler.scheduled.map { it.fireAt.atZone(zone).toLocalDateTime() })
            .containsExactly(avisoNaSerie)
        assertThat(promessa.recap).contains(AgendaFormat.longDate(amanha))
        assertThat(promessa.recap).contains("18:00")
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(terca))
    }

    /**
     * O mesmo com o horário da manhã, que é o caso do dia inteiro: às 15:00 o remédio das
     * 08:00 já passou, e o primeiro aviso é amanhã às 08:00 — dentro do dia, e não um alarme
     * vencido que o sistema dispara no ato.
     */
    @Test
    fun escolhaDaManhaJaPassadaNasceAmanha() = runBlocking<Unit> {
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())
        val draft = rascunho("Remédio", terca, LocalTime.of(8, 0), rule)

        val saved = repo(agora).saveDraft(draft)

        val amanha = LocalDateTime.of(2026, 9, 30, 8, 0)
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
        assertThat(saved.occurrence.scheduledAt.atZone(zone).toLocalDateTime()).isEqualTo(amanha)
        assertThat(scheduler.scheduled.map { it.fireAt.atZone(zone).toLocalDateTime() })
            .containsExactly(amanha)
    }

    /**
     * Editando para uma data que ainda não venceu, a data escolhida é a que vale — a tela diz a
     * mesma data, sem linha de descarte. O sábado com a regra "dias úteis" não seria a primeira
     * ocorrência na criação, mas na edição o contrato é outro: a escolha vale enquanto futura.
     */
    @Test
    fun editandoADataEscolhidaEADataQueVale() = runBlocking {
        val sabado = LocalDate.of(2026, 10, 3)
        val rule = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        val draft = rascunho("Cabelo", sabado, LocalTime.of(9, 0), rule)

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = draft.localDate!!,
            chosenTime = draft.localTime!!,
            recurrence = draft.recurrence,
            today = sabado,
            now = sabado.atTime(8, 0).atZone(zone).toInstant(),
            zone = zone,
            editing = true,
        )

        assertThat(promessa.recap).contains(AgendaFormat.longDate(sabado))
        assertThat(promessa.droppedChoice).isNull()
    }

    /**
     * A edição de uma tarefa que repete para um horário de hoje já passado: a ocorrência de hoje
     * é arquivada e quem é armada é a próxima data da regra (`TaskRepository.occurrencesForChoice`),
     * então a tela não pode prometer hoje. Prometia — `editing = true` fixava a data literal e o
     * desvio só mordia na regra que não repete —, e ela salvava vendo "Vai avisar quinta, 20 de
     * agosto às 18:00" para um alarme que só toca em 21/08.
     */
    @Test
    fun editarRecorrenteParaHorarioDeHojeJaPassadoPrometeAProximaData() = runBlocking {
        val noite = terca.atTime(20, 0).atZone(zone).toInstant()
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = terca,
            chosenTime = LocalTime.of(18, 0),
            recurrence = rule,
            today = terca,
            now = noite,
            zone = zone,
            editing = true,
        )

        val amanha = terca.plusDays(1)
        assertThat(promessa.recap).contains(AgendaFormat.longDate(amanha))
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(terca))
        // E a data descartada não some em silêncio: a linha diz por que hoje não vale.
        assertThat(promessa.droppedChoice).isNotNull()
        assertThat(promessa.droppedChoice!!).contains(AgendaFormat.longDate(amanha))
    }

    /** E a edição para um horário ainda por vir continua prometendo o dia escolhido. */
    @Test
    fun editarRecorrenteParaHorarioFuturoPrometeODiaEscolhido() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = terca,
            chosenTime = LocalTime.of(18, 0),
            recurrence = rule,
            today = terca,
            now = agora,
            zone = zone,
            editing = true,
        )

        assertThat(promessa.recap).contains(AgendaFormat.longDate(terca))
        assertThat(promessa.droppedChoice).isNull()
    }

    /**
     * O defeito do tombstone, com os números dele: série "Remédio" todo dia às 08:00, ela
     * exclui a ocorrência de amanhã (30/09) e edita o cartão de hoje para as 18:00 com o
     * relógio já em 20:00.
     *
     * A escolha venceu, então quem é armada é a próxima data da regra — mas 30/09 tem
     * tombstone, e a próxima **viva** é 01/10. O repositório (que é a fonte da verdade) tem de
     * gravar e armar 01/10, e a tela tem de prometer a mesma data. Prometer 30/09 é anunciar um
     * alarme que não vai tocar: é o defeito de origem deste aplicativo, pelo caminho do #49.
     */
    @Test
    fun editarComTombstoneNaProximaDataPrometeEArmaAProximaViva() = runBlocking {
        val noite = terca.atTime(20, 0).atZone(zone).toInstant()
        val amanha = LocalDate.of(2026, 9, 30)
        val depoisDeAmanha = LocalDate.of(2026, 10, 1)
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())
        val seriesDao = FakeSeriesDao()
        val occDao = FakeOccurrenceDao()
        val repoDaNoite = TaskRepository(seriesDao, occDao, FixedAppClock(noite, zone), scheduler)
        val series = TaskSeries(
            id = "s-rem",
            title = "Remédio",
            zoneId = zone,
            localTime = LocalTime.of(8, 0),
            startLocalDate = terca,
            recurrence = rule,
            skippedDates = setOf(amanha),
            createdAt = noite,
            updatedAt = noite,
        )
        seriesDao.upsert(series.toEntity())
        val deHoje = OccurrenceIds.of(series.id, terca)
        occDao.upsert(
            TaskOccurrence(
                id = deHoje,
                seriesId = series.id,
                localDate = terca,
                scheduledAt = terca.atTime(8, 0).atZone(zone).toInstant(),
                status = OccurrenceStatus.PENDING,
            ).toEntity(),
        )

        repoDaNoite.editOccurrence(deHoje, "Remédio", terca, LocalTime.of(18, 0), rule)

        // O repositório é a fonte da verdade: a data com tombstone não nasce, e a próxima viva
        // é que fica pendente com alarme.
        assertThat(occDao.get(OccurrenceIds.of(series.id, amanha))).isNull()
        val viva = occDao.get(OccurrenceIds.of(series.id, depoisDeAmanha))
        assertThat(viva).isNotNull()
        assertThat(viva!!.status).isEqualTo(OccurrenceStatus.PENDING.name)

        // E a tela promete exatamente a data que o repositório armou.
        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = terca,
            chosenTime = LocalTime.of(18, 0),
            recurrence = rule,
            today = terca,
            now = noite,
            zone = zone,
            editing = true,
            skippedDates = setOf(amanha),
        )
        assertThat(promessa.recap).contains(AgendaFormat.longDate(depoisDeAmanha))
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(amanha))
    }

    /**
     * Vários tombstones seguidos: com 30/09 e 01/10 excluídos, a próxima viva é 02/10. Um passo
     * só da regra não basta — avançar uma data pararia numa que também está excluída.
     */
    @Test
    fun variosTombstonesSeguidosAvancamAteAProximaViva() = runBlocking {
        val noite = terca.atTime(20, 0).atZone(zone).toInstant()
        val amanha = LocalDate.of(2026, 9, 30)
        val depoisDeAmanha = LocalDate.of(2026, 10, 1)
        val depoisDeDepois = LocalDate.of(2026, 10, 2)
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())
        val seriesDao = FakeSeriesDao()
        val occDao = FakeOccurrenceDao()
        val repoDaNoite = TaskRepository(seriesDao, occDao, FixedAppClock(noite, zone), scheduler)
        val series = TaskSeries(
            id = "s-rem",
            title = "Remédio",
            zoneId = zone,
            localTime = LocalTime.of(8, 0),
            startLocalDate = terca,
            recurrence = rule,
            skippedDates = setOf(amanha, depoisDeAmanha),
            createdAt = noite,
            updatedAt = noite,
        )
        seriesDao.upsert(series.toEntity())
        val deHoje = OccurrenceIds.of(series.id, terca)
        occDao.upsert(
            TaskOccurrence(
                id = deHoje,
                seriesId = series.id,
                localDate = terca,
                scheduledAt = terca.atTime(8, 0).atZone(zone).toInstant(),
                status = OccurrenceStatus.PENDING,
            ).toEntity(),
        )

        repoDaNoite.editOccurrence(deHoje, "Remédio", terca, LocalTime.of(18, 0), rule)

        assertThat(occDao.get(OccurrenceIds.of(series.id, amanha))).isNull()
        assertThat(occDao.get(OccurrenceIds.of(series.id, depoisDeAmanha))).isNull()
        assertThat(occDao.get(OccurrenceIds.of(series.id, depoisDeDepois))).isNotNull()

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = terca,
            chosenTime = LocalTime.of(18, 0),
            recurrence = rule,
            today = terca,
            now = noite,
            zone = zone,
            editing = true,
            skippedDates = setOf(amanha, depoisDeAmanha),
        )
        assertThat(promessa.recap).contains(AgendaFormat.longDate(depoisDeDepois))
    }

    /**
     * O resumo da edição não anuncia uma regra que não rege a primeira ocorrência.
     *
     * O caso é o da caçada: série diária, ela abre a dose de 22/08 (um sábado) e escolhe "Toda
     * semana" + quinta. O que a edição grava está certo — a data tocada vale, e a série segue
     * dela (é o contrato, ver `ChoiceSchedule`) —, mas o resumo dizia "Vai avisar Sábado, 22 de
     * agosto de 2026 às 08:00. Dias úteis.": as duas metades da frase se contradizem, e ela salva
     * achando que o aviso é na segunda.
     *
     * A criação do mesmo caso nunca disse isso: lá a data escolhida é descartada e o resumo
     * promete a segunda. As duas metades aparecem aqui lado a lado de propósito — é a divergência
     * entre os dois caminhos que o defeito produzia.
     *
     * A data tocada continua sendo a que vale (o `droppedChoice` segue nulo, e o teste
     * `editandoADataEscolhidaEADataQueVale` continua verdadeiro): o que sai da frase é só o
     * anúncio da regra, que é a metade que não rege nada nesta edição. Quem diz a regra é o chip
     * "Repetir", que ela tocou e continua vendo na tela.
     */
    @Test
    fun aEdicaoNaoAnunciaARegraQueNaoRegeAPrimeiraOcorrencia() = runBlocking {
        val sabado = LocalDate.of(2026, 10, 3)
        val rule = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        val oitoDaManha = sabado.atTime(8, 0).atZone(zone).toInstant()

        val edicao = AgendaFormat.promiseOfChoice(
            chosenDate = sabado,
            chosenTime = LocalTime.of(9, 0),
            recurrence = rule,
            today = sabado,
            now = oitoDaManha,
            zone = zone,
            editing = true,
        )
        val criacao = AgendaFormat.promiseOfChoice(
            chosenDate = sabado,
            chosenTime = LocalTime.of(9, 0),
            recurrence = rule,
            today = sabado,
            now = oitoDaManha,
            zone = zone,
        )

        // A edição mantém a data tocada — e é isso que o resumo promete.
        assertThat(edicao.recap).contains(AgendaFormat.longDate(sabado))
        assertThat(edicao.recap).doesNotContain(rule.describePtBr())
        // E não há data descartada a explicar: a escolhida é a que vale.
        assertThat(edicao.droppedChoice).isNull()
        // A criação do mesmo caso é o contraste: lá a regra rege a primeira ocorrência e o
        // resumo a anuncia.
        assertThat(criacao.recap).contains(rule.describePtBr())
    }

    /**
     * O outro lado do critério: quando a regra **rege** a data da edição, o resumo continua
     * anunciando-a. Sem este caso, tirar a regra do resumo em toda edição passaria no teste de
     * cima — e ela perderia a informação de que a tarefa repete.
     */
    @Test
    fun aEdicaoAnunciaARegraQuandoElaRegeAPrimeiraOcorrencia() = runBlocking {
        val segunda = LocalDate.of(2026, 10, 5)
        val rule = recurrenceFor(RecurrenceKind.WEEKDAYS, segunda, emptySet())

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = segunda,
            chosenTime = LocalTime.of(9, 0),
            recurrence = rule,
            today = segunda,
            now = segunda.atTime(8, 0).atZone(zone).toInstant(),
            zone = zone,
            editing = true,
        )

        assertThat(promessa.recap).contains(AgendaFormat.longDate(segunda))
        assertThat(promessa.recap).contains(rule.describePtBr())
    }

    /**
     * E a escolha vencida da edição continua anunciando a regra: ali a data prometida vem da
     * própria regra, então ela rege a primeira ocorrência e a frase não se contradiz.
     */
    @Test
    fun aEdicaoVencidaContinuaAnunciandoARegra() = runBlocking {
        val noite = terca.atTime(20, 0).atZone(zone).toInstant()
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = terca,
            chosenTime = LocalTime.of(18, 0),
            recurrence = rule,
            today = terca,
            now = noite,
            zone = zone,
            editing = true,
        )

        assertThat(promessa.recap).contains(AgendaFormat.longDate(terca.plusDays(1)))
        assertThat(promessa.recap).contains(rule.describePtBr())
    }

    /** O que a tela monta: os mesmos argumentos que a `ConfirmDraftScreen` tem em mãos. */
    private fun promessa(draft: ParsedTaskDraft, today: LocalDate, now: Instant) =

        AgendaFormat.promiseOfChoice(
            chosenDate = draft.localDate!!,
            chosenTime = draft.localTime!!,
            recurrence = draft.recurrence,
            today = today,
            now = now,
            zone = zone,
        )

    private fun rascunho(
        title: String,
        date: LocalDate,
        time: LocalTime,
        rule: RecurrenceRule,
    ) = ParsedTaskDraft(
        title = title,
        localDate = date,
        localTime = time,
        recurrence = rule,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = title,
    )
}

/**
 * Grava **quando** o alarme foi armado, não só que foi armado.
 *
 * O defeito desta suíte é o instante que chega ao `AlarmManager`: um fake que só contasse ids
 * passava com o alarme marcado para ontem — disparo imediato — e foi assim que o defeito
 * atravessou. [Armed.fireAt] é o `nextReminderAt` que o `ReminderScheduler` entrega a
 * `setAlarmClock`, e é ele que os testes daqui comparam.
 */
private class TestScheduler : AlarmScheduler {
    data class Armed(val occurrenceId: String, val fireAt: Instant)

    val scheduled = mutableListOf<Armed>()

    override suspend fun quietHours(): QuietHours = QuietHours()
    override fun canScheduleExact(): Boolean = true
    override fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome {
        // Como o de verdade: sem `nextReminderAt` não há alarme para armar.
        val fireAt = occurrence.nextReminderAt
            ?: return SchedulerOutcome(inexact = false, scheduled = false)
        scheduled += Armed(occurrence.id, fireAt)
        return SchedulerOutcome(inexact = false, scheduled = true)
    }
    override fun cancel(occurrenceId: String) = Unit
    override fun scheduleRecovery(occurrenceId: String, at: Instant) = Unit
    override fun scheduleDailySweep(at: Instant) = Unit
}

private class FakeSeriesDao : SeriesDao {
    private val rows = linkedMapOf<String, SeriesEntity>()
    private val flow = MutableStateFlow<List<SeriesEntity>>(emptyList())
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<SeriesEntity>> = flow.map { it }
    override suspend fun upsert(entity: SeriesEntity) {
        rows[entity.id] = entity
        emit()
    }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        emit()
        return if (removed) 1 else 0
    }
}

private class FakeOccurrenceDao : OccurrenceDao {
    private val rows = linkedMapOf<String, OccurrenceEntity>()
    private val flow = MutableStateFlow<List<OccurrenceEntity>>(emptyList())
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun forSeries(seriesId: String) = rows.values.filter { it.seriesId == seriesId }
    override suspend fun byStatus(status: String) = rows.values.filter { it.status == status }
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<OccurrenceEntity>> = flow.map { it }
    override suspend fun upsert(entity: OccurrenceEntity): Long {
        rows[entity.id] = entity
        emit()
        return 1
    }
    override suspend fun upsertAll(entities: List<OccurrenceEntity>): List<Long> = entities.map { upsert(it) }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        emit()
        return if (removed) 1 else 0
    }
    override suspend fun deleteSeries(seriesId: String): Int {
        val ids = rows.filterValues { it.seriesId == seriesId }.keys.toList()
        ids.forEach { rows.remove(it) }
        emit()
        return ids.size
    }
}

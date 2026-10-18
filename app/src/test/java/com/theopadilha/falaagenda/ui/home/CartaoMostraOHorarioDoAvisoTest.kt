package com.theopadilha.falaagenda.ui.home

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmIds
import com.theopadilha.falaagenda.reminders.ReminderScheduler
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.editDraftOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * O horário que o cartão da agenda mostra para uma ocorrência é o horário em que o alarme dela
 * toca — e o rascunho de edição abre com o mesmo horário.
 *
 * O defeito medido pela caçada da segunda porta: o cartão lia `series.localTime` e o rascunho de
 * edição também, enquanto o `AlarmManager` tinha outro instante armado. A série tem UM horário, e
 * ele pode não ser o desta dose por três desenhos diferentes:
 *
 *  - a edição de uma dose de **outra data** preserva de propósito o `scheduledAt` das doses que
 *    ela não tocou (o contrato de `EditarDoseDaSerieNaoPerdeAsOutrasTest`) enquanto a série passa
 *    a carregar o horário novo;
 *  - o **adiamento** grava `nextReminderAt`/`snoozedUntil` sem tocar em `scheduledAt` nem em
 *    `localTime`;
 *  - o **silêncio noturno** desloca a repetição para as 08:00 do dia seguinte.
 *
 * Nos três ela lia no cartão uma hora e o remédio tocava em outra: perdia a dose ou tomava duas.
 * É o mesmo defeito que o widget tinha do lado de fora (#107), e a resposta é a mesma — quem
 * decide o instante é `occurrence.nextReminderAt`, a peça que o `ReminderScheduler` entrega ao
 * alarme.
 *
 * A medição é do par (rótulo, alarme) lado a lado, com Room real e `ReminderScheduler` real: o
 * que se compara é a hora que ela **lê** contra a hora que o `AlarmManager` **tem armada**.
 * Prender só o rótulo deixaria o teste verde no dia em que o alarme é que estivesse errado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class CartaoMostraOHorarioDoAvisoTest {
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private lateinit var container: AppContainer

    private val seriesId = "s-rem"
    private val hoje = LocalDate.of(2026, 8, 20)
    private val amanha = LocalDate.of(2026, 8, 21)
    private val idDe = { data: LocalDate -> OccurrenceIds.of(seriesId, data) }

    @Before
    fun setUp() {
        container = AppContainer(context)
    }

    private fun em(dia: LocalDate, hora: Int, minuto: Int = 0): Instant =
        dia.atTime(hora, minuto).atZone(zone).toInstant()

    private fun repo(agora: LocalDateTime) = TaskRepository(
        container.db.seriesDao(),
        container.db.occurrenceDao(),
        FixedAppClock(agora.atZone(zone).toInstant(), zone),
        ReminderScheduler(context, SettingsStore(context)),
    )

    /** Série "Remédio" que repete todo dia no horário dado — o horário que a **série** carrega. */
    private suspend fun semearSerie(horaDaSerie: LocalTime) {
        val criado = em(hoje.minusDays(1), 10)
        container.db.seriesDao().upsert(
            TaskSeries(
                id = seriesId,
                title = "Remédio",
                zoneId = zone,
                localTime = horaDaSerie,
                startLocalDate = hoje,
                recurrence = RecurrenceRule(RecurrenceKind.DAILY),
                createdAt = criado,
                updatedAt = criado,
            ).toEntity(),
        )
    }

    /**
     * Uma segunda série no mesmo dia, para a ordem dos itens poder divergir da ordem das horas.
     * O `id` sai do título: [idDa] e o `idDe` do remédio não podem colidir.
     */
    private suspend fun semearOutraSerie(titulo: String, horaDaSerie: LocalTime) {
        val criado = em(hoje.minusDays(1), 10)
        val id = "s-$titulo"
        container.db.seriesDao().upsert(
            TaskSeries(
                id = id,
                title = titulo,
                zoneId = zone,
                localTime = horaDaSerie,
                startLocalDate = hoje,
                recurrence = RecurrenceRule(RecurrenceKind.DAILY),
                createdAt = criado,
                updatedAt = criado,
            ).toEntity(),
        )
        container.db.occurrenceDao().upsert(
            TaskOccurrence(
                id = idDa(id, hoje),
                seriesId = id,
                localDate = hoje,
                scheduledAt = em(hoje, horaDaSerie.hour, horaDaSerie.minute),
                status = OccurrenceStatus.PENDING,
                nextReminderAt = em(hoje, horaDaSerie.hour, horaDaSerie.minute),
            ).toEntity(),
        )
    }

    private fun idDa(serie: String, data: LocalDate) = OccurrenceIds.of(serie, data)

    /**
     * A dose como ela chega viva ao toque dela: pendente, com o instante do aviso já gravado.
     * [marcadaPara] é a hora marcada (`scheduledAt`) e [avisoEm] o instante que o alarme vai
     * tocar — os dois divergem por desenho nos casos que este teste mede.
     */
    private suspend fun semearDose(
        data: LocalDate,
        marcadaPara: Instant,
        avisoEm: Instant,
        passo: Int = 0,
    ) {
        container.db.occurrenceDao().upsert(
            TaskOccurrence(
                id = idDe(data),
                seriesId = seriesId,
                localDate = data,
                scheduledAt = marcadaPara,
                status = OccurrenceStatus.PENDING,
                reminderStep = passo,
                nextReminderAt = avisoEm,
            ).toEntity(),
        )
    }

    /** O instante que o `AlarmManager` tem armado para esta ocorrência, lido do shadow. */
    private fun alarmeDe(data: LocalDate): Instant? {
        val requestCode = AlarmIds.requestCode(idDe(data), AlarmIds.ACTION_FIRE)
        return shadowOf(alarmManager).scheduledAlarms
            .firstOrNull { shadowOf(it.operation).requestCode == requestCode }
            ?.let { Instant.ofEpochMilli(it.triggerAtTime) }
    }

    /** O item como a home o recebe: pela mesma leitura de agenda que alimenta a lista. */
    private suspend fun itemDe(data: LocalDate): AgendaItem {
        val secao = repo(LocalDateTime.of(2026, 8, 20, 0, 0)).snapshotAgenda()
        return (secao.today + secao.upcoming).first { it.occurrence.id == idDe(data) }
    }

    /** A hora que o cartão mostra, no fuso da série. */
    private fun horaNoCartao(item: AgendaItem): LocalTime = AgendaFormat.occurrenceTime(item)

    /**
     * A edição de uma dose de outra data.
     *
     * Às 07:00 ela corrige a dose de **amanhã** para as 14:00. A série passa a valer 14:00, e a
     * dose de hoje continua armada às 08:00 — de propósito. O cartão de hoje dizia 14:00, que é o
     * horário da série, e o alarme tocava às 8h.
     */
    @Test
    fun aDosePreservadaPelaEdicaoDeOutraDataMostraOHorarioDoAlarmeDela() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            listOf(hoje, amanha, hoje.plusDays(2)).forEach { dia ->
                semearDose(dia, marcadaPara = em(dia, 8), avisoEm = em(dia, 8))
            }
            val repo = repo(LocalDateTime.of(2026, 8, 20, 7, 0))
            repo.rescheduleAll()

            repo.editOccurrence(
                idDe(amanha),
                "Remédio",
                amanha,
                LocalTime.of(14, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            // O alarme de hoje continua onde estava...
            assertThat(alarmeDe(hoje)).isEqualTo(em(hoje, 8))
            // ...e é esse o horário que o cartão dela mostra.
            val item = itemDe(hoje)
            assertThat(horaNoCartao(item)).isEqualTo(LocalTime.of(8, 0))
            assertThat(AgendaFormat.time(horaNoCartao(item)))
                .isEqualTo(AgendaFormat.time(em(hoje, 8).atZone(zone).toLocalTime()))
        }
    }

    /**
     * O adiamento.
     *
     * Às 08:00 ela toca "Adiar 30 min": o `snooze` grava `nextReminderAt`/`snoozedUntil` e não
     * toca em `scheduledAt` nem em `localTime`. O cartão dizia "Hoje · 08:00" — um instante que já
     * passou — e o aviso só tocaria às 08:30.
     */
    @Test
    fun aDoseAdiadaMostraOHorarioDoAdiamento() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            semearDose(hoje, marcadaPara = em(hoje, 8), avisoEm = em(hoje, 8))
            val repo = repo(LocalDateTime.of(2026, 8, 20, 8, 0))
            repo.rescheduleAll()

            repo.snooze(idDe(hoje), minutes = 30)

            val adiada = em(hoje, 8, 30)
            assertThat(alarmeDe(hoje)).isEqualTo(adiada)
            assertThat(horaNoCartao(itemDe(hoje))).isEqualTo(LocalTime.of(8, 30))
        }
    }

    /**
     * O silêncio noturno.
     *
     * Remédio às 22:00 com o silêncio padrão (22:00-08:00): o primeiro disparo entrega, a
     * repetição seguinte cai no silêncio e é deslocada para as 08:00 de amanhã. O cartão dizia
     * "Hoje · 22:00" — um instante já passado — para um aviso que só toca na manhã seguinte.
     */
    @Test
    fun aDoseDeslocadaPeloSilencioMostraODiaEaHoraDoAviso() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(22, 0))
            semearDose(
                hoje,
                marcadaPara = em(hoje, 22),
                avisoEm = em(amanha, 8),
                passo = 1,
            )
            repo(LocalDateTime.of(2026, 8, 20, 22, 30)).rescheduleAll()

            val item = itemDe(hoje)
            assertThat(horaNoCartao(item)).isEqualTo(LocalTime.of(8, 0))
            // E o dia acompanha: anunciar as 08:00 sob "Hoje" seria a mesma mentira com outra
            // roupa — um instante já passado sob o rótulo do que ainda vem.
            assertThat(AgendaFormat.occurrenceDay(item)).isEqualTo(amanha)
            assertThat(AgendaFormat.dateLabel(AgendaFormat.occurrenceDay(item), hoje)).isEqualTo("Amanhã")
            assertThat(alarmeDe(hoje)).isEqualTo(em(amanha, 8))
        }
    }

    /**
     * O rascunho da edição abre no horário do aviso — é ele que ela vê no campo "Horário" e
     * salva por cima.
     *
     * Sem esta metade, o cartão passaria a dizer 08:00 e a tela de edição continuaria abrindo em
     * 14:00: ela tocaria o cartão, leria outra hora e gravaria o horário errado.
     */
    @Test
    fun oRascunhoDaEdicaoAbreNoHorarioDoAviso() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            listOf(hoje, amanha).forEach { dia ->
                semearDose(dia, marcadaPara = em(dia, 8), avisoEm = em(dia, 8))
            }
            val repo = repo(LocalDateTime.of(2026, 8, 20, 7, 0))
            repo.rescheduleAll()
            repo.editOccurrence(
                idDe(amanha),
                "Remédio",
                amanha,
                LocalTime.of(14, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            val rascunho = editDraftOf(itemDe(hoje))

            assertThat(rascunho.localTime).isEqualTo(LocalTime.of(8, 0))
            // O dia continua o da ocorrência: é ele que a edição grava como data escolhida, e o
            // repositório mantém a dose de hoje onde ela está.
            assertThat(rascunho.localDate).isEqualTo(hoje)
            assertThat(alarmeDe(hoje)).isEqualTo(em(hoje, 8))
        }
    }

    /**
     * A dose adiada também decide o rascunho: ela abre a dose adiada para corrigir o nome e o
     * campo "Horário" não pode mostrar 08:00 enquanto o aviso está marcado para as 08:30.
     */
    @Test
    fun oRascunhoDaDoseAdiadaAbreNoHorarioDoAdiamento() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            semearDose(hoje, marcadaPara = em(hoje, 8), avisoEm = em(hoje, 8))
            val repo = repo(LocalDateTime.of(2026, 8, 20, 8, 0))
            repo.rescheduleAll()
            repo.snooze(idDe(hoje), minutes = 30)

            assertThat(editDraftOf(itemDe(hoje)).localTime).isEqualTo(LocalTime.of(8, 30))
        }
    }

    /**
     * Abrir a dose adiada para corrigir só o **nome** e salvar sem mexer no horário não desfaz o
     * adiamento.
     *
     * É o caminho real depois do fix: o rascunho abre com o que a tela mostra, e quem salva manda
     * de volta esse mesmo horário. O `editOccurrence` recalcula a escolha pelo horário que
     * recebeu; com o da **série** (08:00) já vencido às 08:05, a escolha nascia vencida — a dose
     * adiada virava não realizada e o aviso pulava para amanhã, calado. Com o do aviso (08:30), a
     * escolha segue de pé e o alarme fica onde ela o tinha posto.
     *
     * Sem esta metade, o teste do rascunho provaria só o campo na tela: salvar por cima dele é o
     * que decide se o adiamento sobrevive.
     */
    @Test
    fun salvarORascunhoSemMexerNoHorarioNaoDesfazOAdiamento() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            semearDose(hoje, marcadaPara = em(hoje, 8), avisoEm = em(hoje, 8))
            val repo = repo(LocalDateTime.of(2026, 8, 20, 8, 0))
            repo.rescheduleAll()
            repo.snooze(idDe(hoje), minutes = 30)

            // O que a tela manda de volta: os valores do rascunho que ela abriu, sem edição.
            val rascunho = editDraftOf(itemDe(hoje))
            val dataNoCampo = rascunho.localDate!!
            val horaNoCampo = rascunho.localTime!!
            repo(LocalDateTime.of(2026, 8, 20, 8, 5)).editOccurrence(
                occurrenceId = idDe(hoje),
                title = "Remédio da manhã",
                date = dataNoCampo,
                time = horaNoCampo,
                recurrence = rascunho.recurrence,
            )

            assertThat(alarmeDe(hoje)).isEqualTo(em(hoje, 8, 30))
            assertThat(container.db.occurrenceDao().get(idDe(hoje))?.status)
                .isEqualTo(OccurrenceStatus.PENDING.name)
            // O nome novo entrou: o teste mede o adiamento preservado, não uma escrita que não
            // aconteceu.
            assertThat(container.db.seriesDao().get(seriesId)?.title).isEqualTo("Remédio da manhã")
        }
    }

    /**
     * A manchete do topo anuncia o mesmo horário que o cartão logo abaixo.
     *
     * Sem esta metade a home se contradiz **dentro da mesma tela**: o cartão dizendo 08:00 e a
     * frase do topo dizendo 14:00 sobre a mesma tarefa. Fechar uma superfície não fecha o eixo —
     * foi assim que o #107 fechou o widget e deixou a home com o mesmo defeito.
     */
    @Test
    fun aMancheteAnunciaOHorarioDoAviso() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            listOf(hoje, amanha).forEach { dia ->
                semearDose(dia, marcadaPara = em(dia, 8), avisoEm = em(dia, 8))
            }
            val repo = repo(LocalDateTime.of(2026, 8, 20, 7, 0))
            repo.rescheduleAll()
            repo.editOccurrence(
                idDe(amanha),
                "Remédio",
                amanha,
                LocalTime.of(14, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            val manchete = homeHeadline(
                agendaUi = AgendaUi(
                    sections = repo(LocalDateTime.of(2026, 8, 20, 7, 0)).snapshotAgenda(),
                    loaded = true,
                    failed = false,
                ),
                nowTime = LocalTime.of(7, 0),
                today = hoje,
            )

            assertThat(manchete).contains("Remédio, hoje às 08:00")
            assertThat(manchete).doesNotContain("14:00")
            assertThat(alarmeDe(hoje)).isEqualTo(em(hoje, 8))
        }
    }

    /**
     * E o texto que sai do aplicativo para a família carrega a mesma hora do aviso.
     *
     * Aqui o erro não fica na tela dela: ela manda "Remédio às 14:00" para quem cuida dela e o
     * alarme toca às 8h — e quem recebe não tem como conferir.
     */
    @Test
    fun oCompartilhadoDoDiaLevaOHorarioDoAviso() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            listOf(hoje, amanha).forEach { dia ->
                semearDose(dia, marcadaPara = em(dia, 8), avisoEm = em(dia, 8))
            }
            val repo = repo(LocalDateTime.of(2026, 8, 20, 7, 0))
            repo.rescheduleAll()
            repo.editOccurrence(
                idDe(amanha),
                "Remédio",
                amanha,
                LocalTime.of(14, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            val secao = repo(LocalDateTime.of(2026, 8, 20, 7, 0)).snapshotAgenda()
            val texto = AgendaFormat.todayShare(shareLinesOf(secao.today, hoje))

            assertThat(texto).contains("Remédio às 08:00")
            assertThat(texto).doesNotContain("14:00")
        }
    }

    /**
     * A manchete escolhe o compromisso pela hora do **aviso**, e não pela hora marcada.
     *
     * O defeito é o mesmo eixo uma superfície acima: ela escolhia com `minByOrNull
     * { scheduledAt }` e descrevia com `occurrenceTime`. Quando o mais próximo deixa de ser o
     * marcado mais cedo — que é o que o adiamento faz —, a frase anuncia o compromisso errado, e
     * anuncia a hora dele com a mesma convicção.
     *
     * Aqui: o remédio era o mais cedo (07:00) e foi adiado para as 11:30; a consulta é às 08:00.
     * Quem vem primeiro agora é a consulta.
     */
    @Test
    fun aMancheteEscolheOPendentePelaHoraDoAviso() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(7, 0))
            semearDose(hoje, marcadaPara = em(hoje, 7), avisoEm = em(hoje, 7))
            semearOutraSerie("Consulta", horaDaSerie = LocalTime.of(8, 0))
            val repo = repo(LocalDateTime.of(2026, 8, 20, 7, 0))
            repo.rescheduleAll()

            repo.snooze(idDe(hoje), minutes = 270)

            val manchete = homeHeadline(
                agendaUi = AgendaUi(
                    sections = repo(LocalDateTime.of(2026, 8, 20, 7, 0)).snapshotAgenda(),
                    loaded = true,
                    failed = false,
                ),
                nowTime = LocalTime.of(7, 0),
                today = hoje,
            )

            assertThat(alarmeDe(hoje)).isEqualTo(em(hoje, 11, 30))
            assertThat(manchete).contains("Consulta, hoje às 08:00")
            assertThat(manchete).doesNotContain("Remédio")
        }
    }

    /**
     * E o compartilhado sai na ordem das horas que ele **anuncia**.
     *
     * O texto é montado a partir de `sectionsOf`, que ordena por `scheduledAt`; como a linha
     * carrega a hora do aviso, o adiamento fazia a dose das 07:00 (agora 11:30) sair **antes** da
     * consulta das 08:00 — a família recebia a lista fora de ordem, com a tarde na frente da
     * manhã.
     */
    @Test
    fun oCompartilhadoSaiNaOrdemDasHorasQueEleAnuncia() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(7, 0))
            semearDose(hoje, marcadaPara = em(hoje, 7), avisoEm = em(hoje, 7))
            semearOutraSerie("Consulta", horaDaSerie = LocalTime.of(8, 0))
            val repo = repo(LocalDateTime.of(2026, 8, 20, 7, 0))
            repo.rescheduleAll()

            repo.snooze(idDe(hoje), minutes = 270)

            val secao = repo(LocalDateTime.of(2026, 8, 20, 7, 0)).snapshotAgenda()
            val texto = AgendaFormat.todayShare(shareLinesOf(secao.today, hoje))

            assertThat(texto).contains("Consulta às 08:00")
            assertThat(texto.indexOf("Consulta às 08:00"))
                .isLessThan(texto.indexOf("Remédio às 11:30"))
        }
    }

    /**
     * A pendente de hoje cujo aviso já passou e não tem mais escada aberta é "atrasada".
     *
     * `lateMark` pergunta pelo **dia**, e só marca o que atravessou a meia-noite. A escada
     * encerrada (`nextReminderAt` nulo) com a hora já passada não era vista por ninguém: às 10:00
     * ela lia "Remédio, hoje · 08:00" — um instante que já passou, sem nada dizendo que passou. O
     * widget já marca esse caso como atrasado; o cartão de dentro, não.
     */
    @Test
    fun oCartaoMarcaComoAtrasadaAPendenteCujoAvisoJaPassou() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(8, 0))
            container.db.occurrenceDao().upsert(
                TaskOccurrence(
                    id = idDe(hoje),
                    seriesId = seriesId,
                    localDate = hoje,
                    scheduledAt = em(hoje, 8),
                    status = OccurrenceStatus.PENDING,
                    nextReminderAt = null,
                ).toEntity(),
            )
            val agora = LocalDateTime.of(2026, 8, 20, 10, 0)
            val item = repo(agora).snapshotAgenda().today.first { it.occurrence.id == idDe(hoje) }

            assertThat(item.occurrence.nextReminderAt).isNull()
            assertThat(AgendaFormat.isLate(item, hoje, agora.atZone(zone).toInstant())).isTrue()
            // O `lateMark` sozinho não via este caso — é ele que a peça nova estende.
            assertThat(AgendaFormat.lateMark(item.occurrence.localDate, hoje)).isNull()
        }
    }

    /**
     * O outro lado do critério, que é o que impede a marca de virar "tudo que passou das 8h está
     * atrasado": com a escada aberta, o aviso deslocado pelo silêncio noturno para as 08:00 de
     * **amanhã** é o próximo, e não um atraso.
     */
    @Test
    fun oAvisoDeslocadoParaAmanhaNaoViraAtraso() {
        runBlocking {
            semearSerie(horaDaSerie = LocalTime.of(22, 0))
            semearDose(hoje, marcadaPara = em(hoje, 22), avisoEm = em(amanha, 8), passo = 1)
            val agora = LocalDateTime.of(2026, 8, 20, 22, 30)
            val item = repo(agora).snapshotAgenda().today.first { it.occurrence.id == idDe(hoje) }

            assertThat(AgendaFormat.isLate(item, hoje, agora.atZone(zone).toInstant())).isFalse()
        }
    }
}

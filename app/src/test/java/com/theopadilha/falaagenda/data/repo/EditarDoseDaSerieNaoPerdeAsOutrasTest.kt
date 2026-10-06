package com.theopadilha.falaagenda.data.repo

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.local.toDomain
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmIds
import com.theopadilha.falaagenda.reminders.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
 * Editar uma dose de uma série recorrente não pode custar as outras doses.
 *
 * O que se mede aqui são os alarmes realmente entregues ao `AlarmManager` (o
 * `ReminderScheduler` de verdade, com o `ShadowAlarmManager` no lugar do sistema), não a
 * intenção do código: a premissa deste aplicativo é que o alarme toca.
 *
 * O defeito medido: série "Remédio" todo dia às 08:00, doses de 20, 21, 22 e 23/08 armadas. Às
 * 10:00 de 20/08 ela abre a dose de 22/08 e corrige o horário para 20:00. O `editOccurrence`
 * cancelava o alarme de **todas** as pendentes, apagava as linhas do banco e movia
 * `startLocalDate` para a data editada — e o preview só materializa `>= startLocalDate`. As
 * doses de 20 e 21/08 sumiam do banco e do `AlarmManager`, sem aviso, e o restart confirmava
 * (a varredura não as recria). No aparelho dela: "mudei o horário do remédio de amanhã e o de
 * hoje/amanhã parou de tocar".
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class EditarDoseDaSerieNaoPerdeAsOutrasTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val seriesDao = SerieDaoDeTeste()
    private val occurrenceDao = OcorrenciaDaoDeTeste()

    private val hoje = LocalDate.of(2026, 8, 20)
    private val seriesId = "s-rem"
    private val idDe = { dia: Int -> OccurrenceIds.of(seriesId, LocalDate.of(2026, 8, dia)) }

    private fun repo(agora: LocalDateTime = LocalDateTime.of(2026, 8, 20, 10, 0)) = TaskRepository(
        seriesDao,
        occurrenceDao,
        FixedAppClock(agora.atZone(zone).toInstant(), zone),
        ReminderScheduler(context, SettingsStore(context)),
    )

    /**
     * Semeia a série e as doses já materializadas e armadas — o estado do aparelho
     * depois de um start do processo (`rescheduleAll`), que é como as doses chegam vivas ao
     * toque dela.
     */
    private suspend fun serieComDosesArmadas(
        dias: IntRange = 20..23,
        agora: LocalDateTime = LocalDateTime.of(2026, 8, 20, 10, 0),
        inicioDaSerie: LocalDate = hoje,
    ): TaskRepository {
        val series = TaskSeries(
            id = seriesId,
            title = "Remédio",
            zoneId = zone,
            localTime = LocalTime.of(8, 0),
            startLocalDate = inicioDaSerie,
            recurrence = RecurrenceRule(RecurrenceKind.DAILY),
            createdAt = Instant.parse("2026-08-19T10:00:00Z"),
            updatedAt = Instant.parse("2026-08-19T10:00:00Z"),
        )
        seriesDao.upsert(series.toEntity())
        dias.forEach { dia ->
            val data = LocalDate.of(2026, 8, dia)
            val em = data.atTime(8, 0).atZone(zone).toInstant()
            occurrenceDao.upsert(
                TaskOccurrence(
                    id = OccurrenceIds.of(seriesId, data),
                    seriesId = seriesId,
                    localDate = data,
                    scheduledAt = em,
                    status = OccurrenceStatus.PENDING,
                    nextReminderAt = em,
                ).toEntity(),
            )
        }
        val repo = repo(agora)
        repo.rescheduleAll()
        return repo
    }

    private suspend fun serieComQuatroDosesArmadas(): TaskRepository = serieComDosesArmadas()

    /** Os alarmes de dose armados, por ocorrência — a varredura da virada do dia não entra. */
    private fun alarmesDeDose(): Map<String, Instant> {
        val porRequestCode = (20..25).associate { dia ->
            AlarmIds.requestCode(idDe(dia), AlarmIds.ACTION_FIRE) to idDe(dia)
        }
        return shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val id = porRequestCode[shadowOf(alarm.operation).requestCode] ?: return@mapNotNull null
            id to Instant.ofEpochMilli(alarm.triggerAtTime)
        }.toMap()
    }

    private fun emSaoPaulo(dia: Int, hora: Int, minuto: Int = 0) =
        LocalDateTime.of(2026, 8, dia, hora, minuto).atZone(zone).toInstant()

    /**
     * A dose de amanhã tem um adiamento em curso: ela tocou "Adiar 30 min" e o aviso está
     * marcado para as 08:30, ainda por vir. Editar a dose de depois de amanhã não pode
     * reescrever essa linha — o `materialize` do preview monta a ocorrência do zero e o
     * adiamento some, junto com o degrau que ele já tinha andado.
     */
    private suspend fun adiarADoseDeAmanha() {
        val linha = occurrenceDao.get(idDe(21))!!.toDomain()
        val adiada = emSaoPaulo(21, 8, 30)
        occurrenceDao.upsert(
            linha.copy(snoozedUntil = adiada, nextReminderAt = adiada, reminderStep = 3).toEntity(),
        )
    }

    @Test
    fun editarOHorarioDeUmaDoseNaoApagaNemDesarmaAsOutras() {
        runBlocking {
            val repo = serieComQuatroDosesArmadas()
            val antes = alarmesDeDose()
            // O ponto de partida do cenário medido: as quatro doses armadas.
            assertThat(antes.keys).containsExactly(idDe(20), idDe(21), idDe(22), idDe(23))
            assertThat(antes[idDe(21)]).isEqualTo(emSaoPaulo(21, 8))
            assertThat(antes[idDe(23)]).isEqualTo(emSaoPaulo(23, 8))

            repo.editOccurrence(
                idDe(22),
                "Remédio",
                LocalDate.of(2026, 8, 22),
                LocalTime.of(20, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            // A dose editada vale no horário novo...
            assertThat(alarmesDeDose()[idDe(22)]).isEqualTo(emSaoPaulo(22, 20))
            // ...e a série a segue: a dose de 23/08, que não foi tocada mas vem **depois** da
            // data nova, passa a valer no horário novo também — é o horário da série, e a série
            // mudou. (Antes ela era simplesmente cancelada e apagada; o preview não a trazia de
            // volta porque parava na data tocada.)
            assertThat(alarmesDeDose()[idDe(23)]).isEqualTo(emSaoPaulo(23, 20))
            // ...e as outras doses continuam existindo e tocando. A de amanhã é a que dói no
            // aparelho dela: era ela que sumia, com o banco confirmando a perda no restart.
            assertThat(occurrenceDao.get(idDe(21))).isNotNull()
            assertThat(occurrenceDao.get(idDe(20))).isNotNull()
            // E a série não foi empurrada para frente: o âncora da regra é piso de toda
            // materialização, e movê-lo apagava as datas anteriores.
            assertThat(seriesDao.get(seriesId)!!.toDomain().startLocalDate).isEqualTo(hoje)
        }
    }

    /**
     * O caso que a primeira versão deste teste deixava passar: com a dose de 22/08 editada, a
     * linha de 21/08 é reescrita pelo preview — e uma linha reescrita perde o que ela vivia.
     * Aqui ela está adiada para as 08:30 e o degrau já andou; se a edição da dose seguinte
     * sobrescrever essa linha, o adiamento morre e o aviso que ela pediu para depois volta
     * para as 08:00 — ou pior, para o instante errado da série nova.
     */
    @Test
    fun editarOutraDoseNaoApagaOAdiamentoDaDoseDeAmanha() {
        runBlocking {
            val repo = serieComQuatroDosesArmadas()
            val alarmeOriginal = alarmesDeDose()[idDe(21)]
            adiarADoseDeAmanha()
            val adiada = emSaoPaulo(21, 8, 30)

            repo.editOccurrence(
                idDe(22),
                "Remédio",
                LocalDate.of(2026, 8, 22),
                LocalTime.of(20, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            // A linha que ela adiou não foi reescrita pelo preview: o adiamento e o degrau
            // que ele já andou continuam lá.
            val depois = occurrenceDao.get(idDe(21))!!.toDomain()
            assertThat(depois.snoozedUntil).isEqualTo(adiada)
            assertThat(depois.nextReminderAt).isEqualTo(adiada)
            assertThat(depois.reminderStep).isEqualTo(3)
            // E o alarme dela não foi cancelado junto com os das doses reescritas: continua o
            // que estava armado.
            assertThat(alarmesDeDose()[idDe(21)]).isEqualTo(alarmeOriginal)
            // O contrato da edição, medido: a série tem UM horário, e ele passa a valer da data
            // tocada para frente. As doses anteriores — hoje e amanhã — ficam no horário antigo
            // e é justamente por isso que a de amanhã continua tocando às 08:00. Reescrever só o
            // cartão tocado (o outro candidato de fix) traria a de amanhã para as 20:00 sem ela
            // ter pedido.
            assertThat(alarmesDeDose()[idDe(21)]).isEqualTo(emSaoPaulo(21, 8))
            assertThat(alarmesDeDose()[idDe(20)]).isEqualTo(emSaoPaulo(20, 8))
        }
    }

    /**
     * Mover a dose de 22/08 para 24/08 não pode apagar a de 23/08.
     *
     * O cartão tocado passa a valer em 24/08 e a série o segue — mas a dose de 23/08 não foi
     * tocada, e um corte pela data **nova** a levaria junto, sem que nenhum caminho a
     * recriasse. O que fica intocado é o que vem antes do cartão que ela tocou.
     */
    @Test
    fun moverADataDaDoseNaoApagaAsQueFicamNoMeio() {
        runBlocking {
            val repo = serieComQuatroDosesArmadas()
            val vinteETres = alarmesDeDose()[idDe(23)]

            repo.editOccurrence(
                idDe(22),
                "Remédio",
                LocalDate.of(2026, 8, 24),
                LocalTime.of(20, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            assertThat(occurrenceDao.get(idDe(23))).isNotNull()
            assertThat(alarmesDeDose()[idDe(23)]).isEqualTo(vinteETres)
            assertThat(occurrenceDao.get(idDe(21))).isNotNull()
        }
    }

    /**
     * O caso sem ambiguidade nenhuma: ela só corrige o título da dose de 22/08. Nada mais
     * mudou na série, então o alarme das outras doses tem que ficar exatamente onde estava —
     * mesmo instante, não só "algum alarme". Antes, todas as outras eram canceladas e
     * apagadas por qualquer campo.
     */
    @Test
    fun editarSoOTituloNaoMudaOAlarmeDasOutrasDoses() {
        runBlocking {
            val repo = serieComQuatroDosesArmadas()
            val antes = alarmesDeDose()

            repo.editOccurrence(
                idDe(22),
                "Remédio da pressão",
                LocalDate.of(2026, 8, 22),
                LocalTime.of(8, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            val depois = alarmesDeDose()
            assertThat(depois[idDe(21)]).isEqualTo(antes[idDe(21)])
            assertThat(depois[idDe(23)]).isEqualTo(antes[idDe(23)])
            assertThat(depois[idDe(20)]).isEqualTo(antes[idDe(20)])
            assertThat(depois[idDe(22)]).isEqualTo(antes[idDe(22)])
        }
    }

    /**
     * A dose de hoje, preservada pela edição, não pode mudar de horário no próximo start.
     *
     * O horário novo da série vale do cartão tocado para frente, e é isso que a edição grava:
     * às 07:00 de 20/08, editar a dose de **amanhã** (21/08) para as 14:00 deixa a de hoje
     * intacta em 08:00. Só que a série tem UM horário, e ele agora é 14:00 — e o `rescheduleAll`
     * de todo boot/abertura rematerializa a ocorrência do dia com o horário da série, sem saber
     * que a de hoje foi preservada de propósito. No aparelho dela: "mudei o horário do remédio de
     * amanhã e o de hoje parou de tocar" — o de hoje passava a tocar 14:00 em silêncio.
     *
     * O start é um repositório novo sobre o mesmo banco, que é o que o boot faz.
     */
    @Test
    fun doseDeHojePreservadaPelaEdicaoMantemOHorarioDepoisDoRestart() {
        runBlocking {
            val repo = serieComDosesArmadas(
                dias = 20..23,
                agora = LocalDateTime.of(2026, 8, 20, 7, 0),
            )
            val deHoje = emSaoPaulo(20, 8)
            assertThat(alarmesDeDose()[idDe(20)]).isEqualTo(deHoje)

            repo.editOccurrence(
                idDe(21),
                "Remédio",
                LocalDate.of(2026, 8, 21),
                LocalTime.of(14, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            // A edição preserva a dose de hoje...
            assertThat(alarmesDeDose()[idDe(20)]).isEqualTo(deHoje)
            // ...e a série segue no horário novo da data tocada para frente.
            assertThat(alarmesDeDose()[idDe(21)]).isEqualTo(emSaoPaulo(21, 14))

            repo(LocalDateTime.of(2026, 8, 20, 7, 0)).rescheduleAll()

            // O restart não pode reescrever a dose de hoje com o horário da série: o que ela
            // tocou foi a de amanhã, e o alarme de hoje tem que continuar onde estava.
            assertThat(alarmesDeDose()[idDe(20)]).isEqualTo(deHoje)
            val hoje = occurrenceDao.get(idDe(20))!!.toDomain()
            assertThat(hoje.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(hoje.scheduledAt).isEqualTo(deHoje)
            assertThat(hoje.nextReminderAt).isEqualTo(deHoje)
            // E a série continua no horário novo para o resto.
            assertThat(alarmesDeDose()[idDe(21)]).isEqualTo(emSaoPaulo(21, 14))
        }
    }

    /**
     * Mover o cartão de 25/08 para 20/08 não pode apagar as doses do meio (23 e 24/08).
     *
     * O corte das linhas reescritas é pela **data nova**, então tudo que é `>= 20/08` era
     * cancelado e apagado — inclusive as doses de 23 e 24/08, que vêm depois da data nova mas
     * antes do cartão que ela tocou e que, pela regra da edição, deviam ficar intactas. O
     * preview só materializa três datas a partir da data nova, então as duas não voltavam nem
     * no restart: é o P0 original na direção oposta.
     */
    @Test
    fun moverADataParaTrasNaoApagaAsDosesQueFicamNoMeio() {
        runBlocking {
            val repo = serieComDosesArmadas(dias = 20..25)
            val antes = alarmesDeDose()
            assertThat(antes.keys)
                .containsExactly(idDe(20), idDe(21), idDe(22), idDe(23), idDe(24), idDe(25))

            repo.editOccurrence(
                idDe(25),
                "Remédio",
                LocalDate.of(2026, 8, 20),
                LocalTime.of(20, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            // O cartão que ela tocou aterrissa na data nova, no horário novo...
            assertThat(alarmesDeDose()[idDe(20)]).isEqualTo(emSaoPaulo(20, 20))
            // ...e a linha que ele deixou para trás não fica pendurada.
            assertThat(occurrenceDao.get(idDe(25))).isNull()
            // ...e o que está estritamente antes do cartão tocado permanece intacto — mesmo
            // vindo depois da data nova.
            listOf(21, 22, 23, 24).forEach { dia ->
                assertThat(occurrenceDao.get(idDe(dia))).isNotNull()
                assertThat(alarmesDeDose()[idDe(dia)]).isEqualTo(emSaoPaulo(dia, 8))
            }

            repo().rescheduleAll()

            listOf(21, 22, 23, 24).forEach { dia ->
                assertThat(occurrenceDao.get(idDe(dia))).isNotNull()
                assertThat(alarmesDeDose()[idDe(dia)]).isEqualTo(emSaoPaulo(dia, 8))
            }
        }
    }

    /**
     * A data de destino já vencida não pode ficar com o alarme velho de pé.
     *
     * O cartão de 25/08 passa a valer em 20/08, cujo instante (08:00) já passou quando o relógio
     * está em 10:00: a peça da escolha arquiva 20/08 como não realizada e arma a próxima data
     * viva, que é 21/08. O corte não alcança a data de destino por data — ela é **anterior** ao
     * cartão tocado, que é justamente o que o "antes" preserva —, então é o cartão tocado que
     * arrasta a data de destino para a lista de reescritas. Sem isso, a linha de 20/08 ficava
     * armada num estado que o banco já dá como não realizada: ocorrência não realizada não
     * carrega alarme, e o aviso velho dela continuava entregue ao `AlarmManager`.
     */
    @Test
    fun moverParaUmaDataVencidaNaoDeixaOAlarmeDaquelaDataDePe() {
        runBlocking {
            val repo = serieComDosesArmadas(dias = 20..25)
            // Ponto de partida: a data de destino está armada no horário da série.
            assertThat(alarmesDeDose()[idDe(20)]).isEqualTo(emSaoPaulo(20, 8))

            repo.editOccurrence(
                idDe(25),
                "Remédio",
                LocalDate.of(2026, 8, 20),
                LocalTime.of(8, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            // A data de destino venceu: virou não realizada...
            assertThat(occurrenceDao.get(idDe(20))!!.toDomain().status)
                .isEqualTo(OccurrenceStatus.MISSED)
            // ...e o alarme dela saiu junto, em vez de ficar armado numa ocorrência arquivada.
            assertThat(alarmesDeDose().keys).doesNotContain(idDe(20))
            // O cartão tocado não fica pendurado na data antiga...
            assertThat(occurrenceDao.get(idDe(25))).isNull()
            // ...e a próxima data viva continua tocando às 08:00.
            assertThat(alarmesDeDose()[idDe(21)]).isEqualTo(emSaoPaulo(21, 8))
        }
    }

    /**
     * A data nova pode cair antes do início da série — o `startLocalDate` desce junto e vira o
     * piso da materialização. O que está estritamente antes do cartão tocado continua de pé.
     */
    @Test
    fun moverADataParaAntesDoInicioDaSerieNaoApagaAsAnterioresAoTocado() {
        runBlocking {
            val repo = serieComDosesArmadas(dias = 20..23)

            repo.editOccurrence(
                idDe(22),
                "Remédio",
                LocalDate.of(2026, 8, 18),
                LocalTime.of(8, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            assertThat(seriesDao.get(seriesId)!!.toDomain().startLocalDate)
                .isEqualTo(LocalDate.of(2026, 8, 18))
            // As doses de 20 e 21/08 vêm antes do cartão tocado e continuam armadas no horário
            // delas. O alarme da de hoje é a prova: ela já tocou às 08:00 e o relógio está em
            // 10:00, então o instante dela é passado — recriada pelo preview, ela não seria
            // armada, e é justamente o que acontecia quando o corte era pela data nova (18/08).
            listOf(20, 21).forEach { dia ->
                assertThat(occurrenceDao.get(idDe(dia))).isNotNull()
                assertThat(alarmesDeDose()[idDe(dia)]).isEqualTo(emSaoPaulo(dia, 8))
            }

            repo().rescheduleAll()

            listOf(20, 21).forEach { dia ->
                assertThat(occurrenceDao.get(idDe(dia))).isNotNull()
                assertThat(alarmesDeDose()[idDe(dia)]).isEqualTo(emSaoPaulo(dia, 8))
            }
        }
    }
}

private class SerieDaoDeTeste : SeriesDao {
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

private class OcorrenciaDaoDeTeste : OccurrenceDao {
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

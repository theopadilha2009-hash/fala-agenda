package com.theopadilha.falaagenda.ui.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A caixa "Pode salvar?" promete o dia em que o aviso vai tocar — a data que o salvar vai
 * criar, e não a do rascunho.
 *
 * O caminho comum já concordava (o parser resolve a data de uma regra com o mesmo
 * `firstOnOrAfter`), mas a caixa mostrava a data do rascunho: um rascunho contraditório —
 * "cabelo sábado dias úteis às 9h" — chegava aqui dizendo "Vai avisar Sábado, 3 de outubro de
 * 2026 às 09:00. Dias úteis." e o salvar criava segunda.
 *
 * Não é teste de composição: esta suíte não tem como subir a caixa (o módulo roda sem
 * recursos do Android, `isIncludeAndroidResources = false`), então o que se prende aqui é a
 * peça que a caixa desenha — [quickConfirmPromise], no arquivo dela — contra a ocorrência
 * que o repositório grava de verdade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class QuickConfirmDialogTest {
    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    @Test
    fun aCaixaNaoPrometeODiaQueOAgendamentoDescarta() {
        val hoje = LocalDate.now()
        val sabado = hoje.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        val draft = rascunho("Cabelo", sabado, RecurrenceRule(RecurrenceKind.WEEKDAYS))

        val salvo = runBlocking { container.tasks.saveDraft(draft) }
        // A regra não tem sábado: a ocorrência nasce na segunda seguinte.
        assertThat(salvo.occurrence.localDate).isNotEqualTo(sabado)

        val promessa = quickConfirmPromise(draft, hoje, Instant.now(), ZoneId.systemDefault())

        assertThat(promessa).isNotNull()
        assertThat(promessa!!.recap).contains(AgendaFormat.longDate(salvo.occurrence.localDate))
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(sabado))
        // E a data do rascunho não some em silêncio: a caixa diz por que o aviso é outro dia.
        assertThat(promessa.droppedChoice).isNotNull()
    }

    /** Quando a data do rascunho é a que vale, a caixa promete exatamente ela. */
    @Test
    fun aCaixaPrometeADataDoRascunhoQuandoElaVale() {
        val hoje = LocalDate.now()
        val amanha = hoje.plusDays(1)
        val draft = rascunho("Cabelo", amanha, RecurrenceRule(RecurrenceKind.NONE))

        val salvo = runBlocking { container.tasks.saveDraft(draft) }

        val promessa = quickConfirmPromise(draft, hoje, Instant.now(), ZoneId.systemDefault())

        assertThat(promessa!!.recap).contains(AgendaFormat.longDate(salvo.occurrence.localDate))
        assertThat(promessa.droppedChoice).isNull()
    }

    /** Sem data ou sem horário não há o que prometer — e a caixa não mostra resumo nenhum. */
    @Test
    fun semDataOuHorarioNaoHaPromessa() {
        val semHora = rascunho("Cabelo", LocalDate.now().plusDays(1), RecurrenceRule()).copy(localTime = null)

        assertThat(quickConfirmPromise(semHora, LocalDate.now(), Instant.now(), ZoneId.systemDefault())).isNull()
    }

    private fun rascunho(titulo: String, data: LocalDate, rule: RecurrenceRule) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = LocalTime.of(9, 0),
        recurrence = rule,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
    )
}

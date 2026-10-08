package com.theopadilha.falaagenda.widget

import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * O widget anuncia a hora do **aviso que vai tocar**, não a hora da série.
 *
 * O defeito medido: a escolha de qual tarefa mostrar sai de `occurrence.scheduledAt`, e o rótulo
 * de hora sai de `series.localTime`. Os dois divergem por desenho em três situações — a dose de
 * outra data editada, o adiamento e o silêncio noturno —, e nas três o widget mentia sobre a hora
 * do próximo aviso enquanto o `AlarmManager` tinha outra armada. A pergunta que estes testes
 * respondem é a mesma que o revisor fará: a hora mostrada é a hora do alarme que vai tocar?
 *
 * A medição é do `RemoteViews` **aplicado**: o layout é inflado e o texto é lido do `TextView`,
 * não da intenção do código que o monta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class AgendaWidgetHoraDoAvisoTest {
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val hoje: LocalDate = LocalDate.of(2026, 10, 7)
    private val amanha: LocalDate = hoje.plusDays(1)

    private fun em(dia: LocalDate, hora: Int, minuto: Int = 0): Instant =
        dia.atTime(hora, minuto).atZone(zone).toInstant()

    /**
     * Série "Remédio" que repete todo dia. [horaDaSerie] é o horário que a **série** carrega — e é
     * justamente ele que o widget mostrava, mesmo quando a dose exibida tinha outro instante
     * armado.
     */
    private fun serie(horaDaSerie: LocalTime): TaskSeries = TaskSeries(
        id = "s-rem",
        title = "Remédio",
        zoneId = zone,
        localTime = horaDaSerie,
        startLocalDate = hoje,
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        createdAt = em(hoje.minusDays(1), 10),
        updatedAt = em(hoje.minusDays(1), 10),
    )

    private fun ocorrencia(
        series: TaskSeries,
        dia: LocalDate,
        marcadaPara: Instant,
        avisoEm: Instant?,
        adiadaPara: Instant? = null,
        passo: Int = 0,
    ): AgendaItem = AgendaItem(
        occurrence = TaskOccurrence(
            id = "${series.id}:$dia",
            seriesId = series.id,
            localDate = dia,
            scheduledAt = marcadaPara,
            status = OccurrenceStatus.PENDING,
            reminderStep = passo,
            nextReminderAt = avisoEm,
            lastReminderAt = null,
            snoozedUntil = adiadaPara,
        ),
        series = series,
    )

    private fun secoes(vararg itens: AgendaItem): AgendaSections = AgendaSections(
        today = itens.toList(),
        upcoming = emptyList(),
        completed = emptyList(),
        missed = emptyList(),
    )

    /** O `RemoteViews` aplicado de verdade: o que o launcher pintaria na tela dela. */
    private fun naTela(sections: AgendaSections, now: Instant): Tela {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = AgendaWidgetProvider.snapshotOf(sections, hoje, now)
        val remote = AgendaWidgetProvider.views(context, snapshot, colors = null)
        val raiz = remote.apply(context, FrameLayout(context))
        return Tela(
            kicker = raiz.findViewById<TextView>(R.id.widget_kicker).text.toString(),
            quando = raiz.findViewById<TextView>(R.id.widget_when).text.toString(),
        )
    }

    private data class Tela(val kicker: String, val quando: String)

    /**
     * A dose de hoje preservada pela edição da dose de amanhã.
     *
     * Às 07:00 ela edita a dose de **amanhã** para as 14:00. A edição preserva de propósito o
     * `scheduledAt` das doses que ela não tocou (o contrato de
     * `EditarDoseDaSerieNaoPerdeAsOutrasTest`), mas a **série** passa a carregar o horário novo.
     * O widget lia o horário da série e anunciava 14:00 para a dose de hoje, cujo alarme o
     * `AlarmManager` tem armado para as 08:00 — ela olha o telefone às 7h para saber a que hora
     * tomar o remédio, lê 14:00 e o alarme toca às 8h.
     */
    @Test
    fun aDoseDeHojePreservadaAnunciaOHorarioDoAlarmeDela() {
        val series = serie(horaDaSerie = LocalTime.of(14, 0))
        val deHoje = ocorrencia(
            series,
            hoje,
            marcadaPara = em(hoje, 8),
            avisoEm = em(hoje, 8),
        )

        val tela = naTela(secoes(deHoje), now = em(hoje, 7))

        assertThat(tela.quando).isEqualTo("Hoje · 08:00")
        assertThat(tela.kicker).isEqualTo("Próxima")
    }

    /**
     * O adiamento.
     *
     * Às 08:00 ela toca "Adiar 30 min": o `snooze` grava `snoozedUntil`/`nextReminderAt` e não
     * toca em `scheduledAt` nem em `series.localTime` — e o widget não lia nenhum dos dois que
     * mudaram. Ela olha o widget, vê "Hoje · 08:00", conclui que já passou e toma duas vezes.
     */
    @Test
    fun oAvisoAdiadoAnunciaAHoraDoAdiamento() {
        val series = serie(horaDaSerie = LocalTime.of(8, 0))
        val adiada = ocorrencia(
            series,
            hoje,
            marcadaPara = em(hoje, 8),
            avisoEm = em(hoje, 8, 30),
            adiadaPara = em(hoje, 8, 30),
            passo = 3,
        )

        val tela = naTela(secoes(adiada), now = em(hoje, 8))

        assertThat(tela.quando).isEqualTo("Hoje · 08:30")
        assertThat(tela.kicker).isEqualTo("Próxima")
    }

    /**
     * O silêncio noturno.
     *
     * Remédio às 22:00 com o silêncio padrão (22:00-08:00): o primeiro disparo entrega, a repetição
     * seguinte cai no silêncio e é deslocada para as 08:00 de amanhã. O widget lia a hora da série
     * e anunciava "Próxima — Hoje · 22:00" — um instante que já passou, sob o kicker do que ainda
     * vem —, e a tela de fora mentia a noite inteira.
     */
    @Test
    fun oAvisoDeslocadoPeloSilencioAnunciaOInstanteQueVaiTocar() {
        val series = serie(horaDaSerie = LocalTime.of(22, 0))
        val deslocada = ocorrencia(
            series,
            hoje,
            marcadaPara = em(hoje, 22),
            avisoEm = em(amanha, 8),
            passo = 1,
        )

        val tela = naTela(secoes(deslocada), now = em(hoje, 22, 30))

        assertThat(tela.quando).isEqualTo("Amanhã · 08:00")
        assertThat(tela.kicker).isEqualTo("Próxima")
    }

    /**
     * O adiamento também decide **qual** tarefa o widget mostra, não só a hora dela.
     *
     * A dose das 08:00 foi adiada para as 08:30 e há outra tarefa às 09:00. Aos 08:15, a hora
     * marcada da adiada já passou — e escolher por ela descartava a adiada da lista de candidatas,
     * elegendo a das 09:00: o aviso que ela pediu para daqui a quinze minutos sumia da tela de
     * fora, e o widget anunciava a tarefa seguinte. Escolher pelo instante do aviso mantém a
     * adiada, que é a que vai tocar primeiro.
     */
    @Test
    fun oAvisoAdiadoGanhaDaTarefaSeguinteNaEscolhaDeQualMostrar() {
        val series = serie(horaDaSerie = LocalTime.of(8, 0))
        val adiada = ocorrencia(
            series,
            hoje,
            marcadaPara = em(hoje, 8),
            avisoEm = em(hoje, 8, 30),
            adiadaPara = em(hoje, 8, 30),
            passo = 3,
        )
        val seguinte = ocorrencia(
            serie(horaDaSerie = LocalTime.of(9, 0)),
            hoje,
            marcadaPara = em(hoje, 9),
            avisoEm = em(hoje, 9),
        )

        val tela = naTela(secoes(adiada, seguinte), now = em(hoje, 8, 15))

        assertThat(tela.quando).isEqualTo("Hoje · 08:30")
        assertThat(tela.kicker).isEqualTo("Próxima")
    }

    /**
     * O piso continua sendo a hora marcada: sem aviso armado (a escada terminou, ou a ocorrência
     * nasceu vencida sem alarme) não há instante de aviso a mostrar, e o que resta é o `scheduledAt`
     * — que é o que o widget sempre mostrou.
     */
    @Test
    fun semAvisoArmadoOWidgetMostraAHoraMarcada() {
        val series = serie(horaDaSerie = LocalTime.of(8, 0))
        val semAlarme = ocorrencia(
            series,
            hoje,
            marcadaPara = em(hoje, 15),
            avisoEm = null,
        )

        val tela = naTela(secoes(semAlarme), now = em(hoje, 12))

        assertThat(tela.quando).isEqualTo("Hoje · 15:00")
        assertThat(tela.kicker).isEqualTo("Próxima")
    }
}

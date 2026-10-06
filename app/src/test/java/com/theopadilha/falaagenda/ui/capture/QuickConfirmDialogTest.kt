package com.theopadilha.falaagenda.ui.capture

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
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
 * A caixa "Pode salvar?" — as duas coisas que ela precisa acertar.
 *
 * A promessa: o dia em que o aviso vai tocar, que é a data que o salvar cria e não a do
 * rascunho. O caminho comum já concordava (o parser resolve a data de uma regra com o mesmo
 * `firstOnOrAfter`), mas a caixa mostrava a data do rascunho: um rascunho contraditório —
 * "cabelo sábado dias úteis às 9h" — chegava aqui dizendo "Vai avisar Sábado, 3 de outubro de
 * 2026 às 09:00. Dias úteis." e o salvar criava segunda. Essa parte prende [quickConfirmPromise],
 * no arquivo da caixa, contra a ocorrência que o repositório grava de verdade.
 *
 * A hierarquia: qual dos dois botões é o de destaque. O módulo sobe a composição de verdade
 * (`isIncludeAndroidResources = true`, `app/build.gradle.kts:107`), então os botões são nós
 * reais e medíveis — é a posição deles que o caso de hierarquia usa.
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

    @get:Rule
    val compose = createComposeRule()

    /**
     * O confirmar é o botão de destaque; o cancelar é o de saída. Nesta caixa ela confirma o
     * que acabou de falar, e com "Cancelar" no slot de destaque o reflexo era cancelar e perder
     * o recado.
     *
     * O caso mede a **posição**, não o par tag/texto. A versão anterior prendia
     * `onNodeWithTag("quick_confirm_confirmar")` contra "Salvar" — mas a tag viajava no mesmo
     * argumento que escolhe o slot, então trocar os dois blocos inteiros de volta (com as tags
     * no lugar) mantinha o teste verde: ele só pegava um `git revert`. Sem as tags, quem
     * separa os dois slots é a geometria: o `confirmButton` do M3 desenha acima do
     * `dismissButton`, e os dois botões têm a mesma largura e a mesma altura mínima (56.dp),
     * então o de destaque é o de cima. Trocar os blocos de slot empurra o "Salvar" para baixo
     * e o caso falha.
     */
    @Test
    fun oConfirmarEOQueFicaNoSlotPrimario() {
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                QuickConfirmDialog(
                    draft = rascunho("Tomar remédio", LocalDate.now().plusDays(1), RecurrenceRule()),
                    saving = false,
                    onSave = {},
                    onEdit = {},
                    onCancel = {},
                )
            }
        }

        // Os dois botões são nós reais: o texto diz qual é qual, e o slot é o lugar onde o M3
        // desenha o par.
        val salvar = compose.onNodeWithText("Salvar")
        val cancelar = compose.onNodeWithText("Cancelar")
        salvar.assertIsDisplayed()
        cancelar.assertIsDisplayed()

        assertThat(salvar.getUnclippedBoundsInRoot().top)
            .isLessThan(cancelar.getUnclippedBoundsInRoot().top)
    }

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

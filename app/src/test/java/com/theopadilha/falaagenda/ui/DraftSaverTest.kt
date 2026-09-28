package com.theopadilha.falaagenda.ui

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * O `DraftSaver` é o que faz um recado em andamento atravessar a recriação da Activity —
 * na tela de confirmação e na caixa "Pode salvar?" da home. Sem ele, girar o aparelho com
 * a caixa aberta apagava a fala já reconhecida e parseada, sem erro nenhum.
 */
class DraftSaverTest {

    private val scope = SaverScope { true }

    /** O que o Bundle guarda: é isto que volta depois do giro. */
    private fun save(draft: ParsedTaskDraft?) = with(DraftSaver) { scope.save(draft) }

    @Test
    fun rascunhoInteiroVoltaIgualDepoisDoGiro() {
        val draft = ParsedTaskDraft(
            title = "tomar remédio",
            localDate = LocalDate.of(2026, 9, 28),
            localTime = LocalTime.of(8, 30),
            recurrence = RecurrenceRule(
                kind = RecurrenceKind.WEEKLY,
                weekDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                dayOfMonth = 3,
                monthOfYear = 11,
            ),
            confidence = 0.85,
            missingFields = setOf(MissingDraftField.TIME),
            ambiguous = true,
            transcript = "tomar remédio amanhã às oito e meia",
            notes = listOf("o horário ficou ambíguo"),
            source = DraftSource.AI,
            amountCents = 4590L,
            observation = "em jejum",
        )

        assertThat(DraftSaver.restore(save(draft)!!)).isEqualTo(draft)
    }

    @Test
    fun rascunhoSemDataNemHoraSobrevive() {
        val draft = ParsedTaskDraft(
            title = "comprar pão",
            localDate = null,
            localTime = null,
            recurrence = RecurrenceRule(),
            confidence = 0.4,
            missingFields = setOf(MissingDraftField.DATE, MissingDraftField.TIME),
            ambiguous = false,
            transcript = "",
            source = DraftSource.MANUAL,
            amountCents = null,
            observation = "",
        )

        assertThat(DraftSaver.restore(save(draft)!!)).isEqualTo(draft)
    }

    @Test
    fun semRascunhoNaoSeRestauraNada() {
        // Lista vazia é "nenhum rascunho em andamento": o Bundle não pode fazer a caixa
        // "Pode salvar?" abrir sozinha depois de um giro sem recado nenhum.
        val saved = save(null)

        assertThat(saved).isEmpty()
        assertThat(DraftSaver.restore(saved!!)).isNull()
    }

    @Test
    fun bundleDeVersaoAntigaNaoDerrubaAAbertura() {
        // O Bundle salvo por uma versão antiga do app tem menos campos que o `draftFrom`
        // espera: restaurar isso não pode estourar na criação da tela.
        val antigo = arrayListOf<Any?>("tomar remédio", 1L, 2)

        assertThat(DraftSaver.restore(antigo)).isNull()
    }
}

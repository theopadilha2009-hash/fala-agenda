package com.theopadilha.falaagenda.ui.capture

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * A tela de confirmação — a composição de verdade, não a função que ela chama — prometendo o
 * dia que o alarme vai tocar.
 *
 * Este é o sítio do defeito do #33: o resumo e o botão liam a **data do seletor** enquanto o
 * salvar usa a **regra**, então com "dias úteis" e uma data de sábado a tela dizia "Vai avisar
 * Sábado, 3 de outubro de 2026 às 09:00. Dias úteis." e o alarme nascia na segunda. A frase se
 * contradizia sozinha, e a data tocada sumia sem explicação.
 *
 * O teste que o #33 trouxe (`PromessaDaTelaBateComOAgendamentoTest`) amarra a função pura
 * contra a ocorrência gravada; ele cobre `AgendaFormat.promiseOfChoice`, não os nós que a
 * pessoa lê. Reverter as linhas da tela deixava a suíte verde — por isso o que se prende aqui
 * são os textos renderizados: o resumo, a linha que explica a data descartada e o rótulo do
 * botão.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class ConfirmDraftScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ptBr = Locale.forLanguageTag("pt-BR")

    private val hoje = LocalDate.now()

    /** O próximo sábado: a "dias úteis" a regra não tem sábado — a data é sempre descartada. */
    private val sabado = hoje.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

    /** A primeira segunda depois dele: onde o aviso realmente nasce. */
    private val segunda = sabado.with(TemporalAdjusters.next(DayOfWeek.MONDAY))

    private val hora = LocalTime.of(9, 0)

    /**
     * A frase do resumo é a da data que vale, e igualdade exata: um texto que citasse as duas
     * datas — a prometida e a descartada — passaria num `contains`. Com o defeito de volta, o
     * texto obtido é o da data do seletor, e a mensagem de falha mostra os dois lado a lado.
     */
    @Test
    fun oResumoPrometeODiaQueOVAIAvisarEnaoODoSeletor() {
        val regra = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        tela(rascunho("Cabelo", sabado, hora, regra))

        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextEquals(AgendaFormat.recap(segunda, hora, regra))
    }

    /**
     * A data descartada não some em silêncio: sem esta linha ela não entende por que o primeiro
     * aviso é noutro dia — nem tem como corrigir o sábado que tocou.
     */
    @Test
    fun aLinhaDaDataDescartadaDizQualFoiEDeQuandoEPrimeiroAviso() {
        val regra = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        tela(rascunho("Cabelo", sabado, hora, regra))

        val explicacao = compose.onNodeWithText("não cai nesse dia", substring = true)
        explicacao.assertTextContains(AgendaFormat.dateLabel(sabado, hoje).lowercase(ptBr), substring = true)
        explicacao.assertTextContains(AgendaFormat.longDate(segunda), substring = true)
    }

    /**
     * O botão faz a mesma promessa do resumo: o #33 mudou os dois juntos, e antes ele prometia
     * o dia do seletor (o sábado descartado) com o alarme nascendo na segunda.
     */
    @Test
    fun oBotaoSalvarPrometeOMesmoDiaQueOResumo() {
        val regra = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        tela(rascunho("Cabelo", sabado, hora, regra))

        compose.onNodeWithText("Salvar ·", substring = true)
            .assertTextContains(
                "Salvar · ${AgendaFormat.dateLabel(segunda, hoje).lowercase(ptBr)} ${AgendaFormat.time(hora)}",
            )
    }

    /**
     * O outro lado da linha: quando a data escolhida é a que vale não há o que explicar. Sem
     * este caso, a linha poderia aparecer sempre e os testes de cima passariam por acidente.
     */
    @Test
    fun semDataDescartadaNaoHaOLinhaDaExplicacao() {
        val regra = recurrenceFor(RecurrenceKind.WEEKLY, hoje, setOf(hoje.dayOfWeek))
        tela(rascunho("Natação", hoje, hora, regra))

        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextContains(AgendaFormat.longDate(hoje), substring = true)
        compose.onNodeWithText("não cai nesse dia", substring = true).assertDoesNotExist()
    }

    private fun tela(draft: ParsedTaskDraft) {
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                ConfirmDraftScreen(initial = draft, onCancel = {}, onSave = {})
            }
        }
    }

    private fun rascunho(
        titulo: String,
        data: LocalDate,
        hora: LocalTime,
        regra: RecurrenceRule,
    ) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = hora,
        recurrence = regra,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = "",
    )
}

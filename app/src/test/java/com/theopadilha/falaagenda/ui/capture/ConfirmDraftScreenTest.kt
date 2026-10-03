package com.theopadilha.falaagenda.ui.capture

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    /**
     * O sábado da **semana que vem**: "dias úteis" não tem sábado, então a data é sempre
     * descartada. Não é o sábado mais próximo de propósito — a mais de uma semana, o
     * `dateLabel` cai sempre no ramo `dd/MM`, e uma corrida que atravessa a meia-noite não
     * troca "amanhã" por "hoje" no meio do caminho (com sábado = amanhã, a instância criada
     * antes das 00:00 e a tela lida depois discordariam por relógio, não por defeito).
     */
    private val sabado = hoje.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).plusWeeks(1)

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
     *
     * A data escolhida é a da **semana que vem**, não hoje. Com hoje + 09:00 o caso dependeria
     * do relógio de parede: depois das 09:00 o primeiro aviso vira o sábado seguinte — não por
     * defeito, mas justamente pelo que o #36 consertou (`DraftSchedule.firstOccurrence`: hora
     * vencida numa regra que repete avança, e a tela explica) — e o teste daria vermelho na CI
     * da tarde e verde na da manhã. Sete dias à frente o instante é futuro a qualquer hora do
     * dia, e a invariante que este caso protege — sem explicação quando a escolha vale — continua
     * sendo a mesma exercitada.
     */
    @Test
    fun semDataDescartadaNaoHaOLinhaDaExplicacao() {
        val escolhida = hoje.plusWeeks(1)
        val regra = recurrenceFor(RecurrenceKind.WEEKLY, escolhida, setOf(escolhida.dayOfWeek))
        tela(rascunho("Natação", escolhida, hora, regra))

        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextContains(AgendaFormat.longDate(escolhida), substring = true)
        compose.onNodeWithText("não cai nesse dia", substring = true).assertDoesNotExist()
        // A linha tem dois motivos agora: a regra que não cai naquele dia e a hora de hoje que
        // já passou. Anular só o primeiro deixaria a segunda passar por baixo deste teste.
        compose.onNodeWithText("esse horário já passou", substring = true).assertDoesNotExist()
    }

    /**
     * Os quatro testes de cima montam o rascunho **já resolvido** e prendem a renderização
     * inicial. Isso deixa um mutante vivo: trocar a fonte da promessa de `previewRule` (o
     * estado ao vivo dos chips, `ConfirmDraftScreen.kt:286`) para `initial.recurrence` — a
     * regra como veio no rascunho — passa os quatro verdes, porque no primeiro frame os dois
     * são a mesma coisa. É o defeito do #33 de volta, só que mudando **de qual das três
     * entradas** o resumo lê: a tela descreveria o sábado do rascunho enquanto o `onSave`
     * grava `recurrenceFor(kind, …)` com o chip que ela tocou.
     *
     * Este caso começa em "Só uma vez" e **toca** "Dias úteis": aí as duas fontes divergem, e
     * a promessa tem que acompanhar o toque.
     *
     * O toque vai com `performScrollTo()` porque sem rolar o hit-teste da viewport do
     * Robolectric não alcança o chip e `performClick()` não faz nada — em silêncio, sem
     * exceção. E vem seguido de `assertIsSelected()` exatamente por isso: um clique morto
     * deixaria este teste verde na direção oposta à que ele existe para prender. Ninguém no
     * repositório tinha exercitado toque nesse harness antes (`grep performClick` em
     * `app/src/test`: zero ocorrências).
     */
    @Test
    fun tocarDiasUteisDepoisMoveAPromessaComOToque() {
        val unica = recurrenceFor(RecurrenceKind.NONE, sabado, emptySet())
        tela(rascunho("Cabelo", sabado, hora, unica))

        // O ponto de partida: com "Só uma vez" a data escolhida vale, e não há explicação de
        // data descartada — é o estado que o mutante preservaria.
        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextContains(AgendaFormat.longDate(sabado), substring = true)

        compose.onNodeWithText("Dias úteis").performScrollTo().performClick().assertIsSelected()

        val diasUteis = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextEquals(AgendaFormat.recap(segunda, hora, diasUteis))
        compose.onNodeWithText("Salvar ·", substring = true).assertTextContains(
            "Salvar · ${AgendaFormat.dateLabel(segunda, hoje).lowercase(ptBr)} ${AgendaFormat.time(hora)}",
            substring = true,
        )
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

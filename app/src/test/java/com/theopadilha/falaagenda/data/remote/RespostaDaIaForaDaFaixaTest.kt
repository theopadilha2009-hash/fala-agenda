package com.theopadilha.falaagenda.data.remote

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.reminder.DraftSchedule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A fronteira onde o dado da IA entra no app.
 *
 * O defeito medido: o schema do LLM (`supabase/functions/_shared/openai.ts`) declara
 * `day_of_month` e `month_of_year` como `integer` **sem `minimum`/`maximum`**, e o modelo devolve
 * `month_of_year = 13` — troca de base ou alucinação, tanto faz. O `toDraft()` convertia sem
 * olhar a faixa, a regra virava `YEARLY monthOfYear = 13`, a caixa de confirmação rápida chamava
 * `DraftSchedule.firstOccurrence` **na composição** e o `YearMonth.of(2026, 13)` derrubava a home:
 * sem error boundary, e num caminho em que a tela de confirmação nem aparece (`canQuickConfirm`
 * não olha a recorrência).
 *
 * O teste roda o `ParseReminderClient` de verdade contra um servidor local — não testa a função
 * de validação isolada, testa o caminho que a produção percorre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class RespostaDaIaForaDaFaixaTest {
    private lateinit var server: MockWebServer

    @Before
    fun subirServidor() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun derrubarServidor() {
        server.shutdown()
    }

    private fun cliente() = ParseReminderClient(
        config = SupabaseConfig(url = server.url("/").toString(), anonKey = "k"),
        tokenProvider = { "t" },
        http = OkHttpClient(),
    )

    private fun respostaCom(
        mes: String,
        dia: String,
        localDate: String = "\"2026-10-25\"",
        localTime: String = "\"10:00\"",
        kind: String = "YEARLY",
    ): String = """
        {
          "title": "Compromisso",
          "local_date": $localDate,
          "local_time": $localTime,
          "recurrence": {
            "kind": "$kind",
            "week_days": [],
            "day_of_month": $dia,
            "month_of_year": $mes
          },
          "confidence": 0.9,
          "ambiguous": false,
          "missing_fields": [],
          "notes": []
        }
    """.trimIndent()

    private fun parseDoServidor(
        mes: String,
        dia: String,
        localDate: String = "\"2026-10-25\"",
        localTime: String = "\"10:00\"",
        kind: String = "YEARLY",
    ) = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(respostaCom(mes, dia, localDate, localTime, kind))
                .setHeader("Content-Type", "application/json"),
        )
        cliente().parse(
            transcript = "todo dia 5 de maio",
            nowIso = "2026-10-06T13:00:00Z",
            timezone = "America/Sao_Paulo",
            locale = "pt-BR",
        )
    }

    @Test
    fun mesForaDaFaixaEhDescartadoEAvisa() {
        val draft = parseDoServidor(mes = "13", dia = "5")

        assertThat(draft.recurrence.monthOfYear).isNull()
        assertThat(draft.notes.joinToString()).contains("fora do calendário")
    }

    @Test
    fun diaForaDaFaixaEhDescartadoEAvisa() {
        val draft = parseDoServidor(mes = "5", dia = "32")

        assertThat(draft.recurrence.dayOfMonth).isNull()
        assertThat(draft.notes.joinToString()).contains("fora do calendário")
    }

    /**
     * A regra anual sem um dos dois campos não é uma recorrência — é metade de uma, e o texto que
     * ela produz na tela é `"Todo 5 de ?"` (ver `RecurrenceRule.describePtBr`). Descartar o mês
     * sem mexer no `kind` trocava a queda da home por um texto quebrado na tela dela.
     *
     * A asserção é a **ausência** do `?` no texto real que a tela mostra — o resumo da caixa
     * rápida passa por `describePtBr()` (`AgendaFormat.promiseOfChoice`), não por uma cópia.
     */
    @Test
    fun anualSemMesRebaixaParaNONEESomeOTextoQuebrado() {
        val draft = parseDoServidor(mes = "13", dia = "5")

        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(draft.recurrence.describePtBr()).doesNotContain("?")
        assertThat(draft.recurrence.describePtBr()).isEqualTo("Única")
    }

    /** O espelho: `day_of_month` inválido com mês válido virava `"Todo ? de maio"`. */
    @Test
    fun anualSemDiaRebaixaParaNONEESomeOTextoQuebrado() {
        val draft = parseDoServidor(mes = "5", dia = "32")

        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(draft.recurrence.describePtBr()).doesNotContain("?")
        assertThat(draft.recurrence.describePtBr()).isEqualTo("Única")
    }

    /** Mês `0`, negativo e `99` também caem, e também não podem sobrar como texto quebrado. */
    @Test
    fun mesForaDaFaixaEmQualquerBordaRebaixaParaNONE() {
        listOf("0", "-1", "99").forEach { mes ->
            val draft = parseDoServidor(mes = mes, dia = "5")

            assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
            assertThat(draft.recurrence.describePtBr()).doesNotContain("?")
        }
    }

    /**
     * O caso que derrubava a home, ponta a ponta: o rascunho que sai do cliente não pode
     * estourar quando a caixa de confirmação rápida calcula a primeira ocorrência na composição.
     *
     * `FirstOccurrence.date` é `LocalDate` **não-nulo** — asserir `isNotNull()` passava com
     * qualquer regra, inclusive a que inventava `2027-10-05`. O que o teste promete é que a faixa
     * inválida não inventa dia nenhum: sem mês utilizável a regra anual deixa de existir
     * (`kind = NONE`) e a data escolhida é a que vale.
     */
    @Test
    fun oRascunhoDaFaixaInvalidaNaoDerrubaOCalculoDaPrimeiraOcorrencia() {
        val draft = parseDoServidor(mes = "13", dia = "5")

        val first = DraftSchedule.firstOccurrence(
            rule = draft.recurrence,
            chosenDate = LocalDate.of(2026, 10, 25),
            chosenTime = LocalTime.of(10, 0),
            zoneId = ZoneId.of("America/Sao_Paulo"),
            now = java.time.Instant.parse("2026-10-06T13:00:00Z"),
        )

        assertThat(first.date).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(first.movedBecause).isNull()
    }

    /** Meses `0` e negativos também vêm do modelo, e também têm de cair. */
    @Test
    fun mesZeroENegativoTambemCaem() {
        assertThat(parseDoServidor(mes = "0", dia = "5").recurrence.monthOfYear).isNull()
        assertThat(parseDoServidor(mes = "-1", dia = "5").recurrence.monthOfYear).isNull()
    }

    /** A faixa válida não pode ter sido afetada: 1..12 e 1..31 continuam passando inteiros. */
    @Test
    fun faixaValidaPassaIntacta() {
        val janeiro = parseDoServidor(mes = "1", dia = "1")
        assertThat(janeiro.recurrence.monthOfYear).isEqualTo(1)
        assertThat(janeiro.recurrence.dayOfMonth).isEqualTo(1)
        // F-3: `doesNotContain` sobre lista vazia passa trivialmente — e "não avisou nada" era
        // exatamente o defeito. A asserção é a lista vazia, não a ausência de uma frase nela.
        assertThat(janeiro.notes).isEmpty()

        val dezembro = parseDoServidor(mes = "12", dia = "31")
        assertThat(dezembro.recurrence.monthOfYear).isEqualTo(12)
        assertThat(dezembro.recurrence.dayOfMonth).isEqualTo(31)
        assertThat(dezembro.recurrence.kind).isEqualTo(RecurrenceKind.YEARLY)
        assertThat(dezembro.notes).isEmpty()
    }

    /**
     * F-1, o achado que reprovou o PR: o `kindCoerente` rebaixava o `YEARLY` sem mês/dia e **não
     * cobria o `MONTHLY`**.
     *
     * `{"kind":"MONTHLY","day_of_month":null}` é **conformante ao schema**
     * (`supabase/functions/_shared/openai.ts`: `type: ["integer","null"]`, e `minimum`/`maximum`
     * não mordem em `null`) — não é preciso o modelo alucinar. O `describePtBr()` renderizava
     * `"Todo dia ? do mês"`, o `faixaDescartada` ficava falso (`null != null` é `false`, então nem
     * nota aparecia) e a caixa "Pode salvar?" mostrava o texto quebrado com `canQuickConfirm = true`
     * — o caminho do salvamento com um toque, com o texto errado já à vista.
     */
    @Test
    fun mensalSemDiaRebaixaParaNONEESomeOTextoQuebrado() {
        val draft = parseDoServidor(mes = "null", dia = "null", kind = "MONTHLY")

        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(draft.recurrence.describePtBr()).isEqualTo("Única")
        assertThat(draft.recurrence.describePtBr()).doesNotContain("?")
        assertThat(draft.notes.joinToString()).contains("não disse o dia")
    }

    /**
     * O `MONTHLY` sem dia **salva em silêncio** — é o agravante que fez o achado ser P2 e não P3.
     * A asserção prende o mecanismo: `isComplete` e `canQuickConfirm` verdadeiros sobre uma regra
     * que teria ido para a tela como `"Todo dia ? do mês"`.
     */
    @Test
    fun oMensalSemDiaEraOCaminhoDoSalvamentoSilencioso() {
        val draft = parseDoServidor(mes = "null", dia = "null", kind = "MONTHLY")
        val agora = java.time.Instant.parse("2026-10-06T13:00:00Z")

        assertThat(draft.isComplete).isTrue()
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(agora, ZoneId.of("America/Sao_Paulo"))).isTrue()
    }

    /** O mensal com o dia presente continua mensal, e sem nota nova. */
    @Test
    fun mensalComDiaContinuaMensalSemNota() {
        val draft = parseDoServidor(mes = "null", dia = "15", kind = "MONTHLY")

        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(draft.recurrence.describePtBr()).isEqualTo("Todo dia 15 do mês")
        assertThat(draft.notes).isEmpty()
    }

    /**
     * O campo fora da faixa **já tem** nota ("fora do calendário"), e a nota nova não pode vir
     * junto: duas frases para o mesmo descarte.
     */
    @Test
    fun mensalComDiaForaDaFaixaUsaSoANotaDeFaixa() {
        val draft = parseDoServidor(mes = "null", dia = "32", kind = "MONTHLY")

        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(draft.notes.joinToString()).contains("fora do calendário")
        assertThat(draft.notes.joinToString()).doesNotContain("não disse o dia")
    }

    /** O mesmo caminho do `YEARLY`: faltando o mês, a nota nova aparece e a de faixa não. */
    @Test
    fun anualSemMesTambemDeixaANotaDaRecorrenciaIncompleta() {
        val draft = parseDoServidor(mes = "null", dia = "5")

        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(draft.notes.joinToString()).contains("não disse a data")
        assertThat(draft.notes.joinToString()).doesNotContain("fora do calendário")
    }

    /**
     * F3: a faixa é válida, mas o dia não existe **naquele mês** — "todo dia 30 de fevereiro".
     *
     * Aqui o `clampToValidDate` rola em silêncio para 28/02, e o aviso é o que faltava. O dia
     * continua na regra (não é faixa inválida), mas ela precisa saber que a data foi mexida.
     */
    @Test
    fun diaQueNaoExisteNoMesViraNota() {
        val draft = parseDoServidor(mes = "2", dia = "30")

        assertThat(draft.recurrence.monthOfYear).isEqualTo(2)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(30)
        assertThat(draft.notes.joinToString()).contains("fevereiro")
        assertThat(draft.notes.joinToString()).contains("30")
    }

    /** O 29 de fevereiro existe (no bissexto) e não pode virar nota. */
    @Test
    fun dia29DeFevereiroNaoViraNota() {
        val draft = parseDoServidor(mes = "2", dia = "29")
        assertThat(draft.notes.joinToString()).doesNotContain("fevereiro")
    }

    /**
     * A regra mensal de dia 31 é o clamp **documentado e intencional** ("Ajusta dia 29/30/31 ao
     * último dia válido do mês"), não uma data impossível: 31 existe em sete meses. Nota aqui
     * seria ruído vermelho em cima de um pedido legítimo.
     */
    @Test
    fun mensalDia31NaoViraNota() {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "title": "Conta",
                  "local_date": "2026-10-25",
                  "local_time": "10:00",
                  "recurrence": {
                    "kind": "MONTHLY",
                    "week_days": [],
                    "day_of_month": 31,
                    "month_of_year": null
                  },
                  "confidence": 0.9,
                  "ambiguous": false,
                  "missing_fields": [],
                  "notes": []
                }
                """.trimIndent(),
            ).setHeader("Content-Type", "application/json"),
        )
        val draft = runBlocking {
            cliente().parse(
                transcript = "todo dia 31",
                nowIso = "2026-10-06T13:00:00Z",
                timezone = "America/Sao_Paulo",
                locale = "pt-BR",
            )
        }
        assertThat(draft.notes).isEmpty()
    }

    /**
     * O campo ao lado: `local_date`/`local_time` são o mesmo dado não confiável, e eram o mesmo
     * padrão de conversão sem rede — `LocalDate.parse("2026-02-30")` e `LocalTime.parse("25:00")`
     * estouram `DateTimeParseException`.
     *
     * Hoje quem engole é o `catch` do `HybridParser`, que devolve o rascunho local inteiro e
     * joga fora a data e a hora que a IA tinha acertado — a nota do descarte, junto. O caminho
     * certo é o mesmo do campo de recorrência: o valor inválido vira ausente e a nota aparece.
     */
    @Test
    fun dataQueNaoDaParaLerViraAusenteEAvisa() {
        listOf("2026-02-30", "21/08/2026", "lixo").forEach { data ->
            val draft = parseDoServidor(mes = "5", dia = "5", localDate = "\"$data\"")

            assertThat(draft.localDate).isNull()
            assertThat(draft.notes).containsExactly(
                "A ajuda extra devolveu uma data que não deu para entender. Ficou sem essa parte.",
            )
        }
    }

    /** O horário inválido é o mesmo defeito: `25:00` e `9h` não são `LocalTime`. */
    @Test
    fun horaQueNaoDaParaLerViraAusenteEAvisa() {
        listOf("25:00", "9h", "lixo").forEach { hora ->
            val draft = parseDoServidor(mes = "5", dia = "5", localTime = "\"$hora\"")

            assertThat(draft.localTime).isNull()
            assertThat(draft.notes).containsExactly(
                "A ajuda extra devolveu um horário que não deu para entender. Ficou sem essa parte.",
            )
        }
    }

    /** A data e a hora válidas seguem intactas: o fix é rede, não troca de comportamento. */
    @Test
    fun dataEHoraValidasPassamIntactas() {
        val draft = parseDoServidor(mes = "5", dia = "5")

        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(draft.notes).isEmpty()
    }

    /** `null` é o "não foi dito" legítimo da IA e não pode virar nota. */
    @Test
    fun dataEHoraNulasNaoViramNota() {
        val draft = parseDoServidor(mes = "5", dia = "5", localDate = "null", localTime = "null")

        assertThat(draft.localDate).isNull()
        assertThat(draft.localTime).isNull()
        assertThat(draft.notes).isEmpty()
    }
}

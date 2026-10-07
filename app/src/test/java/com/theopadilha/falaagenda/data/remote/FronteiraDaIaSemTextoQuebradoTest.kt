package com.theopadilha.falaagenda.data.remote

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.DraftSchedule
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * O oráculo de fronteira da IA: **nenhum `"?"` pode chegar à tela dela**, venha de onde vier.
 *
 * O defeito medido: `kindCoerente` (`SupabaseFunctions.kt`) rebaixava o `YEARLY` sem mês/dia para
 * `NONE`, mas não cobria o `MONTHLY`. E o `MONTHLY` **não precisa de alucinação** para chegar
 * quebrado — `{"kind":"MONTHLY","day_of_month":null}` é **conformante ao schema**
 * (`supabase/functions/_shared/openai.ts`: `type: ["integer","null"]`, e `minimum`/`maximum` não
 * mordem em `null`). O `describePtBr()` então renderiza `"Todo dia ? do mês"`, o
 * `faixaDescartada` fica falso (`null != null` é `false`, então nem nota aparece) e a caixa
 * "Pode salvar?" mostra o texto quebrado com `canQuickConfirm = true` — o caminho do salvamento
 * com um toque, com o texto errado já à vista.
 *
 * Aqui a resposta entra pelo **caminho de produção** — `Json.decodeFromString(ParseResponse...)`
 * → `toDraft(...)` — e não por uma `RecurrenceRule` montada na mão: um teste que constrói a regra
 * não prova que a resposta da IA chega assim. São 6 `kind` × 10 dias × 9 meses × 6 datas × 5 horas
 * = 16200 combinações, e o que se **conta** é quantas renderizam `"?"`.
 *
 * O invariante é independente: a asserção é a ausência literal do caractere no texto que a tela
 * mostra, e não uma segunda cópia da regra de coerência do código.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class FronteiraDaIaSemTextoQuebradoTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val chosenDate = LocalDate.of(2026, 10, 25)
    private val chosenTime = LocalTime.of(10, 0)
    private val now = Instant.parse("2026-10-06T13:00:00Z")

    private val kinds = listOf("NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "YEARLY")
    private val dias = listOf("null", "0", "1", "5", "15", "29", "30", "31", "32", "-1")
    private val meses = listOf("null", "0", "1", "2", "5", "8", "12", "13", "-1")
    private val datas = listOf("\"2026-10-25\"", "\"2026-02-30\"", "\"21/08/2026\"", "\"lixo\"", "\"2026-12-31\"", "null")
    private val horas = listOf("\"10:00\"", "\"25:00\"", "\"9h\"", "\"00:00\"", "null")

    private fun corpo(kind: String, mes: String, dia: String, data: String, hora: String): String = """
        {
          "title": "Compromisso",
          "local_date": $data,
          "local_time": $hora,
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

    /** Quantos `"?"` o texto renderizado carrega — o invariante literal, sem cópia da regra. */
    private fun interrogacoes(texto: String): Int = texto.count { it == '?' }

    private fun inteiroOuNulo(no: JsonObject, chave: String): Int? {
        val valor = no[chave] ?: JsonNull
        return (valor as? JsonPrimitive)?.intOrNull
    }

    @Test
    fun nenhumaRespostaDaIaRenderizaInterrogacaoNaTela() {
        var combinacoes = 0
        val textosQuebrados = mutableListOf<String>()
        val estouros = mutableListOf<String>()
        val descarteSemNota = mutableListOf<String>()

        for (kind in kinds) {
            for (dia in dias) {
                for (mes in meses) {
                    for (data in datas) {
                        for (hora in horas) {
                            combinacoes++
                            val label = "$kind dia=$dia mes=$mes data=$data hora=$hora"
                            val cru = corpo(kind, mes, dia, data, hora)
                            val recorrenciaCrua = json.parseToJsonElement(cru)
                                .jsonObject["recurrence"]!!.jsonObject
                            val diaCru = inteiroOuNulo(recorrenciaCrua, "day_of_month")
                            val mesCru = inteiroOuNulo(recorrenciaCrua, "month_of_year")

                            val draft = try {
                                json.decodeFromString(ParseResponse.serializer(), cru)
                                    .toDraft(transcript = "fala da usuária")
                            } catch (t: Throwable) {
                                estouros += "$label toDraft estourou ${t::class.simpleName}: ${t.message}"
                                continue
                            }

                            // O texto que a tela mostra. O recap é o da caixa de confirmação
                            // rápida (`AgendaFormat.promiseOfChoice` → `AgendaFormat.recap`), que é
                            // o caminho do salvamento com um toque; sem data e hora a tela mostra
                            // "Falta a data" e não há resumo a renderizar.
                            val descricao = draft.recurrence.describePtBr()
                            val recap = if (draft.localDate != null && draft.localTime != null) {
                                AgendaFormat.recap(draft.localDate!!, draft.localTime!!, draft.recurrence)
                            } else {
                                ""
                            }
                            val renderizado = "$descricao\n$recap"
                            if (interrogacoes(renderizado) > 0) {
                                textosQuebrados += "$label → '${descricao.trim()}'"
                            }

                            // O campo que a IA devolveu fora da faixa tem de virar ausente **com**
                            // a nota que explica. `null` é o "não foi dito" legítimo e não pede nota.
                            val notaPresente = draft.notes.any { it.contains("fora do calendário") }
                            val diaDescartado = diaCru != null && diaCru !in 1..31 && draft.recurrence.dayOfMonth == null
                            val mesDescartado = mesCru != null && mesCru !in 1..12 && draft.recurrence.monthOfYear == null
                            if ((diaDescartado || mesDescartado) && !notaPresente) {
                                descarteSemNota += "$label descartou sem nota"
                            }

                            try {
                                DraftSchedule.firstOccurrence(
                                    rule = draft.recurrence,
                                    chosenDate = chosenDate,
                                    chosenTime = chosenTime,
                                    zoneId = zone,
                                    now = now,
                                )
                            } catch (t: Throwable) {
                                estouros += "$label firstOccurrence estourou ${t::class.simpleName}: ${t.message}"
                            }
                        }
                    }
                }
            }
        }

        println(
            "FRONTEIRA_IA combinacoes=$combinacoes textosQuebrados=${textosQuebrados.size} " +
                "estouros=${estouros.size} descarteSemNota=${descarteSemNota.size}",
        )
        textosQuebrados.take(5).forEach { println("FRONTEIRA_IA exemploQuebrado: $it") }

        assertThat(combinacoes).isEqualTo(16200)
        assertThat(textosQuebrados).isEmpty()
        assertThat(estouros).isEmpty()
        assertThat(descarteSemNota).isEmpty()
    }

    /**
     * O guard sozinho, **sem passar pela fronteira**: a mesma pergunta do oráculo, mas com a regra
     * incoerente injetada direto no `describePtBr`. É o que prova que o conserto fechou a **classe**
     * e não só o caso do `MONTHLY` — um `kind` futuro, um caminho novo do parser ou um dado que já
     * esteja gravado no banco chegam aqui, e nenhum deles pode renderizar `"?"`.
     */
    @Test
    fun oGuardFechaAClasseParaQualquerRegraIncoerente() {
        val incoerentes = buildList {
            add("MONTHLY sem dia" to RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = null))
            listOf(0, -1, 32, 99).forEach {
                add("MONTHLY dia=$it" to RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = it))
            }
            add("YEARLY sem nada" to RecurrenceRule(RecurrenceKind.YEARLY))
            add(
                "YEARLY sem mês" to RecurrenceRule(RecurrenceKind.YEARLY, dayOfMonth = 5),
            )
            add(
                "YEARLY sem dia" to RecurrenceRule(RecurrenceKind.YEARLY, monthOfYear = 5),
            )
            listOf(0, -1, 13, 99).forEach {
                add(
                    "YEARLY mês=$it" to RecurrenceRule(
                        RecurrenceKind.YEARLY,
                        dayOfMonth = 5,
                        monthOfYear = it,
                    ),
                )
            }
            add(
                "YEARLY dia=32 mês=5" to RecurrenceRule(
                    RecurrenceKind.YEARLY,
                    dayOfMonth = 32,
                    monthOfYear = 5,
                ),
            )
            add("WEEKLY sem dia da semana" to RecurrenceRule(RecurrenceKind.WEEKLY))
        }

        val quebrados = incoerentes.filter { (_, rule) ->
            interrogacoes(rule.describePtBr()) > 0
        }.map { it.first }

        assertThat(quebrados).isEmpty()
        assertThat(incoerentes).isNotEmpty()
        incoerentes.forEach { (_, rule) ->
            assertThat(rule.describePtBr().trim()).isNotEmpty()
        }
    }

    /**
     * A regra **coerente** continua com o texto de sempre — o guard não pode ter trocado o que já
     * funcionava por uma frase genérica.
     */
    @Test
    fun aRegraCoerenteContinuaComOTextoDeSempre() {
        assertThat(RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = 5).describePtBr())
            .isEqualTo("Todo dia 5 do mês")
        assertThat(RecurrenceRule(RecurrenceKind.YEARLY, dayOfMonth = 5, monthOfYear = 5).describePtBr())
            .isEqualTo("Todo 5 de maio")
        assertThat(RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = 31).describePtBr())
            .isEqualTo("Todo dia 31 do mês")
    }
}

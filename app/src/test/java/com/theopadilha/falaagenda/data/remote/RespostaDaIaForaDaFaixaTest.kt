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

    private fun respostaCom(mes: String, dia: String): String = """
        {
          "title": "Compromisso",
          "local_date": "2026-10-25",
          "local_time": "10:00",
          "recurrence": {
            "kind": "YEARLY",
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

    private fun parseDoServidor(mes: String, dia: String) = runBlocking {
        server.enqueue(MockResponse().setBody(respostaCom(mes, dia)).setHeader("Content-Type", "application/json"))
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
     * O caso que derrubava a home, ponta a ponta: o rascunho que sai do cliente não pode
     * estourar quando a caixa de confirmação rápida calcula a primeira ocorrência na composição.
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

        assertThat(first.date).isNotNull()
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
        assertThat(janeiro.notes.joinToString()).doesNotContain("fora do calendário")

        val dezembro = parseDoServidor(mes = "12", dia = "31")
        assertThat(dezembro.recurrence.monthOfYear).isEqualTo(12)
        assertThat(dezembro.recurrence.dayOfMonth).isEqualTo(31)
        assertThat(dezembro.recurrence.kind).isEqualTo(RecurrenceKind.YEARLY)
        assertThat(dezembro.notes.joinToString()).doesNotContain("fora do calendário")
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
}

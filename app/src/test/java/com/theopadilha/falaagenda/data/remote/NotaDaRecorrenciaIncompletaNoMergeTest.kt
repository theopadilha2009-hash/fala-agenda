package com.theopadilha.falaagenda.data.remote

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.parser.HybridParser
import com.theopadilha.falaagenda.domain.parser.LocalTaskParser
import com.theopadilha.falaagenda.domain.parser.NetworkStatus
import com.theopadilha.falaagenda.domain.time.FixedAppClock
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A nota "Ficou sem repetir" atravessa o `HybridParser` — e é aí que ela passa a mentir.
 *
 * O defeito medido pelo review, pelo caminho de produção (`ParseReminderClient` de verdade contra
 * um servidor local → `HybridParser.parse`):
 *
 * ```
 * fala: "todo dia 5 do mês"
 *   local:  MONTHLY dia=5, date=2026-11-05, time=null  → escala (falta hora)
 *   IA:     {"kind":"MONTHLY","day_of_month":null}     → a fronteira rebaixa p/ NONE + nota
 *   final:  kind=MONTHLY dia=5  describe='Todo dia 5 do mês'
 *           notes=[... mas não disse o dia. Ficou sem repetir.]
 *           isComplete=true  canQuickConfirm=true
 * ```
 *
 * A nota nasce na fronteira (`ParseResponse.toDraft`) quando a IA devolve `MONTHLY` sem o dia, e o
 * `toDraft` está **certo**: a regra que ele produz não repete. O que a desfaz é o merge, uma camada
 * acima — `mergeRemote` só aceita a recorrência do remoto quando ela `isRecurring`, então a regra
 * rebaixada a `NONE` é descartada e a do **local** volta. A nota, que veio em `remoteDraft.notes`,
 * sobrevive (`notasDomescladas` só desmente o que casa com um prefixo de [NotasDoRascunho]).
 *
 * Resultado: a regra está certa e a nota mente — na caixa "Pode salvar?", com o texto à vista e
 * `canQuickConfirm=true`, o caminho do salvamento com um toque.
 *
 * O invariante que estes testes prendem é o texto da própria nota, lido de volta do rascunho:
 * **"Ficou sem repetir" só pode aparecer quando a recorrência final realmente não repete.** Os dois
 * lados são contados — a nota falsa e a perda silenciosa sem nota —, porque consertar um só cria o
 * defeito oposto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class NotaDaRecorrenciaIncompletaNoMergeTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 10, 6, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    private lateinit var server: MockWebServer

    /** A frase da nota que afirma a perda — lida do texto, não de uma constante do código. */
    private val afirmacaoDePerda = "Ficou sem repetir"

    @Before
    fun subirServidor() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun derrubarServidor() {
        server.shutdown()
    }

    /**
     * A resposta da IA no formato do schema (`supabase/functions/_shared/openai.ts`), com data e
     * hora resolvidas — é o rascunho completo que chega à caixa rápida.
     */
    private fun respostaJson(
        kind: String,
        dia: String,
        mes: String,
        localDate: String = "\"2026-10-25\"",
        localTime: String = "\"10:00\"",
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

    /** O caminho de produção inteiro: o cliente real contra o servidor local, e o merge por cima. */
    private fun parsePelaProducao(fala: String, json: String): ParsedTaskDraft {
        server.enqueue(
            MockResponse().setBody(json).setHeader("Content-Type", "application/json"),
        )
        val remote = ParseReminderClient(
            config = SupabaseConfig(url = server.url("/").toString(), anonKey = "k"),
            tokenProvider = { "t" },
            http = OkHttpClient(),
        )
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remote,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        return runBlocking { hybrid.parse(fala) }
    }

    /**
     * O caso que o review mediu: o local reconhece a recorrência e a IA devolve o mesmo `kind` sem
     * o campo. O merge restaura a regra do local — então a nota que fala da perda não pode existir.
     */
    @Test
    fun aNotaNaoFalaDePerdaQuandoOLocalRestauraARecorrencia() {
        val final = parsePelaProducao(
            fala = "todo dia 5 do mês",
            json = respostaJson(kind = "MONTHLY", dia = "null", mes = "null"),
        )

        assertThat(final.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(final.recurrence.describePtBr()).isEqualTo("Todo dia 5 do mês")
        // O caminho do salvamento com um toque, com o resumo e a nota à vista na mesma caixa.
        assertThat(final.isComplete).isTrue()
        assertThat(final.canQuickConfirm(clock.instant(), zone)).isTrue()
        assertWithMessage("a regra repete, então a nota que fala da perda é falsa: ${final.notes}")
            .that(final.notes.any { it.contains(afirmacaoDePerda) })
            .isFalse()
    }

    /** A variante `YEARLY` reproduz igual: a regra incoerente cai e o local a restaura. */
    @Test
    fun aVarianteAnualTambemNaoPodeFalarDePerda() {
        val final = parsePelaProducao(
            fala = "todo dia 5 do mês",
            json = respostaJson(kind = "YEARLY", dia = "5", mes = "null"),
        )

        assertThat(final.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertWithMessage("a regra repete, então a nota que fala da perda é falsa: ${final.notes}")
            .that(final.notes.any { it.contains(afirmacaoDePerda) })
            .isFalse()
    }

    /**
     * O outro lado, que **não** pode regredir: quando o local não reconhece recorrência nenhuma, a
     * regra rebaixada é a única que existe, o final não repete — e aí a nota é verdadeira. Sem ela,
     * a perda seria silenciosa: a caixa mostraria "Única" para uma fala em que ela pediu "todo mês".
     */
    @Test
    fun aNotaContinuaQuandoNaoHaRecorrenciaLocalParaRestaurar() {
        val final = parsePelaProducao(
            fala = "quinze horas",
            json = respostaJson(kind = "MONTHLY", dia = "null", mes = "null"),
        )

        assertThat(final.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(final.notes.any { it.contains(afirmacaoDePerda) }).isTrue()
        assertThat(final.notes.joinToString()).contains("não disse o dia")
    }

    /**
     * O invariante dos **dois lados**, contado em vez de afirmado num exemplo: cruzando falas em que
     * o local reconhece a recorrência com falas em que ele não reconhece, contra as respostas de
     * recorrência que a IA pode devolver.
     *
     * - **nota falsa**: o texto afirma a perda e o final repete.
     * - **perda silenciosa**: a IA disse que repete, o final não repete e não há nota nenhuma.
     *
     * O esperado é calculado pelo **texto da nota** e pelo `kind` final — nenhuma cópia da regra de
     * supressão do código. Se o conserto tapar só um lado, o outro acende aqui.
     */
    @Test
    fun oInvarianteDaNotaValeNosDoisLados() {
        val falasQueOlocalReconhece = listOf(
            "todo dia 5 do mês" to RecurrenceKind.MONTHLY,
            "toda segunda" to RecurrenceKind.WEEKLY,
        )
        val falasSemRecorrenciaLocal = listOf("quinze horas", "no dia 25", "tomar remédio amanhã")

        val respostas = listOf(
            "MONTHLY" to listOf("null" to "null", "15" to "null", "32" to "null"),
            "YEARLY" to listOf("5" to "null", "null" to "null", "5" to "5"),
            "NONE" to listOf("null" to "null"),
            "WEEKLY" to listOf("null" to "null"),
        )

        var casos = 0
        val notasFalsas = mutableListOf<String>()
        val perdasSilenciosas = mutableListOf<String>()

        (falasQueOlocalReconhece.map { it.first } + falasSemRecorrenciaLocal).forEach { fala ->
            for ((kind, variantes) in respostas) {
                for ((dia, mes) in variantes) {
                    casos++
                    val label = "fala='$fala' ia=$kind dia=$dia mes=$mes"
                    val final = parsePelaProducao(fala, respostaJson(kind, dia, mes))
                    val temNotaDePerda = final.notes.any { it.contains(afirmacaoDePerda) }
                    // O descarte da recorrência tem duas notas possíveis: a de incompletude (a IA
                    // não disse o campo) e a de faixa (a IA disse um campo que não existe). Qualquer
                    // uma delas explica a perda; a que não pode existir é a perda **sem** explicação.
                    val temNotaDeDescarte = temNotaDePerda ||
                        final.notes.any { it.contains("fora do calendário") }
                    val repeteNoFinal = final.recurrence.kind != RecurrenceKind.NONE
                    val iaDisseQueRepete = kind != "NONE"

                    if (temNotaDePerda && repeteNoFinal) notasFalsas += "$label → repete ${final.recurrence.kind}"
                    if (!temNotaDeDescarte && iaDisseQueRepete && !repeteNoFinal) {
                        perdasSilenciosas += "$label → final NONE sem nota"
                    }
                }
            }
        }

        println(
            "NOTA_RECORRENCIA_MERGE casos=$casos notasFalsas=${notasFalsas.size} " +
                "perdasSilenciosas=${perdasSilenciosas.size}",
        )
        notasFalsas.take(5).forEach { println("NOTA_RECORRENCIA_MERGE notaFalsa: $it") }
        perdasSilenciosas.take(5).forEach { println("NOTA_RECORRENCIA_MERGE perdaSilenciosa: $it") }

        assertThat(casos).isEqualTo(40)
        assertThat(notasFalsas).isEmpty()
        assertThat(perdasSilenciosas).isEmpty()
    }

    /**
     * A recorrência que a IA traz **coerente** continua vencendo o local, e sem nota: o conserto é
     * para a nota, não para a precedência do merge.
     */
    @Test
    fun aRecorrenciaCoerenteDaIaContinuaVencendoSemNota() {
        val final = parsePelaProducao(
            fala = "todo dia 5 do mês",
            json = respostaJson(kind = "MONTHLY", dia = "15", mes = "null"),
        )

        assertThat(final.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(final.recurrence.dayOfMonth).isEqualTo(15)
        assertThat(final.notes).isEmpty()
    }

    /** E a fala sem recorrência nenhuma, com a IA calada sobre ela, segue sem nota de recorrência. */
    @Test
    fun semRecorrenciaNenhumaNaoHaNota() {
        val final = parsePelaProducao(
            fala = "quinze horas",
            json = respostaJson(kind = "NONE", dia = "null", mes = "null"),
        )

        assertThat(final.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        // O título diverge ("Quinze" do local contra "Compromisso" da IA) e a nota disso é
        // esperada — o que não pode haver aqui é nota de recorrência.
        assertThat(final.notes.any { it.contains("repet") || it.contains(afirmacaoDePerda) }).isFalse()
        assertThat(final.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(final.localDate).isEqualTo(LocalDate.of(2026, 10, 25))
    }
}

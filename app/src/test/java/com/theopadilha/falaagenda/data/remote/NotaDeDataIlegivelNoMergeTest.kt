package com.theopadilha.falaagenda.data.remote

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
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
 * A nota "Ficou sem essa parte" da data/hora atravessa o `HybridParser` — e é aí que ela mente.
 *
 * O defeito medido, pelo caminho de produção (`ParseReminderClient` de verdade contra um servidor
 * local → `HybridParser.parse`):
 *
 * ```
 * ela fala: "reunião 25/10"
 *   local:  data=2026-10-25, hora=null        → escala (falta a hora)
 *   IA:     {"local_date":"2026-02-30", ...}  → a fronteira descarta a data e escreve a nota
 *   final:  data=2026-10-25 (do local), hora=10:00, canQuickConfirm=true
 *           notes=[A ajuda extra devolveu uma data que não deu para entender. Ficou sem essa parte.]
 * ```
 *
 * A caixa "Pode salvar?" mostra a data certa e, logo abaixo em vermelho, "Ficou sem essa parte".
 *
 * É a **mesma classe** que o PR #75 corrigiu para a nota de recorrência: o veredito da nota é o
 * **desfecho final do merge**, não a contribuição intermediária. A nota nasce na fronteira, mas o
 * merge restaura a data do local (`mergedDate = remoteDraft.localDate ?: localDraft.localDate`) e
 * nada a desmente — o `notasDomescladas` só suprime nota **do local** por prefixo de
 * [com.theopadilha.falaagenda.domain.parser.NotasDoRascunho], e a de data/hora vem do remoto.
 *
 * O invariante que estes testes prendem tem **dois lados**, porque consertar um só cria o outro:
 * **há nota de data ilegível ⟺ a data final ficou sem a parte que a IA não soube ler.**
 * Os dois são contados no oráculo — a nota falsa e a perda muda.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class NotaDeDataIlegivelNoMergeTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 10, 6, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    private lateinit var server: MockWebServer

    /**
     * O texto que a nota afirma, lido da frase e **não** de uma constante do código: é a referência
     * independente do oráculo. Se a redação mudar, o teste acusa em vez de acompanhar.
     */
    private val afirmacaoDaData = "devolveu uma data que não deu para entender"
    private val afirmacaoDaHora = "devolveu um horário que não deu para entender"

    @Before
    fun subirServidor() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun derrubarServidor() {
        server.shutdown()
    }

    private fun literal(valor: String?): String = if (valor == null) "null" else "\"$valor\""

    /** A resposta da IA no formato do schema (`supabase/functions/_shared/openai.ts`). */
    private fun respostaJson(dataRaw: String?, horaRaw: String?): String = """
        {
          "title": "",
          "local_date": ${literal(dataRaw)},
          "local_time": ${literal(horaRaw)},
          "recurrence": {
            "kind": "NONE",
            "week_days": [],
            "day_of_month": null,
            "month_of_year": null
          },
          "confidence": 0.9,
          "ambiguous": false,
          "missing_fields": [],
          "notes": []
        }
    """.trimIndent()

    /** O caminho de produção inteiro: o cliente real contra o servidor local, e o merge por cima. */
    private fun parsePelaProducao(fala: String, dataRaw: String?, horaRaw: String?): ParsedTaskDraft {
        server.enqueue(
            MockResponse()
                .setBody(respostaJson(dataRaw, horaRaw))
                .setHeader("Content-Type", "application/json"),
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

    private fun ParsedTaskDraft.temNotaDaData() = notes.any { it.contains(afirmacaoDaData) }
    private fun ParsedTaskDraft.temNotaDaHora() = notes.any { it.contains(afirmacaoDaHora) }

    /**
     * O caso medido: o local crava a data, a IA devolve uma data que não existe, e o merge restaura
     * a data do local. A parte **está** no rascunho final — então a nota não pode dizer que sumiu.
     */
    @Test
    fun aNotaDeDataIlegivelNaoFalaDePerdaQuandoOLocalTemAData() {
        val final = parsePelaProducao(
            fala = "reunião 25/10",
            dataRaw = "2026-02-30",
            horaRaw = "10:00",
        )

        assertThat(final.localDate).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(final.localTime).isEqualTo(LocalTime.of(10, 0))
        // O caminho do salvamento com um toque, com a data à vista na mesma caixa.
        assertThat(final.canQuickConfirm(clock.instant(), zone)).isTrue()
        assertWithMessage("a data final existe, então a nota que fala da perda é falsa: ${final.notes}")
            .that(final.temNotaDaData())
            .isFalse()
    }

    /**
     * O espelho do horário: o local crava a hora, a IA devolve um horário ilegível, e o merge
     * restaura a hora do local. A mesma nota, o mesmo defeito.
     */
    @Test
    fun aNotaDeHoraIlegivelNaoFalaDePerdaQuandoOLocalTemAHora() {
        val final = parsePelaProducao(
            fala = "reunião às 9h",
            dataRaw = "2026-11-01",
            horaRaw = "25:00",
        )

        assertThat(final.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(final.localDate).isEqualTo(LocalDate.of(2026, 11, 1))
        assertThat(final.canQuickConfirm(clock.instant(), zone)).isTrue()
        assertWithMessage("a hora final existe, então a nota que fala da perda é falsa: ${final.notes}")
            .that(final.temNotaDaHora())
            .isFalse()
    }

    /**
     * O outro lado, que **não** pode regredir: o local não tem data, a IA não soube ler a data que
     * tentou devolver, e o final fica sem data — aí a nota é verdadeira e é a única coisa que conta
     * que a ajuda extra errou essa parte.
     */
    @Test
    fun aNotaDeDataIlegivelContinuaQuandoNinguemTemAData() {
        val final = parsePelaProducao(
            fala = "às 9h",
            dataRaw = "2026-02-30",
            horaRaw = "10:00",
        )

        assertThat(final.localDate).isNull()
        assertWithMessage("a data final não existe, então a nota é a única que conta a perda: ${final.notes}")
            .that(final.temNotaDaData())
            .isTrue()
    }

    /** O mesmo para a hora: sem hora final, a nota fica. */
    @Test
    fun aNotaDeHoraIlegivelContinuaQuandoNinguemTemAHora() {
        val final = parsePelaProducao(
            fala = "reunião 25/10",
            dataRaw = "2026-11-01",
            horaRaw = "25:00",
        )

        assertThat(final.localTime).isNull()
        assertWithMessage("a hora final não existe, então a nota é a única que conta a perda: ${final.notes}")
            .that(final.temNotaDaHora())
            .isTrue()
    }

    /**
     * A resposta coerente não pode virar nota nenhuma: o fix é para a nota, não para a leitura. Se
     * a IA trouxe uma data e uma hora que existem, nada foi perdido e nada é avisado.
     */
    @Test
    fun aRespostaCoerenteNaoViraNotaNenhuma() {
        val final = parsePelaProducao(
            fala = "reunião 25/10",
            dataRaw = "2026-11-01",
            horaRaw = "10:00",
        )

        assertThat(final.localDate).isEqualTo(LocalDate.of(2026, 11, 1))
        assertThat(final.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(final.notes).isEmpty()
    }

    /**
     * O invariante dos **dois lados**, contado em vez de afirmado num exemplo: o espaço cartesiano
     * de falas (o local tinha a data/hora ou não) contra as respostas que a IA pode devolver.
     *
     * - **nota falsa**: o texto afirma a perda e o campo final existe.
     * - **perda muda**: a IA mandou um valor ilegível, o final ficou sem o campo, e não há nota.
     *
     * O esperado é calculado do **lado de fora do app**: se o JSON que a IA devolveu trazia um valor
     * não-nulo que não é um `LocalDate`/`LocalTime` (parseado aqui, no teste), e o rascunho final
     * ficou sem aquele campo. Nenhuma cópia da regra de supressão do código — um oráculo que
     * recalcula com a mesma lógica do app não acha nada.
     */
    @Test
    fun oInvarianteDaNotaDeDataEHoraValeNosDoisLados() {
        // O local crava um campo, ou não: a fala decide de qual lado do merge ele chega.
        val falas = listOf(
            "reunião 25/10" to "local tem a data e não tem a hora",
            "às 9h" to "local tem a hora e não tem a data",
            "reunião" to "local não tem nem data nem hora",
        )
        // As respostas que a IA pode devolver: o valor existe e é ilegível, ou não veio.
        val respostas = listOf(
            "data ilegível" to ("2026-02-30" to "10:00"),
            "hora ilegível" to ("2026-11-01" to "25:00"),
            "ambas ilegíveis" to ("2026-02-30" to "25:00"),
            "nenhuma" to (null to null),
            "coerente" to ("2026-11-01" to "10:00"),
        )

        var casos = 0
        val notasFalsas = mutableListOf<String>()
        val perdasMudas = mutableListOf<String>()

        falas.forEach { (fala, descricaoLocal) ->
            respostas.forEach { (descricaoIa, valores) ->
                val (dataRaw, horaRaw) = valores
                casos++
                val label = "fala='$fala' ($descricaoLocal) ia=$descricaoIa"
                val final = parsePelaProducao(fala, dataRaw, horaRaw)

                // A referência independente: o que a IA mandou e o que sobrou no fim.
                val iaMandouDataIlegivel =
                    dataRaw != null && runCatching { LocalDate.parse(dataRaw) }.isFailure
                val iaMandouHoraIlegivel =
                    horaRaw != null && runCatching { LocalTime.parse(horaRaw) }.isFailure
                val esperaNotaDaData = iaMandouDataIlegivel && final.localDate == null
                val esperaNotaDaHora = iaMandouHoraIlegivel && final.localTime == null

                if (final.temNotaDaData() && !esperaNotaDaData) {
                    notasFalsas += "$label → nota de data, mas final tem ${final.localDate}"
                }
                if (final.temNotaDaHora() && !esperaNotaDaHora) {
                    notasFalsas += "$label → nota de hora, mas final tem ${final.localTime}"
                }
                if (!final.temNotaDaData() && esperaNotaDaData) {
                    perdasMudas += "$label → data perdida sem nota (final ${final.localDate})"
                }
                if (!final.temNotaDaHora() && esperaNotaDaHora) {
                    perdasMudas += "$label → hora perdida sem nota (final ${final.localTime})"
                }
            }
        }

        println(
            "NOTA_DATA_HORA_MERGE casos=$casos notasFalsas=${notasFalsas.size} " +
                "perdasMudas=${perdasMudas.size}",
        )
        notasFalsas.forEach { println("NOTA_DATA_HORA_MERGE notaFalsa: $it") }
        perdasMudas.forEach { println("NOTA_DATA_HORA_MERGE perdaMuda: $it") }

        assertThat(casos).isEqualTo(15)
        assertThat(notasFalsas).isEmpty()
        assertThat(perdasMudas).isEmpty()
    }

    /** A resposta com a recorrência também preenchida, para cruzar os dois juízes na mesma frase. */
    private fun respostaComRecorrencia(dataRaw: String?, kind: String, dia: String): String = """
        {
          "title": "",
          "local_date": ${literal(dataRaw)},
          "local_time": "10:00",
          "recurrence": {
            "kind": "$kind",
            "week_days": [],
            "day_of_month": $dia,
            "month_of_year": null
          },
          "confidence": 0.9,
          "ambiguous": false,
          "missing_fields": [],
          "notes": []
        }
    """.trimIndent()

    private fun parseComRecorrencia(fala: String, dataRaw: String?, kind: String, dia: String): ParsedTaskDraft {
        server.enqueue(
            MockResponse()
                .setBody(respostaComRecorrencia(dataRaw, kind, dia))
                .setHeader("Content-Type", "application/json"),
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
     * Os dois juízes no mesmo `filterNot` — o da recorrência (do #75) e o da data/hora (deste PR).
     *
     * A fala `"todo dia 5 do mês"` tem as duas coisas que o merge pode restaurar: a regra `MONTHLY`
     * dia=5 do local **e** a data `2026-11-05` do local. A IA devolve, na mesma resposta, a data
     * ilegível (`2026-02-30` → nota de data) e a regra incoerente (`MONTHLY` sem dia → nota de
     * recorrência). O merge restaura as duas, então as duas notas são falsas e as duas têm de cair.
     *
     * Se o juiz da recorrência engolisse o da data (ou o contrário), uma das duas sobreviveria — e
     * é exatamente essa colisão que este teste prende.
     */
    @Test
    fun osDoisJuizesNaoSeAtropelamNaMesmaResposta() {
        val final = parseComRecorrencia(
            fala = "todo dia 5 do mês",
            dataRaw = "2026-02-30",
            kind = "MONTHLY",
            dia = "null",
        )

        // As duas partes foram restauradas do local: a regra repete e a data existe.
        assertThat(final.recurrence.kind).isEqualTo(com.theopadilha.falaagenda.domain.model.RecurrenceKind.MONTHLY)
        assertThat(final.recurrence.dayOfMonth).isEqualTo(5)
        assertThat(final.localDate).isEqualTo(LocalDate.of(2026, 11, 5))

        assertWithMessage("a regra repete, então a nota de recorrência é falsa: ${final.notes}")
            .that(final.notes.any { it.contains("Ficou sem repetir") })
            .isFalse()
        assertWithMessage("a data existe, então a nota de data é falsa: ${final.notes}")
            .that(final.temNotaDaData())
            .isFalse()
    }
}

package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A ambiguidade que o parser local detectou **sobrevive** à passagem pelo `HybridParser`.
 *
 * Dois revisores independentes, em PRs diferentes e sobre diffs diferentes, acharam o mesmo furo
 * (#105 pelo intervalo em horas, #98 pelos dois dias relativos). A causa é uma linha do
 * `mergeRemote`:
 *
 * ```
 * ambiguous = remoteDraft.ambiguous && missing.isNotEmpty()
 * ```
 *
 * Quando a IA devolve data e hora completas, `missing = []` e a ambiguidade local morre — junto com
 * a nota que o parser havia escrito. `canQuickConfirm` volta a `true` e a caixa verde confirma em
 * **um toque** exatamente o que o parser tinha marcado como duvidoso; a ajuda extra ainda substitui
 * o aviso por um elogio.
 *
 * `missing = []` significa "o remoto **preencheu** tudo", não "o remoto **resolveu** o conflito".
 * São coisas diferentes: o remoto pode acrescentar certeza sobre o que ele resolveu, mas não pode
 * apagar uma ambiguidade local sobre um campo que ele preencheu sem reconhecer o conflito.
 *
 * O critério aqui separa as duas razões de o local escalar:
 *  - **falta** de campo ("Falta o horário", "de manhã" sem hora): o remoto preenchendo, a dúvida
 *    acabou — segue o comportamento antigo, e é o caminho mais comum da escalação;
 *  - **conflito** ("a fala diz mais de um dia", "é um intervalo, não um horário do dia"): o remoto
 *    que devolve data e hora completas não endereçou a contradição — preencheu por cima.
 *
 * O relógio é quinta, 20/08/2026, 10:00 em America/Sao_Paulo.
 */
class HybridParserAmbiguidadeLocalTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    /**
     * O remoto do cenário dos dois reviews: data e hora completas, `ambiguous = false`,
     * `missing = []` e a nota de elogio que substituía o aviso.
     */
    private fun remotoCompleto(
        data: LocalDate = LocalDate.of(2026, 8, 25),
        hora: LocalTime = LocalTime.of(9, 0),
    ) = object : RemoteDraftParser {
        override suspend fun parse(
            transcript: String,
            nowIso: String,
            timezone: String,
            locale: String,
        ): ParsedTaskDraft = ParsedTaskDraft(
            title = "Consulta",
            localDate = data,
            localTime = hora,
            confidence = 0.9,
            missingFields = emptySet(),
            ambiguous = false,
            transcript = transcript,
            notes = listOf("A ajuda extra completou o essencial."),
            source = DraftSource.AI,
        )
    }

    private fun hybrid(remote: RemoteDraftParser) = HybridParser(
        local = local,
        clock = clock,
        remote = remote,
        network = NetworkStatus { true },
        isAiEnabled = { true },
    )

    // --------------------------------------------------------- o caso exato dos dois reviews

    /**
     * O cenário do #98: "hoje e amanhã" é uma fala com DOIS dias e o modelo do rascunho tem UM
     * `localDate`. O local escala e escreve a nota; a IA devolve uma data completa e a ambiguidade
     * era apagada.
     */
    @Test
    fun aAmbiguidadeDeDoisDiasSobreviveAoRemotoQueCompleta() = runBlocking {
        val fala = "hoje e amanhã às 9h"
        val localDraft = local.parse(fala)
        assertThat(localDraft.ambiguous).isTrue()
        assertThat(localDraft.notes.joinToString()).contains(NotasDoRascunho.DATA_AMBIGUA)

        val draft = hybrid(remotoCompleto()).parse(fala)

        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        assertThat(draft.notes.joinToString()).contains(NotasDoRascunho.DATA_AMBIGUA)
        // A nota de "falta" sai (o remoto trouxe a data), a do conflito fica: o rascunho ambíguo
        // não pode ficar mudo sobre o porquê.
        assertThat(draft.notes.joinToString()).doesNotContain(NotasDoRascunho.FALTA_DATA)
    }

    /**
     * O alcance medido pelo #98: sete falas que na base estavam `qc=true` e agora escalam no local.
     * Sem esta correção, todas entram na classe que o merge apagava — a data escolhida pela IA era
     * confirmável em um toque.
     */
    @Test
    fun osSeteCasosQueOLote98AmpliouContinuamEscalandoDepoisDoMerge() = runBlocking {
        val falas = listOf(
            "hoje e amanhã às 9h",
            "dentista hoje e amanhã às 9h",
            "amanhã e depois de amanhã às 9h",
            "hoje e depois de amanhã dentista às 9h",
            "hoje e daqui a dois dias dentista às 9h",
            "hoje e daqui a duas semanas dentista às 9h",
            "amanhã e daqui a dois dias dentista às 9h",
        )
        for (fala in falas) {
            val localDraft = local.parse(fala)
            assertThat(localDraft.ambiguous).isTrue()
            val draft = hybrid(remotoCompleto()).parse(fala)
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
            assertThat(draft.notes.joinToString()).contains(NotasDoRascunho.DATA_AMBIGUA)
        }
    }

    /**
     * O cenário do #105, pela outra porta: "de 8 em 8 horas" não é um horário do dia, e a hora dita
     * depois é a primeira dose. O local escala e avisa; a IA devolvendo a hora completa apagava o
     * aviso e a caixa rápida gravava a tarefa única em silêncio.
     */
    @Test
    fun oIntervaloQueNaoEhHorarioSobreviveAoRemotoQueCompleta() = runBlocking {
        val fala = "de 8 em 8 horas"
        val localDraft = local.parse(fala)
        assertThat(localDraft.ambiguous).isTrue()
        assertThat(localDraft.notes.joinToString()).contains("é um intervalo")

        val draft = hybrid(remotoCompleto()).parse(fala)

        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        assertThat(draft.notes.joinToString()).contains("é um intervalo")
    }

    // --------------------------------------------------------- o outro lado: a "falta" não escala

    /**
     * O custo que o `missing.isNotEmpty()` foi criado para conter, e que a saída ingênua
     * (`localDraft.ambiguous || remoteDraft.ambiguous`) reabriria: a fala em que o local só **não
     * tinha** o campo. A IA preenche, o rascunho fica completo e a caixa rápida salva — que é o
     * trabalho que a ajuda extra existe para fazer.
     */
    @Test
    fun aFaltaDeCampoContinuaSendoResolvidaPelaIa() = runBlocking {
        // As duas portas da "falta": a que o local já marcava ambígua (a hora vaga de "de manhã",
        // "depois do jantar", "daqui a pouco") e a que só escalava pela ausência do campo
        // (`missingFields` — o gatilho do #43). O desfecho tem de ser o mesmo: a IA preenche, o
        // rascunho completa, a caixa salva em um toque.
        val falas = listOf(
            "tomar remédio amanhã de manhã",
            "tomar remédio pela manhã",
            "daqui a pouco",
            "tomar remédio depois do jantar",
            "tomar remédio amanhã",
            "reunião amanhã",
        )
        for (fala in falas) {
            val draft = hybrid(remotoCompleto()).parse(fala)

            assertThat(draft.ambiguous).isFalse()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
            // A nota de "falta" do campo que a IA trouxe sai; mantida, a tela mostraria "Falta o
            // horário" em vermelho acima do horário preenchido.
            assertThat(draft.notes.joinToString()).doesNotContain(NotasDoRascunho.FALTA_HORA)
            assertThat(draft.notes.joinToString()).doesNotContain(NotasDoRascunho.FALTA_DATA)
        }
    }

    /**
     * A hora vaga que o local escalou **sem** nota de "falta" — "de manhã" não é um horário exato,
     * mas o `LocalTaskParser` escreve essa frase sem prefixo em [NotasDoRascunho], então ela
     * **nunca** foi desmentida pela IA, nem antes deste lote.
     *
     * Medido no base (`git stash` do fix, mesmo stub): `amb=false qc=true` com a nota presente. É
     * pré-existente e fora do escopo deste lote — mas o desfecho fica preso, porque a nota vaga ao
     * lado do horário preenchido é a mesma classe de contradição visível que o merge evita. Um lote
     * que marcar essa nota na origem muda o resultado, e este teste avisa.
     */
    @Test
    fun aNotaDaHoraVagaQueNaoTemPrefixoFicaComoNaBase() = runBlocking {
        val fala = "tomar remédio amanhã de manhã"
        val localDraft = local.parse(fala)
        assertThat(localDraft.ambiguous).isTrue()
        val notaVaga = localDraft.notes.first { it.contains("não é um horário exato") }

        val draft = hybrid(remotoCompleto()).parse(fala)

        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
        assertThat(draft.notes).contains(notaVaga)
    }

    /**
     * O invariante dos **dois lados**, contado em vez de afirmado num exemplo.
     *
     * - **lado que conserta**: toda ambiguidade local de **conflito** tem de sobreviver — e a nota
     *   que a explica também.
     * - **lado que não pode regredir**: nenhuma ambiguidade de **falta** pode ser preservada (a IA
     *   preencheu o campo, a dúvida acabou) — nem a nota de "falta" pode ficar ao lado do campo
     *   preenchido.
     *
     * O esperado é calculado pelo texto da nota e pela constante de produção, não por uma cópia da
     * regra do merge. Se o conserto tapar só um lado, o outro acende aqui.
     */
    @Test
    fun oInvarianteDoMergeValeNosDoisLados() = runBlocking {
        val deConflito = listOf(
            // conflito de data
            "hoje e amanhã às 9h",
            "dentista hoje e amanhã às 9h",
            "amanhã e depois de amanhã às 9h",
            "hoje e daqui a dois dias dentista às 9h",
            "hoje e daqui a duas semanas dentista às 9h",
            "amanhã e daqui a dois dias dentista às 9h",
            "sexta e sábado às 9h",
            "segunda e quarta",
            "pagar conta no dia 25 e no dia 30",
            "natal no sábado almoço às 12h",
            // conflito de hora
            "de 8 em 8 horas",
            "remédio de duas em duas horas",
            "reunião amanhã às 9h ou às 10h",
            "amanhã antes do jantar às 20h",
            "amanhã meio da tarde às oito",
            "reunião amanhã às 3",
            "tomar remédio às vinte e cinco",
            "meio da manhã às onze",
        )
        // O outro juiz, e a razão de ele ser outro: a nota da recorrência cobre as duas causas, e
        // o que decide é a **regra**, não o texto. As falas abaixo têm a MESMA nota das de campo
        // ausente (que vão na lista de baixo) e desfecho oposto.
        val deConflitoPelaRegra = listOf(
            "todo ano dia 31 de abril remédio às 10h",
            "todo dia 32 de fevereiro remédio às 10h",
        )
        // O lado que NÃO pode ser preservado: o local só não tinha o campo. Inclui as duas portas —
        // as que o local já marcava ambíguas e as que escalam pela ausência (`missingFields`).
        val deFalta = listOf(
            "tomar remédio amanhã de manhã",
            "tomar remédio pela manhã",
            "daqui a pouco",
            "tomar remédio depois do jantar",
            "toda as reunião às 9h",
            "tomar remédio amanhã",
            "reunião amanhã",
        )

        var conflitos = 0
        var faltas = 0
        val conflitosPerdidos = mutableListOf<String>()
        val faltasPreservadas = mutableListOf<String>()

        for (fala in deConflito) {
            val localDraft = local.parse(fala)
            assertThat(localDraft.ambiguous).isTrue()
            assertThat(localDraft.notes.any { n -> HybridParser.NOTAS_DE_CONFLITO.any { n.contains(it) } })
                .isTrue()
            val draft = hybrid(remotoCompleto()).parse(fala)
            val label = "fala='$fala' notas=[${localDraft.notes.joinToString(" | ")}]"
            conflitos++
            if (!draft.ambiguous) conflitosPerdidos += label
            // A nota que explica o conflito não pode sumir junto com a ambiguidade.
            if (draft.notes.none { n -> HybridParser.NOTAS_DE_CONFLITO.any { n.contains(it) } }) {
                conflitosPerdidos += "$label → nota do conflito apagada"
            }
        }

        for (fala in deConflitoPelaRegra) {
            val localDraft = local.parse(fala)
            assertThat(localDraft.ambiguous).isTrue()
            assertThat(localDraft.notes.joinToString())
                .contains(HybridParser.NOTA_RECORRENCIA_AMBIGUA)
            // O oráculo é a REGRA, lida do rascunho local — não a lista de notas: é justamente por
            // o texto não distinguir as causas que este juiz existe. As primitivas são as do
            // domínio (`isCoherent` + `dayExistsInMonth`), não uma cópia de `temConflito`.
            val regra = localDraft.recurrence
            val dia = regra.dayOfMonth
            val mes = regra.monthOfYear
            val regraUtilizavel = regra.isCoherent &&
                (regra.kind != RecurrenceKind.YEARLY ||
                    (dia != null && mes != null && RecurrenceEngine.dayExistsInMonth(dia, mes)))
            assertThat(regraUtilizavel).isFalse()
            val draft = hybrid(remotoCompleto()).parse(fala)
            conflitos++
            if (!draft.ambiguous) {
                conflitosPerdidos += "fala='$fala' (regra fora da faixa) notas=[${localDraft.notes}]"
            }
        }

        for (fala in deFalta) {
            val draft = hybrid(remotoCompleto()).parse(fala)
            faltas++
            if (draft.ambiguous) {
                faltasPreservadas += "fala='$fala' notas=[${local.parse(fala).notes.joinToString(" | ")}]"
            }
        }

        println(
            "AMBIGUIDADE_MERGE conflitos=$conflitos faltas=$faltas " +
                "conflitosPerdidos=${conflitosPerdidos.size} faltasPreservadas=${faltasPreservadas.size}",
        )
        conflitosPerdidos.take(5).forEach { println("AMBIGUIDADE_MERGE conflitoPerdido: $it") }
        faltasPreservadas.take(5).forEach { println("AMBIGUIDADE_MERGE faltaPreservada: $it") }

        // O corpus cobre os dois lados de verdade; um lado vazio deixaria o invariante passar por
        // não ter o que medir.
        assertThat(conflitos).isAtLeast(15)
        assertThat(faltas).isAtLeast(5)
        assertThat(conflitosPerdidos).isEmpty()
        assertThat(faltasPreservadas).isEmpty()
    }

    // --------------------------------------------------------- F1: a recorrência fora da faixa

    /**
     * A nota da recorrência cobre **duas causas opostas**, e o texto é o mesmo nas duas:
     *
     *  - campo **ausente** ("toda as" sem o dia): é falta, o remoto completando libera a caixa;
     *  - valor **fora da faixa** (`YEARLY(32/02)`, `YEARLY(31/04)`): é **conflito** — a série não
     *    existe em calendário nenhum. A regra do local sobrevive ao merge (`mergedDate` só cuida de
     *    `localDate`), então a caixa verde gravaria uma série impossível.
     *
     * O defeito medido no head anterior a esta correção, com a lista fechando antes de olhar a
     * regra: `"todo dia 32 de fevereiro remédio às 10h"` → `amb=false qc=true finalRec=YEARLY/32/2`.
     */
    @Test
    fun aRecorrenciaForaDaFaixaNaoLiberaACaixaVerde() = runBlocking {
        val falas = listOf(
            "todo dia 32 de fevereiro remédio às 10h",
            "todo ano dia 32 de fevereiro remédio às 10h",
            "todo ano dia 31 de abril remédio às 10h",
        )
        for (fala in falas) {
            val localDraft = local.parse(fala)
            assertThat(localDraft.ambiguous).isTrue()
            assertThat(localDraft.notes.joinToString()).contains(HybridParser.NOTA_RECORRENCIA_AMBIGUA)

            val draft = hybrid(remotoCompleto()).parse(fala)

            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
            // A nota que explica a dúvida fica: o rascunho barrado não pode ficar mudo.
            assertThat(draft.notes.joinToString()).contains(HybridParser.NOTA_RECORRENCIA_AMBIGUA)
        }
    }

    /**
     * O outro lado do F1, e o que a correção **não** pode derrubar: a recorrência cujo campo
     * simplesmente não veio é **falta**, não conflito. O remoto completa a data e o rascunho
     * libera — é o caminho comum da escalação.
     */
    @Test
    fun aRecorrenciaComCampoAusenteContinuaLiberando() = runBlocking {
        val falas = listOf("toda as reunião às 9h", "todo os remédio às 9h")
        for (fala in falas) {
            val localDraft = local.parse(fala)
            assertThat(localDraft.ambiguous).isTrue()
            assertThat(localDraft.notes.joinToString()).contains(HybridParser.NOTA_RECORRENCIA_AMBIGUA)

            val draft = hybrid(remotoCompleto()).parse(fala)

            assertThat(draft.ambiguous).isFalse()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
        }
    }

    /**
     * A régua da faixa não pode ser larga: a regra que **existe** continua liberando a caixa
     * verde, inclusive as que o calendário resolve sozinho (`31 de maio` existe; `29 de fevereiro`
     * existe em ano bissexto e o `dayExistsInMonth` o aceita de propósito).
     */
    @Test
    fun aRegraQueExisteContinuaLiberandoACaixaVerde() = runBlocking {
        val falas = listOf(
            "todo dia 31 de maio remédio às 10h",
            "todo 29 de fevereiro revisar documentos às 11h",
            "dia 31 de cada mês",
        )
        for (fala in falas) {
            val draft = hybrid(remotoCompleto()).parse(fala)
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
        }
    }

    /**
     * O critério novo não mexe no `remoteDraft.ambiguous`: o remoto que se declara ambíguo **e não
     * preenche o essencial** continua barrando a caixa rápida, como sempre barrou.
     */
    @Test
    fun aAmbiguidadeDoRemotoContinuaValendoQuandoOFaltaFica() = runBlocking {
        val remotoAmbiguoSemHora = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Consulta",
                localDate = LocalDate.of(2026, 8, 25),
                localTime = null,
                confidence = 0.6,
                missingFields = emptySet(),
                ambiguous = true,
                transcript = transcript,
                notes = emptyList(),
                source = DraftSource.AI,
            )
        }
        val draft = hybrid(remotoAmbiguoSemHora).parse("tomar remédio amanhã")

        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    /**
     * O outro caso do remoto ambíguo, pinado como **estava na base**: ele marca ambíguo, mas
     * devolve o essencial completo (`missing = []`) e a ambiguidade dele cai.
     *
     * Não é o defeito que este lote corrige — a doutrina aqui é sobre a ambiguidade **local** que o
     * remoto não endereçou. Mas o desfecho precisa estar preso: se um lote futuro mexer na linha do
     * `ambiguous`, este caso muda de número e o teste avisa. Medido no base antes do fix:
     * `finalAmb=false qc=true`.
     */
    @Test
    fun aAmbiguidadeDoRemotoQuePreencheuTudoSegueComoNaBase() = runBlocking {
        val remotoAmbiguoCompleto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Consulta",
                localDate = LocalDate.of(2026, 8, 25),
                localTime = LocalTime.of(9, 0),
                confidence = 0.6,
                missingFields = emptySet(),
                ambiguous = true,
                transcript = transcript,
                notes = emptyList(),
                source = DraftSource.AI,
            )
        }
        val draft = hybrid(remotoAmbiguoCompleto).parse("tomar remédio amanhã")

        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
    }

    /**
     * O caminho limpo não regride: quando o local reconhece tudo, o remoto nem é consultado.
     */
    @Test
    fun oCaminhoOfflineContinuaSemTocarNoRemoto() = runBlocking {
        var chamou = false
        val remoto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft {
                chamou = true
                error("não deveria")
            }
        }
        val draft = hybrid(remoto).parse("tomar remédio amanhã às 9h")

        assertThat(chamou).isFalse()
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.source).isEqualTo(DraftSource.LOCAL)
    }
}

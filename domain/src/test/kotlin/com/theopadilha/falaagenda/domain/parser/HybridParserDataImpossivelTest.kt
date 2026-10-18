package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A nota de data impossível do **mês deduzido** sobrevive ao merge remoto.
 *
 * "pagar a conta no dia 31" ouvido em fevereiro: o local recusa — `localDate = null`,
 * `ambiguous = true` e a nota "Data impossível: o dia 31 não existe em fevereiro. Confirme a
 * data." O remoto devolvendo **uma data qualquer** (o palpite 28/02) não endereçou o dia 31 que
 * ela disse: preencheu o campo por cima. Antes desta correção, `conflitoLocalPreservado` era
 * falso (`DATA_IMPOSSIVEL` não estava em `NOTAS_DE_CONFLITO`), a nota era apagada por
 * `SOBRE_A_DATA`, `ambiguous` morria e `qc=true` confirmava em um toque uma data que ela nunca
 * falou.
 *
 * O caso gemeo da recorrência (`YEARLY(31/04)`) já era tratado pelo #114 — a data avulsa ficou
 * fora. A doutrina é a mesma: conflito = o remoto não endereçou; resolução real = o remoto
 * trouxe **o próprio dia dito** em um mês em que ele existe (31/03) — valor possível que ela
 * pode ter querido.
 *
 * A forma do **mês explícito** ("31/02") é outra: a fala inteira era a contradição, e qualquer
 * data que o remoto trouxe já é a resposta a "o que ela quis?" — comportamento codificado pelo
 * teste pré-existente `aNotaDaDataQueNaoExisteSaiQuandoIaResolveAData`, que este lote mantém.
 *
 * O relógio é sexta, 20/02/2026, 10:00 em America/Sao_Paulo — fevereiro, onde o dia 31 não
 * existe.
 */
class HybridParserDataImpossivelTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 2, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    /** O remoto do gap: data e hora completas, `ambiguous = false`, `missing = []`. */
    private fun remoto(data: LocalDate?) = object : RemoteDraftParser {
        override suspend fun parse(
            transcript: String,
            nowIso: String,
            timezone: String,
            locale: String,
        ): ParsedTaskDraft = ParsedTaskDraft(
            title = "Pagar conta",
            localDate = data,
            localTime = LocalTime.of(9, 0),
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

    private val fala = "pagar a conta no dia 31"

    /**
     * O gap medido pelo review: o remoto chuta 28/02 e a caixa verde confirma a data que ela não
     * falou. O desfecho correto preserva a ambiguidade e a nota.
     */
    @Test
    fun oChuteDoRemotoNoMesDeduzidoNaoLiberaACaixaVerde() = runBlocking {
        val localDraft = local.parse(fala)
        assertThat(localDraft.ambiguous).isTrue()
        assertThat(localDraft.localDate).isNull()
        assertThat(localDraft.notes.joinToString()).contains("o dia 31 não existe em")

        val draft = hybrid(remoto(LocalDate.of(2026, 2, 28))).parse(fala)

        println("DATA_IMPOSSIVEL antes_do_merge amb=${localDraft.ambiguous} qc=" +
            localDraft.canQuickConfirm(clock.instant(), zone) +
            " notas=[${localDraft.notes.joinToString(" | ")}]")
        println("DATA_IMPOSSIVEL depois_do_merge(28/02) amb=${draft.ambiguous} qc=" +
            draft.canQuickConfirm(clock.instant(), zone) +
            " notas=[${draft.notes.joinToString(" | ")}]")

        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        // A nota que explica a dúvida fica: o rascunho barrado não pode ficar mudo.
        assertThat(draft.notes.joinToString()).contains("o dia 31 não existe em")
    }

    /**
     * O outro lado, e o que a correção não pode derrubar: o remoto que trouxe o **próprio dia
     * dito** (31/03 — valor possível que ela pode ter querido) resolveu de verdade. A nota sai e
     * a caixa libera, como sempre.
     */
    @Test
    fun oRemotoQueTrazOProprioDiaDitoResolve() = runBlocking {
        val draft = hybrid(remoto(LocalDate.of(2026, 3, 31))).parse(fala)

        println("DATA_IMPOSSIVEL depois_do_merge(31/03) amb=${draft.ambiguous} qc=" +
            draft.canQuickConfirm(clock.instant(), zone) +
            " notas=[${draft.notes.joinToString(" | ")}]")

        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 3, 31))
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
        assertThat(draft.notes.joinToString()).doesNotContain("o dia 31 não existe em")
    }

    /**
     * A forma do mês **explícito** segue o desmentido antigo: a fala "31/02" já era a
     * contradição, e qualquer data do remoto responde a ela — o teste pré-existente
     * (`aNotaDaDataQueNaoExisteSaiQuandoIaResolveAData`) codificou isso e fica de pé.
     */
    @Test
    fun aFormaDoMesExplicitoSegueODesmentidoAntigo() = runBlocking {
        val falaMesExplicito = "consulta 31/02/2027 às 10h"
        val localDraft = local.parse(falaMesExplicito)
        assertThat(localDraft.ambiguous).isTrue()
        assertThat(localDraft.notes.joinToString()).contains("não existe no calendário")

        val draft = hybrid(remoto(LocalDate.of(2027, 2, 28))).parse(falaMesExplicito)

        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
        assertThat(draft.notes.joinToString()).doesNotContain("não existe no calendário")
    }
}

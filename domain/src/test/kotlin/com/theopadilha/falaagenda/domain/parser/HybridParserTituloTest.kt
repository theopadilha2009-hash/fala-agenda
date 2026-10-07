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
 * O título do parser local, quando ele acertou, não pode ser sobrescrito pela IA.
 *
 * O defeito medido, com o cliente real e um interceptor devolvendo resposta de LLM:
 * ```
 * fala "levar a Maria no médico dia 25"
 *   título LOCAL = 'Levar Maria médico'  →  título FINAL = 'Compromisso'
 *   canQuickConfirm = true  →  salva em silêncio, sem tela de confirmação
 * ```
 * Ela perdia a informação de que era o médico e o cartão passava a se chamar "Compromisso".
 * Como o título alimenta `isComplete`/`canQuickConfirm`, o erro não passa por nenhuma tela.
 *
 * A IA é para **completar o que falta** — data, hora, o título que o local não achou. Não para
 * trocar o que já estava certo.
 */
class HybridParserTituloTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 10, 6, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    /** A resposta medida da IA para a fala do defeito: título genérico, data e hora resolvidas. */
    private fun iaComTituloGenerico(titulo: String = "Compromisso") = object : RemoteDraftParser {
        override suspend fun parse(
            transcript: String,
            nowIso: String,
            timezone: String,
            locale: String,
        ): ParsedTaskDraft = ParsedTaskDraft(
            title = titulo,
            localDate = LocalDate.of(2026, 10, 25),
            localTime = LocalTime.of(10, 0),
            confidence = 0.9,
            missingFields = emptySet(),
            ambiguous = false,
            transcript = transcript,
            notes = listOf("A ajuda extra completou a data."),
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

    @Test
    fun tituloDoLocalVenceQuandoIaDevolveGenerico() = runBlocking {
        val localDraft = local.parse("levar a Maria no médico dia 25")
        assertThat(localDraft.title).isEqualTo("Levar Maria médico")

        val draft = hybrid(iaComTituloGenerico()).parse("levar a Maria no médico dia 25")

        assertThat(draft.title).isEqualTo("Levar Maria médico")
        assertThat(draft.title).isNotEqualTo("Compromisso")
        // A data e a hora que faltavam continuam vindo da IA: o fix tira a sobrescrita do
        // título, não o complemento.
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 0))
    }

    /**
     * Ela precisa poder ver que a ajuda extra mudou o nome — a troca não pode ser silenciosa
     * como era antes, quando o cartão nascia "Compromisso" sem nenhum aviso.
     */
    @Test
    fun aTrocaDeTituloPelaIaDeixaNota() = runBlocking {
        val draft = hybrid(iaComTituloGenerico()).parse("levar a Maria no médico dia 25")

        assertThat(draft.notes.joinToString()).contains("Compromisso")
        assertThat(draft.notes.joinToString()).contains("médico")
    }

    /**
     * A IA continua preenchendo o título quando o local não achou nenhum. "dia 25" é a fala em
     * que o local extrai a data e **não** sobra título, e que escala porque falta a hora — o
     * caminho em que o complemento da IA é justamente o trabalho que ele tem de fazer.
     */
    @Test
    fun tituloDaIaPreencheQuandoOLocalNaoTem() = runBlocking {
        val localDraft = local.parse("dia 25")
        assertThat(localDraft.title).isEmpty()
        assertThat(localDraft.localTime).isNull()

        val draft = hybrid(iaComTituloGenerico("Consulta")).parse("dia 25")

        assertThat(draft.title).isEqualTo("Consulta")
    }

    /** Sem divergência de título não há nota nova — o caminho comum segue limpo. */
    @Test
    fun semDivergenciaNaoAcrescentaNotaDeTitulo() = runBlocking {
        val draft = hybrid(iaComTituloGenerico("Levar Maria médico"))
            .parse("levar a Maria no médico dia 25")

        assertThat(draft.title).isEqualTo("Levar Maria médico")
        assertThat(draft.notes.joinToString()).doesNotContain("mudou o nome")
        assertThat(draft.notes.joinToString()).doesNotContain("chamou")
    }

    /**
     * O doc da classe promete que a troca de título "não passa por nenhuma tela" porque
     * `canQuickConfirm` é verdadeiro — era o que fazia o cartão nascer "Compromisso" em silêncio.
     *
     * Sem esta asserção o doc afirmava um mecanismo que nenhum caso prendia: se a IA deixasse o
     * rascunho ambíguo (ou faltando campo), a troca de título voltaria a aparecer na tela de
     * confirmação e o defeito descrito aqui seria outro — invisível para a suíte.
     */
    @Test
    fun oRascunhoDoDefeitoSalvaEmSilencio() = runBlocking {
        val draft = hybrid(iaComTituloGenerico()).parse("levar a Maria no médico dia 25")
        val agora = clock.instant()

        assertThat(draft.isComplete).isTrue()
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(agora, zone)).isTrue()
    }
}

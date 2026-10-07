package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A correção que troca a DATA (e não o alvo): ela dita "amanhã às oito, não, hoje".
 *
 * Relógio fixo declarado: **2026-08-20 10:00 America/Sao_Paulo** (quinta-feira).
 *
 * O esperado de cada caso é escrito À MÃO, pela doutrina do produto — "ela disse o valor
 * corrigido, então é o valor corrigido" —, e a aritmética do calendário é feita aqui, fora do
 * parser:
 *
 * | dita        | vale       |
 * |-------------|------------|
 * | hoje        | 20/08/2026 |
 * | amanhã      | 21/08/2026 |
 * | segunda     | 24/08/2026 |
 * | terça       | 25/08/2026 |
 * | dia 25      | 25/08/2026 |
 * | dia 28      | 28/08/2026 |
 * | semana que vem | 27/08/2026 |
 *
 * Nenhum esperado é derivado de rodar o parser numa "frase de referência": o oráculo que se
 * calcula com o mesmo raciocínio do código não é oráculo, é espelho.
 */
class LocalTaskParserCorrecaoDeDataTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    private fun parse(fala: String) = parser.parse(fala)

    @Test
    fun controleSemCorrecaoNaoMuda() {
        val draft = parse("me lembra de tomar remédio amanhã às oito")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.ambiguous).isFalse()
    }

    /** O P0 do catálogo: ela corrige o dia para HOJE e o app agenda AMANHÃ, calado. */
    @Test
    fun correcaoDoDiaVenceODiaDescartado() {
        val draft = parse("me lembra de tomar remédio amanhã às oito, não, hoje")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaComAHoraDepoisDoConector() {
        val draft = parse("me lembra de tomar remédio amanhã, não, hoje às oito")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDaHoraNaoApagaAHora() {
        val draft = parse("me lembra de tomar remédio amanhã às 8, não, às 9")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaDaSemana() {
        val draft = parse("me lembra de tomar remédio segunda, não, terça")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 25))
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaDoMes() {
        val draft = parse("me lembra de tomar remédio dia 25, não, dia 28")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 28))
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaEDaHoraJuntos() {
        val draft = parse("me lembra de tomar remédio amanhã às 8, não, hoje às 9")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDeDiaRelativo() {
        val draft = parse("me lembra de tomar remédio amanhã às oito, não, semana que vem")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun correcaoDuplaValeAUltima() {
        val draft = parse("me lembra de tomar remédio amanhã às oito, não, segunda, não, hoje")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }
}

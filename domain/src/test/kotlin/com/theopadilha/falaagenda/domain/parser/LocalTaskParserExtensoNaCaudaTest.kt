package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A cauda da segunda hora em EXTENSO ("às tres e oito da noite") ficou fora do gatilho do
 * #129 (`cacada-fala-2026-10-18-dois-horarios-sem-periodo.md`): o "oito" não está no
 * `MINUTE_TAIL` nem no `MINUTE_TAIL_WORDS`, então o `trailingMinutes` não consome a cauda, a
 * cauda não é dígito e o `TRAILING_PERIOD` nem chega a rodar — a primeira hora crava 03:00
 * com `ambiguous=false` e a segunda (a 20h) SOBRA NO TÍTULO ("Tomar remédio oito").
 *
 * O critério é o mesmo do #129 — coerência período↔hora — estendido à cauda em extenso quando
 * o extenso é NOME DE HORA. Fração ("e meia") fica de fora: é minuto por natureza, e o corpus
 * B4 trava "às 3 e meia da noite" = 03:30.
 */
class LocalTaskParserExtensoNaCaudaTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    @Test
    fun extensoNaCaudaNaoCravaASegundaHoraNoTitulo() {
        val draft = parser.parse("tomar remédio às tres e oito da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
    }

    @Test
    fun digitoNaPrimeiraComExtensoNaCaudaTambemEscala() {
        val draft = parser.parse("tomar remédio às 3 e oito da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
    }

    @Test
    fun aNotaDoDesfechoEDeDoisHorarios() {
        val draft = parser.parse("tomar remédio às tres e oito da noite")
        assertThat(draft.notes.joinToString(" ")).contains("dois horários")
    }

    @Test
    fun aSegundaHoraSaiDoTitulo() {
        val draft = parser.parse("tomar remédio às tres e oito da noite")
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    // ---- O par discriminador: só a incoerência noite × madrugada escala ----

    @Test
    fun comManhaAindaECoerenteECrava() {
        // 03:00 ∈ manhã: a cauda em extenso não abre tomada. O título sujo ("…oito") é o estado
        // pré-existente fora do gatilho — travado aqui para o fix não mudar nada além do gatilho.
        val draft = parser.parse("tomar remédio às tres e oito da manha")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.title).contains("oito")
    }

    @Test
    fun semPeriodoNaCaudaPermaneceComoHoje() {
        // "e oito" sem período nenhum: fora do gatilho, título sujo pré-existente.
        val draft = parser.parse("tomar remédio às tres e oito")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.title).contains("oito")
    }
}

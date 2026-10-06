package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O casamento do alvo falado com os títulos da agenda.
 *
 * O caso que importa é o de dois "remédio": escolher um no chute e cancelar o errado é pior
 * que não cancelar nada. A matcher nunca escolhe — ela devolve [SpeechTargetResolution.Ambiguous]
 * e quem executa pergunta.
 */
class SpeechTargetMatcherTest {

    private fun candidates(vararg titles: String) =
        titles.mapIndexed { i, t -> SpeechCandidate(id = "id$i", title = t) }

    @Test
    fun umUnicoTituloCasa() {
        val r = SpeechTargetMatcher.resolve("médico", candidates("Consulta médica"))
        assertThat(r).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun doisRemediosSaoAmbiguos() {
        val r = SpeechTargetMatcher.resolve(
            "remédio",
            candidates("Tomar remédio", "Comprar remédio"),
        )
        assertThat(r).isInstanceOf(SpeechTargetResolution.Ambiguous::class.java)
        assertThat((r as SpeechTargetResolution.Ambiguous).ids).containsExactly("id0", "id1")
    }

    @Test
    fun nomeQueNaoExisteNaoCasa() {
        val r = SpeechTargetMatcher.resolve("dentista", candidates("Consulta médica"))
        assertThat(r).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun oTituloInteiroDentroDoAlvoCasa() {
        val r = SpeechTargetMatcher.resolve(
            "cancela a consulta médica de amanhã",
            candidates("Consulta médica"),
        )
        assertThat(r).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun raizEmComumCasaMesmoComAcentoDiferente() {
        // "medico" (falado) e "médica" (título) compartilham a raiz "medic".
        val r = SpeechTargetMatcher.resolve("medico", candidates("Consulta médica"))
        assertThat(r).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoCurtoDemaisNaoCasa() {
        // "a" não pode casar com tudo — o piso de tamanho existe para isso.
        assertThat(SpeechTargetMatcher.resolve("a", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun raizNoMeioDaPalavraNaoCasa() {
        // "dia" e "remédio" compartilham "dio" no meio — não é a mesma palavra.
        assertThat(SpeechTargetMatcher.resolve("dia", candidates("Tomar remédio")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun semCandidatosNaoCasa() {
        assertThat(SpeechTargetMatcher.resolve("médico", emptyList()))
            .isEqualTo(SpeechTargetResolution.None)
    }
}

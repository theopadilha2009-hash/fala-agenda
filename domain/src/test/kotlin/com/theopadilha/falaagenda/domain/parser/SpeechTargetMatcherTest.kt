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

    // --- a raiz em comum não pode ser um prefixo curto qualquer -----------------------
    //
    // Quatro letras iguais no começo de duas palavras DIFERENTES não são a mesma palavra:
    // "carro" e "carregador" começam as duas com "carr". Casar por prefixo curto concluía ou
    // apagava a tarefa errada.

    @Test
    fun carroNaoCasaCarregador() {
        assertThat(SpeechTargetMatcher.resolve("carro", candidates("Carregador do celular")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun luzNaoCasaLuzia() {
        assertThat(SpeechTargetMatcher.resolve("luz", candidates("Luzia, aniversário")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun contaNaoCasaContrato() {
        // "conta" e "contrato" só compartilham "cont": não é a mesma palavra.
        assertThat(SpeechTargetMatcher.resolve("conta", candidates("Contrato do aluguel")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun remedioCasaRemedioInteiro() {
        // O que o casamento precisa cobrir de verdade continua casando: uma palavra inteira
        // igual à do título.
        assertThat(SpeechTargetMatcher.resolve("remédio", candidates("Remédio do cachorro")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    /**
     * A raiz flexiva continua valendo: "médico" falado casa "Consulta médica" no título, e
     * "médica" no título casa "médico" no alvo — o reconhecimento de fala erra gênero.
     */
    @Test
    fun raizFlexivaAindaCasa() {
        assertThat(SpeechTargetMatcher.resolve("medico", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    // --- prefixo de 5 letras não é a mesma palavra ------------------------------------
    //
    // Radicais DIFERENTES que começam igual não podem casar: com um alvo só na agenda, o
    // casamento é `One` e a ação acontece calada — conclui ou apaga a tarefa errada.

    @Test
    fun medicoNaoCasaMedicamento() {
        // O caso provável na agenda dela: "médico" e "medicamento" só compartilham "medic".
        assertThat(SpeechTargetMatcher.resolve("medico", candidates("Tomar medicamento")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun contaNaoCasaContador() {
        assertThat(SpeechTargetMatcher.resolve("conta", candidates("Contador de água")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun carroNaoCasaCarroca() {
        assertThat(SpeechTargetMatcher.resolve("carro", candidates("Carroça do vizinho")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun carteiraNaoCasaCarteirinha() {
        assertThat(SpeechTargetMatcher.resolve("carteira", candidates("Carteirinha do ônibus")))
            .isEqualTo(SpeechTargetResolution.None)
    }

    // --- o que DEVE continuar casando -------------------------------------------------

    @Test
    fun palavraInteiraAindaCasa() {
        assertThat(SpeechTargetMatcher.resolve("consulta", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun flexaoRegularAindaCasa() {
        // "remedio" (falado, sem acento) e "remédio" (título) são a mesma palavra.
        assertThat(SpeechTargetMatcher.resolve("remedio", candidates("Remédio do cachorro")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    // --- alvo de VÁRIAS palavras: a maioria estrita tem de casar ----------------------
    //
    // Com UMA tarefa só na agenda, um alvo que compartilhava uma única palavra genérica
    // virava `One` e a ação acontecia calada — apagava ou concluía a tarefa errada. "cancela
    // o remédio do cachorro" apagava "Passear com o cachorro"; "já tomei o remédio do
    // cachorro" concluía o passeio. O alvo tem de casar com a TAREFA, não com uma palavra
    // solta dela. Uma palavra só continua casando como sempre (o caso mais comum).

    @Test
    fun alvoDeUmaPalavraContinuaCasando() {
        assertThat(SpeechTargetMatcher.resolve("medico", candidates("Consulta médica")))
            .isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoDeTresPalavrasComUmaTemporalCasa() {
        // 2 de 3 significativas casam ("de" não conta): é a maioria, e o nome composto está
        // lá dentro. É o que o parser entrega em "cancela a consulta médica de amanhã".
        assertThat(
            SpeechTargetMatcher.resolve("consulta medica de amanha", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun remedioDoCachorroNaoApagaOPasseio() {
        // "remedio do cachorro": 1 de 2 significativas casa (só "cachorro"). NÃO é a tarefa.
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro",
                candidates("Passear com o cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun consultaDoDentistaNaoApagaAConsultaMedica() {
        // "consulta do dentista": 1 de 2 significativas casa (só "consulta"). NÃO é a médica.
        assertThat(
            SpeechTargetMatcher.resolve("consulta do dentista", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun alvoDeDuasPalavrasComSoUmaCasandoNaoCasa() {
        // Mesmo com a segunda palavra sendo do mesmo campo ("medico do coracao"), 1 de 2 não
        // é maioria — na dúvida, `None` é o desfecho seguro.
        assertThat(
            SpeechTargetMatcher.resolve("medico do coracao", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    @Test
    fun alvoDeTresPalavrasComSoUmaCasandoNaoCasa() {
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro amanha",
                candidates("Passear com o cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.None)
    }

    // --- o que continua casando com várias palavras ----------------------------------

    @Test
    fun alvoComOTituloInteiroCasa() {
        assertThat(
            SpeechTargetMatcher.resolve("remedio do cachorro", candidates("Remédio do cachorro")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoDeDuasPalavrasAsDuasCasandoCasa() {
        assertThat(
            SpeechTargetMatcher.resolve("tomar remedio", candidates("Tomar remédio em jejum")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun pluralEGeneroNoAlvoInteiroCasa() {
        // "consultas medicas" (falado) casa "Consulta médica": as duas palavras flexionam.
        assertThat(
            SpeechTargetMatcher.resolve("consultas medicas", candidates("Consulta médica")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun alvoDeTresPalavrasComTemporalNoFimCasa() {
        assertThat(
            SpeechTargetMatcher.resolve("tomar remedio hoje", candidates("Tomar remédio")),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }

    @Test
    fun tituloInteiroComTemporalNoAlvoCasa() {
        // O título inteiro está no alvo; a palavra a mais (tempo) não pode derrubar o casamento.
        assertThat(
            SpeechTargetMatcher.resolve(
                "remedio do cachorro amanha",
                candidates("Remédio do cachorro"),
            ),
        ).isEqualTo(SpeechTargetResolution.One("id0"))
    }
}

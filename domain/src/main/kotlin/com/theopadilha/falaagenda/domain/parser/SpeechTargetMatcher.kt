package com.theopadilha.falaagenda.domain.parser

/** Uma tarefa candidata a ser o alvo de um comando, reduzida ao que o casamento precisa. */
data class SpeechCandidate(val id: String, val title: String)

/** O que o alvo falado aponta na agenda. */
sealed interface SpeechTargetResolution {
    /** Um único candidato: pode agir. */
    data class One(val id: String) : SpeechTargetResolution

    /** Nenhum candidato com esse nome. */
    data object None : SpeechTargetResolution

    /**
     * Mais de um candidato com esse nome. NUNCA escolher no chute: dois "remédio" viram uma
     * pergunta, não um palpite — cancelar o remédio errado é pior que não cancelar nada.
     */
    data class Ambiguous(val ids: List<String>) : SpeechTargetResolution
}

/**
 * Casa o alvo falado ("médico", "remédio") com os títulos da agenda.
 *
 * É deliberadamente tolerante com a fala (o reconhecimento erra acento, plural e gênero) e
 * deliberadamente rígido com o que aceita como casamento: só conta o que tem raiz em comum
 * com um mínimo de letras, para "a" não casar com tudo e um nome parcial não derrubar a
 * tarefa errada.
 */
object SpeechTargetMatcher {
    /** Menor palavra que conta: "a"/"de" não podem casar com tudo. */
    private const val MIN_WORD = 3

    /**
     * Menor raiz em comum para valer como a MESMA palavra flexionada ("medico"/"medica").
     *
     * Um prefixo longo NÃO basta: "medico"/"medicamento", "conta"/"contador",
     * "carro"/"carroça" e "carteira"/"carteirinha" começam igual e são palavras diferentes.
     * Com um alvo só na agenda, o casamento é `One` e a ação acontece calada — conclui ou
     * apaga a tarefa errada. Quem separa os dois casos é [INFLECTION_TAILS], não o tamanho.
     */
    private const val MIN_STEM = 5

    /**
     * Os restos que ainda são a MESMA palavra: gênero ("medico"/"medica"), número
     * ("conta"/"contas") e a vogal temática. Qualquer outro resto é sufixo derivacional
     * ("amento", "dor", "inha", "ca") — outra palavra, e o casamento não vale.
     */
    private val INFLECTION_TAILS = setOf("", "a", "o", "e", "s", "as", "os", "es")

    fun resolve(target: String, candidates: List<SpeechCandidate>): SpeechTargetResolution {
        val alvo = TextNormalizer.fold(target)
        if (alvo.length < 3) return SpeechTargetResolution.None
        val hits = candidates.filter { matches(alvo, it.title) }
        return when (hits.size) {
            0 -> SpeechTargetResolution.None
            1 -> SpeechTargetResolution.One(hits.first().id)
            else -> SpeechTargetResolution.Ambiguous(hits.map { it.id })
        }
    }

    private fun matches(alvo: String, title: String): Boolean {
        val alvoWords = alvo.split(' ').filter { it.isNotBlank() }
        val tituloWords = TextNormalizer.fold(title).split(' ').filter { it.isNotBlank() }
        // Palavra INTEIRA igual: é o casamento exato e o que cobre o nome composto
        // ("consulta medica" no alvo "cancela a consulta medica de amanha"). Comparar palavra
        // com palavra — e não substrings — é o que impede "luz" de casar "Luzia".
        if (alvoWords.any { a -> a.length >= MIN_WORD && tituloWords.any { it == a } }) return true
        // Fora isso, raiz flexiva: a MESMA palavra com terminação diferente ("medico"/"medica").
        return alvoWords.any { a -> tituloWords.any { t -> shareStem(a, t) } }
    }

    /**
     * A raiz comum tem de começar a palavra (nunca no meio: "dia" e "medio" compartilham "dio"
     * e não são a mesma coisa) e o que sobra DEPOIS dela tem de ser flexão nos dois lados.
     *
     * É o resto que separa "medico"/"medica" (restos "o"/"a" — a mesma palavra) de
     * "medico"/"medicamento" (restos ""/"amento" — palavras diferentes), sem depender de um
     * tamanho de prefixo que sempre terá um par realista para furar.
     */
    private fun shareStem(a: String, b: String): Boolean {
        if (a.length < MIN_STEM || b.length < MIN_STEM) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (i < MIN_STEM) return false
        return a.substring(i) in INFLECTION_TAILS && b.substring(i) in INFLECTION_TAILS
    }
}

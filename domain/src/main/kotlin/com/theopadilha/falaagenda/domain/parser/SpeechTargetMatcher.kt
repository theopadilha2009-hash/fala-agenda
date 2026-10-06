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
     * O piso de 4 letras do casamento antigo aceitava prefixos enganosos: "carro" casava
     * "Carregador do celular", "conta" casava "Contrato do aluguel" e "luz" casava "Luzia,
     * aniversário" — concluir ou apagar a tarefa errada. Com 5, a flexão legítima continua
     * casando e as palavras diferentes de prefixo curto ficam de fora.
     */
    private const val MIN_STEM = 5

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
     * A raiz comum tem que começar a palavra (comparação de prefixo), nunca no meio: "dia" e
     * "medio" compartilham "dio" no meio e não são a mesma coisa.
     */
    private fun shareStem(a: String, b: String): Boolean {
        if (a.length < MIN_STEM || b.length < MIN_STEM) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i >= MIN_STEM
    }
}

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
    private const val MIN_STEM = 4

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
        val titulo = TextNormalizer.fold(title)
        // O título inteiro contido no alvo (ou vice-versa) cobre o caso de nome composto:
        // "consulta medica" no alvo "cancela a consulta medica de amanha".
        if (titulo.length >= 3 && (alvo.contains(titulo) || titulo.contains(alvo))) return true
        // Fora isso, o casamento é por palavra com raiz em comum — "medico" e "medica" casam
        // pela raiz "medic", e "remedio" não casa com "mercado" (raiz "re" curta demais).
        return alvo.split(' ').any { a ->
            titulo.split(' ').any { t -> shareStem(a, t) }
        }
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

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

    /**
     * Circunstância de TEMPO, não conteúdo da tarefa. O parser já a consome como data/hora
     * ("amanhã" vira a data, "de manhã" vira o período), então no alvo ela é ruído do
     * reconhecedor: não pode contar na maioria nem derrubar um casamento que sem ela
     * aconteceria. Sem esta poda, "já tomei o remédio de manhã" — a fala mais provável de uma
     * rotina — devolvia `None` e a dose não era registrada, porque "manhã" entrava como uma
     * segunda palavra significativa e a maioria (1 de 2) não fechava.
     *
     * Só o ALVO é podado; o título é o nome da tarefa e continua inteiro ("Tomar remédio de
     * manhã" casa normalmente).
     */
    private val TEMPORAIS = setOf(
        "hoje", "amanha", "ontem", "manha", "tarde", "noite", "madrugada",
        "segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo",
    )

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

        // Circunstância de tempo sai do alvo antes de contar (ver [TEMPORAIS]): não é conteúdo
        // da tarefa, e o parser já a consumiu como data/hora. Sem esta poda, "já tomei o
        // remédio de manhã" virava 1 de 2 significativas e devolvia `None` — a dose não era
        // registrada. Com ela, o alvo sobra em "remedio" e casa "Tomar remédio" como sempre.
        val significativas = alvoWords.filter { it.length >= MIN_WORD && it !in TEMPORAIS }

        // Alvo de UMA palavra: o caso mais comum ("cancela o médico" → a consulta médica) e o
        // que a fala produz na maioria das vezes. Continua casando como sempre — palavra
        // inteira ou raiz flexiva — e não pode endurecer.
        if (significativas.size <= 1) {
            return significativas.any { a -> tituloWords.any { t -> wordMatches(a, t) } }
        }

        // Alvo de VÁRIAS palavras: exige a MAIORIA ESTRITA das significativas. Uma única
        // palavra genérica em comum não basta — era isso que, com uma tarefa só na agenda,
        // virava `One` e agia calado: "cancela o remédio do cachorro" apagava "Passear com o
        // cachorro" (1 de 2) e "já tomei o remédio do cachorro" concluía o passeio.
        //
        // Havia aqui um ramo "título inteiro dentro do alvo" que casava sem olhar a proporção.
        // Com o título de UMA palavra significativa ("Dentista"), ele virava MAIS permissivo
        // que a maioria: bastava a palavra aparecer em qualquer alvo. "já tomei o remédio do
        // dentista" com [Dentista, Tomar remédio] virava `One(Dentista)` e o app concluía
        // "Dentista" respondendo "Feito." — a ação destrutiva que mente. Ele também era
        // redundante: os casos que o justificavam ("consulta medica de amanha") já passam pela
        // maioria, uma vez que o temporal sai da contagem. Removido.
        val casadas = significativas.count { a -> tituloWords.any { t -> wordMatches(a, t) } }
        return casadas * 2 > significativas.size
    }

    /**
     * Uma palavra do alvo casa uma do título: inteira e igual, ou a MESMA palavra flexionada
     * ("medico"/"medica"). Comparar palavra com palavra — e não substrings — é o que impede
     * "luz" de casar "Luzia".
     */
    private fun wordMatches(a: String, t: String): Boolean =
        (a.length >= MIN_WORD && a == t) || shareStem(a, t)

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

package com.theopadilha.falaagenda.domain.parser

/**
 * O que ela quis dizer ao falar — decidido ANTES de o texto virar tarefa.
 *
 * O app não tinha esta camada: todo texto reconhecido ia direto para o parser e virava tarefa
 * nova. "cancela o médico" criava a tarefa "Cancela o médico", "o que tenho hoje?" criava uma
 * tarefa sem título, e ela ficava acreditando que tinha cancelado ou que não havia nada no dia.
 *
 * O dano não é a tarefa boba: é ela acreditar que cancelou, mudou ou registrou algo que não
 * aconteceu.
 *
 * O princípio é "nunca fingir que fez": quando o app reconhece uma intenção que ainda não sabe
 * executar, ele diz isso em português de gente — não cria uma tarefa com cara de sucesso.
 */
sealed interface SpeechIntent {
    /** Um recado novo: o caminho de sempre, em que o texto vira rascunho de tarefa. */
    data object Capture : SpeechIntent

    /** Uma pergunta sobre a agenda. */
    data class Ask(val whenDay: AskWhen) : SpeechIntent

    /** "já tomei": marcar como feito o que casa com [target]. */
    data class Complete(val target: String) : SpeechIntent

    /**
     * "cancela o médico": apagar o que casa com [target]. Destrutivo — quem executa oferece o
     * desfazer, e nome ambíguo nunca é escolhido no chute.
     */
    data class Cancel(val target: String) : SpeechIntent

    /** Uma intenção que reconhecemos e ainda NÃO sabemos fazer. */
    data class Unknown(val kind: UnsupportedKind) : SpeechIntent
}

/** De que dia ela está perguntando. */
enum class AskWhen { TODAY, TOMORROW }

/** As intenções reconhecidas que o app ainda não executa. */
enum class UnsupportedKind {
    /** Mudar a data/hora, adiar ou remarcar uma tarefa que já existe. */
    CHANGE,

    /**
     * "apaga isso": apagar sem dizer o nome. Não dá para saber QUAL tarefa — e escolher no
     * chute é o pior desfecho possível. Reconhecido, não executado.
     */
    ERASE,
}

/**
 * Decide se a fala é um recado novo ([SpeechIntent.Capture]) ou uma intenção de comando.
 *
 * Conservador de propósito: toda forma ambígua cai em [SpeechIntent.Capture]. Criar uma tarefa
 * a mais é menos grave que engolir uma tarefa que ela queria — e a camada de comando não pode
 * roubar frases de captura legítimas. Cada gatilho abaixo está ancorado numa forma que só
 * existe como comando, com o falso positivo que ele evita anotado ao lado.
 */
object SpeechIntentClassifier {
    fun classify(text: String): SpeechIntent {
        val folded = TextNormalizer.compactSpaces(TextNormalizer.fold(text))
        if (folded.isEmpty()) return SpeechIntent.Capture
        return ask(folded)
            ?: complete(folded)
            ?: cancel(folded)
            ?: unknown(folded)
            ?: SpeechIntent.Capture
    }

    // --- perguntar -------------------------------------------------------------------

    private val oQue = Regex("\\bo que\\b")
    private val tenhoOuTem = Regex("\\b(tenho|tem|ha)\\b")

    // A pergunta só é sobre a agenda quando nomeia o dia ou o que se mostra ("marcado",
    // "compromissos", "tarefas", "a fazer"). Sem esta pista, "o que tem de novo" viraria
    // pergunta de agenda — e não é.
    private val agendaCue = Regex(
        "\\b(hoje|amanha|marcado|marcada|marcados|marcadas|agendado|agendada|agendados|" +
            "agendadas|agenda|compromisso|compromissos|tarefa|tarefas)\\b" +
            "|\\b(pra|para|por) fazer\\b",
    )

    // "quais os compromissos de hoje?", "qual a tarefa de amanhã?": a pergunta pelo
    // substantivo, sem "o que" nem "tenho".
    private val quaisAgenda = Regex(
        "\\b(quais|qual)\\b.*\\b(compromisso|compromissos|tarefa|tarefas|agenda)\\b",
    )

    // "me mostra/me diz/me fala" é dirigido a quem responde: pedido de informação, não tarefa.
    private val meMostra = Regex(
        "\\b(me mostra|me diga|me diz|me fala|me fale)\\b.*" +
            "\\b(agenda|compromisso|compromissos|tarefa|tarefas|dia)\\b",
    )

    // "tem algo hoje?": a pergunta pelo indefinido, e o dia é obrigatório. Sem o dia,
    // "tenho algo marcado com o dentista" — afirmação — viraria pergunta e a tarefa se
    // perderia; o "algo" sozinho não é pergunta.
    private val temAlgo = Regex(
        "\\b(tenho|tem|ha)\\s+(algo|alguma coisa|algum compromisso|alguma tarefa)\\b.*\\b(hoje|amanha)\\b",
    )

    private val amanha = Regex("\\bamanha\\b")

    private fun ask(folded: String): SpeechIntent? {
        val pergunta = (oQue.containsMatchIn(folded) &&
            tenhoOuTem.containsMatchIn(folded) &&
            agendaCue.containsMatchIn(folded)) ||
            quaisAgenda.containsMatchIn(folded) ||
            meMostra.containsMatchIn(folded) ||
            temAlgo.containsMatchIn(folded)
        if (!pergunta) return null
        // Só hoje e amanhã: são as duas janelas que a home mostra. "depois de amanhã" contém
        // "amanhã" e cai em amanhã — limitação conhecida e preferível a inventar uma terceira
        // janela; a resposta nomeia o dia, então o erro é visível, não silencioso.
        return SpeechIntent.Ask(if (amanha.containsMatchIn(folded)) AskWhen.TOMORROW else AskWhen.TODAY)
    }

    // --- concluir --------------------------------------------------------------------

    // "já <verbo>" é uma afirmação sobre o que ela JÁ fez, não um título de tarefa: ninguém
    // cadastra "Já tomei". O "já" sozinho não basta (é conjunção em "já que..."), então o
    // gatilho exige um verbo de ação em primeira pessoa logo depois.
    private val jaFiz = Regex(
        "\\bja\\s+(tomei|fiz|comi|bebi|lavei|paguei|liguei|fui|mandei|enviei|comprei|marquei|" +
            "resolvi|terminei|acabei|entreguei|recebi|peguei|retirei|cumpri|comecei|usei|dei|" +
            "li|escrevi|assinei|confirmei|agendei|guardei|arrumei|limpei|troquei|consertei|" +
            "levei|busquei|visitei|avisei|respondi|imprimi|atualizei|baixei|instalei|reinicei)\\b",
    )

    // "conclui/concluí o médico": imperativo (ou passado) dirigido ao app. A forma infinitiva
    // ("concluir a faculdade") fica de fora de propósito — é uma captura legítima, e ancorar
    // nela engoliria a tarefa.
    private val conclui = Regex("\\b(conclui|marca como feito|marcar como feito)\\b")

    private fun complete(folded: String): SpeechIntent? {
        val hit = jaFiz.find(folded) ?: conclui.find(folded) ?: return null
        return SpeechIntent.Complete(targetAfter(folded, hit.range.last + 1))
    }

    // --- cancelar --------------------------------------------------------------------

    // "cancela/cancele o médico": imperativo dirigido ao app. A forma infinitiva ("cancelar a
    // consulta") fica de fora: é captura legítima ("me lembra de cancelar...") e viraria um
    // cancelamento que ela não pediu.
    private val cancela = Regex("\\b(cancela|cancele|desmarca|desmarque)\\b")

    private fun cancel(folded: String): SpeechIntent? {
        val hit = cancela.find(folded) ?: return null
        return SpeechIntent.Cancel(targetAfter(folded, hit.range.last + 1))
    }

    // --- mudar (reconhecido, ainda não executado) ------------------------------------

    // "muda pra quinta", "adia o médico", "remarca a consulta": mudar uma tarefa que já
    // existe. O app ainda não sabe fazer isso, e responder "não sei" é melhor que criar a
    // tarefa "Muda pra" — que é o que acontecia.
    //
    // A âncora é o imperativo seguido de preposição ou pronome: as formas infinitivas
    // ("mudar o óleo", "adiar a reunião") são tarefas legítimas e continuam sendo captura.
    // "muda" aceita só preposição/pronome (não artigo) porque "muda o óleo do carro" é uma
    // tarefa de verdade — e continuaria captura.
    private val muda = Regex("\\bmuda\\s+(pra|para|isso|isto|ele|ela)\\b")
    private val adia = Regex("\\b(adia|remarca)\\s+(pra|para|isso|isto|ele|ela|o|a|os|as)\\b")

    // "apaga isso", "exclui isso": apagar apontando para algo que só ela vê na tela. O app
    // não sabe QUAL tarefa, e escolher no chute é o pior desfecho — reconhece e não executa.
    // A âncora é o verbo + o demonstrativo: "apaga a luz" (sem "isso") continua sendo captura.
    private val apagaIsso = Regex("\\b(apaga|apague|exclui|exclua|deleta|delete)\\s+(isso|isto|essa|esse)\\b")

    private fun unknown(folded: String): SpeechIntent? = when {
        muda.containsMatchIn(folded) || adia.containsMatchIn(folded) ->
            SpeechIntent.Unknown(UnsupportedKind.CHANGE)
        apagaIsso.containsMatchIn(folded) -> SpeechIntent.Unknown(UnsupportedKind.ERASE)
        else -> null
    }

    // --- alvo ------------------------------------------------------------------------

    /** O resto da frase depois do gatilho, sem o artigo que abre o alvo ("o médico" → "médico"). */
    private fun targetAfter(folded: String, from: Int): String =
        stripLeadingArticles(folded.substring(from).trim())

    private fun stripLeadingArticles(text: String): String =
        text.replaceFirst(Regex("^(o|a|os|as|um|uma|meu|minha|meus|minhas)\\s+"), "").trim()
}

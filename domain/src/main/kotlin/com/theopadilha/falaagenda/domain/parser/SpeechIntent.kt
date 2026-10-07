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

    /**
     * "apaga o remédio": apagar o que casa com [target]. Diferente de [Cancel], o verbo
     * ("apaga", "exclui", "deleta", "tira", "remove") também nomeia uma TAREFA de verdade —
     * "apaga a luz", "tira o lixo", "remove a sujeira" —, e quem decide se a fala é comando
     * ou recado é a agenda: com alvo, apaga; sem alvo, é captura. O classificador é puro e
     * não tem a agenda, então ele só reconhece o alvo e deixa o desfecho para quem a tem
     * (ver `HomeViewModel.understandSpeech`).
     */
    data class EraseNamed(val target: String) : SpeechIntent

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

    /**
     * "cancela o médico, não, o dentista": ela se corrigiu no meio da fala, e o alvo ficou
     * ambíguo.
     *
     * O que vem depois do conector pode ser a tarefa ("o dentista"), o dia ("hoje"), a hora
     * ("às três") ou nada — e o classificador é puro, sem a agenda na mão, então não tem como
     * decidir. Agir sobre o alvo que ela DESCARTou é o pior desfecho deste aplicativo: no
     * remédio, concluir o alvo errado é dose errada registrada; no cancelamento, apaga a tarefa
     * errada e a certa fica. Reconhecido, não executado.
     */
    CORRECTION,
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
            // O "reconhecer sem executar" vem ANTES de cancelar: "cancela isso" não nomeia
            // alvo nenhum e tem de cair no caminho do ERASE, não num cancelamento sem nome
            // (que procuraria a tarefa "isso" e não acharia).
            ?: unknown(folded)
            ?: eraseNamed(folded)
            ?: cancel(folded)
            ?: SpeechIntent.Capture
    }

    /**
     * O que ela disse DEPOIS de se corrigir.
     *
     * Ela fala, percebe que errou e se corrige sem parar de falar: "cancela o médico, não, o
     * dentista". O classificador agia sobre o alvo que ela DESCARTou — `Cancel(target=medico)` —
     * e o app apagava o médico; em **275 de 300** casos do catálogo isso aconteceu
     * (`cacada-fala-2026-10-07-correcao.md`, seção P1). No `Complete` o dano é maior: concluir o
     * remédio errado é dose errada registrada.
     *
     * Um conector de correção é uma fronteira: o que vem ANTES dele foi descartado por ela, e o
     * alvo que o app pode usar é o de DEPOIS. O classificador não decide o desfecho sozinho
     * porque o que vem depois pode ser a tarefa ("o dentista"), o dia ("hoje"), a hora ("às
     * três") ou nada — e ele é puro, sem a agenda na mão. Medido no oráculo: em **405 de 810**
     * casos do espaço `verbo × conector × alvo` o corrigido é um alvo de tarefa de verdade e
     * nos outros 405 é um dia, uma hora ou nada. Metade não decide, e escolher no chute é o pior
     * desfecho — o app prefere escalar a adivinhar (ver a doutrina de `HybridParser`: "duas
     * expressões de tempo discordam ⇒ escala"). Por isso o desfecho é o mesmo do "apaga isso":
     * reconhecer e não executar.
     *
     * O gatilho é o conector, não o verbo: sem conector nada muda (a frase sem correção continua
     * exatamente como hoje), e uma frase que nunca foi comando — "me lembra de comprar pão, não,
     * leite" — continua sendo captura, porque a checagem só acontece depois de um gatilho abrir a
     * fala.
     */
    private fun hasCorrection(rest: String): Boolean = CORRECTION_CUE.containsMatchIn(rest)

    /**
     * Os conectores com que ela se corrige, medidos todos no catálogo. O espaço em branco entre
     * as vírgulas é flexível porque o reconhecedor pontua de formas diferentes — a mesma fala
     * chega ", não,", ", nao" e "nao," —, e um conector que escapasse deixaria a frase agir sobre
     * o alvo descartado, calada.
     *
     * As alternativas que não têm vírgula (o "melhor", o "errei", o "digo") podem abrir o
     * conector ou fechar a primeira vírgula: em `, melhor, ` o motor casa "melhor" no ponto em
     * que o `, ` seguinte já foi consumido, e a âncora `(^|,)` não vale ali. Por isso cada
     * alternativa é cercada por `(^|[\s,])` e `([\s,]|$)` — nenhuma delas é palavra de conteúdo
     * de tarefa (ver a prova no oráculo: as capturas legítimas "muda o óleo do carro", "troca a
     * lâmpada da sala" e "me lembra de trocar o remédio" continuam sendo captura).
     */
    private val CORRECTION_CUE = Regex(
        "(^|[\\s,])(nao|quer dizer|digo|na verdade|melhor|errei|ao inves disso|em vez disso)" +
            "([\\s,]|$)",
    )

    /**
     * O preâmbulo que pode anteceder o comando: a interjeição e a cortesia com que ela começa
     * a falar espontaneamente ("ah, cancela o médico", "por gentileza, cancela o médico").
     *
     * É o único material tolerado ANTES do gatilho — o resto da fala antes dele muda o sentido
     * da frase. A lista é generosa de propósito: uma senhora falando não começa pelo verbo, e
     * com a lista estreita a fala legítima caía em captura e virava a tarefa "Ah, cancela
     * médico" — exatamente o defeito que a camada existe para consertar.
     */
    private val FILLER_PREFIX = Regex(
        "^(por favor|por gentileza|por obsequio|gentileza|favor|" +
            "pode|poderia|podias|consegue|consegues|" +
            "ve pra mim|ve pra|ve|veja|olha|olhe|escuta|escute|" +
            // "eu" e "hoje" abrem a fala mais natural de uma rotina — "eu já tomei o remédio",
            // "hoje já tomei o remédio" — e sem eles a âncora de "já <verbo>" não via o gatilho:
            // a frase virava a tarefa "Eu já tomei o remédio" e a dose seguia pendente. São
            // sujeito e circunstância, não conteúdo: o que a tarefa faria com eles seria ruído.
            "eu|hoje|" +
            "ah|bom|bem|entao|ei|oi|ola|opa|e|eh)\\b[\\s,!.]+",
    )

    /** A fala sem o preâmbulo de cortesia, que é onde a âncora do gatilho olha. */
    private fun withoutFiller(folded: String): String {
        var rest = folded
        while (true) {
            rest = rest.trimStart(' ', ',', '.', '!', '?', ';', ':')
            val prefix = FILLER_PREFIX.find(rest) ?: return rest
            rest = rest.substring(prefix.range.last + 1)
        }
    }

    /**
     * O gatilho só vale ABRINDO a fala (ou logo depois do preâmbulo de cortesia).
     *
     * A mesma palavra no meio da frase é o verbo de outra oração — "perguntar se a médica
     * cancela a consulta", "checar se já paguei o aluguel" —, e ali não é um pedido dirigido
     * ao app. Sem esta âncora, essas frases viravam comando e o app apagava ou concluía uma
     * tarefa de verdade: o pior desfecho deste aplicativo.
     */
    private fun opensWith(regex: Regex, rest: String): MatchResult? =
        regex.find(rest)?.takeIf { it.range.first == 0 }

    // --- perguntar -------------------------------------------------------------------

    // "o que tenho/tem ... na agenda": o "o que ... tenho/tem" tem de ABRIR a fala. No meio
    // ("comprar o que tem na lista") ele é o objeto da tarefa, não uma pergunta — e sem a
    // âncora a frase virava pergunta e a tarefa nunca nascia.
    private val oQueTenho = Regex("\\bo que\\b.*\\b(tenho|tem|ha)\\b")

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

    // "tem algo hoje?": a pergunta pelo indefinido, ABRINDO a fala, com o dia logo depois do
    // "algo". As duas restrições são necessárias e nenhuma sozinha basta: "tenho algo marcado
    // com o dentista amanhã" também começa com "tenho algo" — é a tarefa, não a pergunta —, e
    // o dia tem de vir colado ao indefinido (no máximo um "pra/para/de"), não no fim de
    // qualquer frase.
    private val temAlgo = Regex(
        "\\b(tenho|tem|ha)\\s+(algo|alguma coisa|algum compromisso|alguma tarefa)" +
            "\\s+((pra|para|de)\\s+)?(hoje|amanha)\\b",
    )

    private val amanha = Regex("\\bamanha\\b")

    private fun ask(folded: String): SpeechIntent? {
        val rest = withoutFiller(folded)
        val pergunta =
            // "o que tenho hoje?": abre a fala (ou vem logo após "por favor") e nomeia o
            // dia/agenda.
            (opensWith(oQueTenho, rest) != null && agendaCue.containsMatchIn(folded)) ||
                // "quais os compromissos de hoje?" / "me mostra a agenda": a pergunta pelo
                // substantivo ou pelo pedido dirigido a quem responde — ABRINDO a fala. No
                // meio ("comprar qual tarefa está faltando", "pedir pra ela me fala o dia")
                // é o objeto da tarefa, não uma pergunta.
                opensWith(quaisAgenda, rest) != null ||
                opensWith(meMostra, rest) != null ||
                // "tem algo amanhã?": a pergunta pelo indefinido, ABRINDO a fala, e o dia é
                // obrigatório. No meio ("tenho algo marcado com o dentista amanhã") é uma
                // afirmação sobre a tarefa — e sem a âncora ela viraria pergunta e a tarefa
                // nunca nasceria.
                opensWith(temAlgo, rest) != null
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
    //
    // O "conclui" nu fica de fora pelo mesmo motivo: no presente ele é idêntico ao imperativo
    // ("ela conclui", "conclui a faculdade em dezembro") e não dá para distinguir pela forma.
    // Só o "marca como feito", que não tem outro sentido, vale — na dúvida, captura.
    private val conclui = Regex("\\b(marca como feito|marcar como feito)\\b")

    private fun complete(folded: String): SpeechIntent? {
        val rest = withoutFiller(folded)
        val hit = opensWith(jaFiz, rest) ?: opensWith(conclui, rest) ?: return null
        if (hasCorrection(rest.substring(hit.range.last + 1))) {
            return SpeechIntent.Unknown(UnsupportedKind.CORRECTION)
        }
        return SpeechIntent.Complete(targetAfter(rest, hit.range.last + 1))
    }

    // --- cancelar --------------------------------------------------------------------

    // "cancela/cancele o médico": imperativo dirigido ao app, ABRINDO a fala. A forma
    // infinitiva ("cancelar a consulta") fica de fora: é captura legítima ("me lembra de
    // cancelar...") e viraria um cancelamento que ela não pediu. E a forma no meio da frase
    // ("perguntar se a médica cancela a consulta", "a cancela do estacionamento") também —
    // ali é o verbo de outra oração ou o substantivo, e tratá-la como comando apagava uma
    // tarefa de verdade.
    private val cancela = Regex("\\b(cancela|cancele|desmarca|desmarque)\\b")

    private fun cancel(folded: String): SpeechIntent? {
        val rest = withoutFiller(folded)
        val hit = opensWith(cancela, rest) ?: return null
        if (hasCorrection(rest.substring(hit.range.last + 1))) {
            return SpeechIntent.Unknown(UnsupportedKind.CORRECTION)
        }
        return SpeechIntent.Cancel(targetAfter(rest, hit.range.last + 1))
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
    //
    // "cancela isso" entra aqui também: é apagar sem dizer o nome. Sem esta linha ele caía no
    // cancelamento com o alvo literal "isso" e respondia "não achei nenhuma tarefa com esse
    // nome" — quando o certo é dizer que ainda não sabe apagar falando.
    //
    // Só "isso/isto" aqui. "essa/esse" não cabem numa regex que olha a forma da frase: o que
    // separa "apaga essa" (apagar no escuro) de "apaga ESSA consulta" (o demonstrativo NOMEIA o
    // alvo) é ter ou não um substantivo DEPOIS, e o reconhecedor gruda pontuação e cortesia
    // nesse fim — "apaga essa." e "apaga essa, por favor" escapavam de qualquer âncora de fim
    // de string e voltavam para a captura, criando a tarefa "Apaga essa". A decisão é do alvo
    // já limpo, em [eraseNamed] (ver [DEMONSTRATIVOS]).
    private val apagaIsso = Regex(
        "\\b(apaga|apague|exclui|exclua|deleta|delete)\\s+(isso|isto)\\b",
    )

    /**
     * A fonte ÚNICA dos determinantes que abrem um alvo: o artigo ("o médico" → "médico") e o
     * demonstrativo ("essa consulta" → "consulta").
     *
     * As duas pontas que precisam da mesma lista — [stripLeadingArticles], que a usa para limpar
     * o alvo, e [DEMONSTRATIVOS], que decide se o que sobrou é um alvo sem nome — liam de listas
     * escritas à mão, e elas divergiram: a limpeza conhecia o plural e a família
     * `este/esta/aquele`, o veredito só o singular. "apaga essa" era reconhecido sem executar e
     * "apaga essas" virava a tarefa "Apaga essas", calado — a mesma classe do P2-A. Uma lista só
     * é o que impede a divergência de voltar.
     *
     * Os determinantes de DATA ("este sábado", "esta semana", "aquele dia") NÃO são alvo: eles
     * vêm com substantivo depois ("este sábado" → "sábado"), então nunca chegam sozinhos ao
     * veredito de [DEMONSTRATIVOS] — que só olha o alvo quando ele é a última coisa dita.
     */
    private val DETERMINANTES = setOf(
        "o", "a", "os", "as", "um", "uma", "meu", "minha", "meus", "minhas",
        "este", "esta", "estes", "estas", "esse", "essa", "esses", "essas",
        "aquele", "aquela", "aqueles", "aquelas",
    )

    /**
     * O determinante sozinho não nomeia alvo nenhum: "apaga essa" aponta para algo que só ela
     * vê na tela, e escolher no chute é o pior desfecho. O veredito é tomado sobre o alvo já
     * limpo — depois da pontuação que o reconhecedor gruda e da cortesia que fecha a fala —,
     * porque é isso que sobra dito: "apaga essa." e "apaga essa, por favor" são o mesmo pedido
     * que "apaga isso", e o caminho é o mesmo (reconhecer e não executar).
     *
     * Derivado de [DETERMINANTES] — a mesma fonte que limpa o alvo —, e não uma enumeração
     * paralela: era um `setOf("essa", "esse", ...)` à mão, cobria só o singular e divergia da
     * outra lista. Ver [DETERMINANTES].
     *
     * "isso/isto" entram à parte: eles não são determinantes de alvo (não têm substantivo
     * depois) e por isso ficam fora de [DETERMINANTES], mas continuam sendo um alvo sem nome.
     * O ramo [apagaIsso] pega os verbos que ele conhece; os outros de [apagaNomeado]
     * ("tira isso", "remove isso") chegam aqui e têm de cair no mesmo veredito.
     */
    private val DEMONSTRATIVOS: Set<String> = DETERMINANTES + setOf("isso", "isto")

    // Só os pronomes "isso/isto": eles não têm substantivo depois, então não nomeiam alvo
    // nenhum. "cancela essa consulta" fica de fora de propósito — "essa consulta" É o alvo, e
    // tratá-la como ERASE perderia um cancelamento que o app sabe fazer.
    private val cancelaIsso = Regex("\\b(cancela|cancele|desmarca|desmarque)\\s+(isso|isto)\\b")

    private fun unknown(folded: String): SpeechIntent? {
        val rest = withoutFiller(folded)
        return when {
            opensWith(muda, rest) != null || opensWith(adia, rest) != null ->
                SpeechIntent.Unknown(UnsupportedKind.CHANGE)
            opensWith(apagaIsso, rest) != null || opensWith(cancelaIsso, rest) != null ->
                SpeechIntent.Unknown(UnsupportedKind.ERASE)
            else -> null
        }
    }

    // --- apagar pelo nome (o alvo decide) --------------------------------------------

    // "apaga o remédio": imperativo dirigido ao app, ABRINDO a fala, com um alvo NOMEADO. O
    // verbo aqui é ambíguo de propósito — "apaga a luz" e "tira o lixo" são tarefas de
    // verdade, e não há nada na FORMA da frase que separe um caso do outro. Quem separa é a
    // agenda: com um alvo que casa, é comando; sem alvo, é recado. Por isso o classificador
    // (puro) só devolve o alvo, e a decisão fica com quem tem a agenda (ver
    // `SpeechIntent.EraseNamed` e `HomeViewModel.understandSpeech`).
    //
    // O infinitivo fica de fora, como no [cancela]: "me lembra de apagar a luz" é captura.
    private val apagaNomeado = Regex("\\b(apaga|apague|exclui|exclua|deleta|delete|tira|tire|remove|remova)\\b")

    // O demonstrativo já foi tratado em [unknown] (ERASE sem nome) — aqui só o que NOMEIA o
    // alvo. O artigo e o resto da frase são do [targetAfter], que também tira a cortesia.
    private fun eraseNamed(folded: String): SpeechIntent? {
        val rest = withoutFiller(folded)
        val hit = opensWith(apagaNomeado, rest) ?: return null
        val target = targetAfter(rest, hit.range.last + 1)
        // Sem alvo ("apaga", "deleta") não há o que casar: é captura, como sempre foi.
        if (target.isEmpty()) return null
        // "apaga o remédio, não, o de pressão": o terceiro caminho destrutivo. Quem decide o
        // desfecho aqui é a agenda (ver [SpeechIntent.EraseNamed]), e com o alvo descartado ela
        // apagaria a tarefa errada — a mesma classe de dano do [cancel] e do [complete].
        if (hasCorrection(folded.substring(hit.range.last + 1))) {
            return SpeechIntent.Unknown(UnsupportedKind.CORRECTION)
        }
        // Um demonstrativo sozinho não nomeia nada — é o "apaga isso" escrito de outro jeito, e
        // o desfecho é o mesmo: reconhecer e não executar, nunca procurar uma tarefa "essa".
        if (target in DEMONSTRATIVOS) return SpeechIntent.Unknown(UnsupportedKind.ERASE)
        return SpeechIntent.EraseNamed(target)
    }

    // --- alvo ------------------------------------------------------------------------

    /**
     * O resto da frase depois do gatilho, sem o artigo que abre o alvo ("o médico" → "médico") e
     * sem a pontuação que o reconhecedor costuma grudar na palavra.
     *
     * "já tomei o remédio." devolvia o alvo `remedio.` e a matcher — que compara palavra com
     * palavra — não achava nada: a rotina do remédio seguia pendente e ela achava que tinha
     * registrado. "cancela o médico, por favor" era pior: a vírgula colava na palavra e o alvo
     * virava `medico, por favor`. O `LocalTaskParser` já tira essa pontuação do título, então o
     * app já assume que a fala chega pontuada — a camada nova é que não estava tirando.
     *
     * A pontuação sai das duas pontas, e a vírgula também do meio: "médico, por favor" é um alvo
     * com a cortesia no rabo, não um nome de tarefa. O `?` fica de fora do corte das pontas
     * apenas por simetria com o resto do app; ele não aparece no meio de um alvo de comando.
     *
     * A cortesia SEM vírgula ("cancela o médico por favor") não é cortada por nenhuma dessas
     * pontuações: ela entrava como palavra significativa do alvo e a maioria estrita devolvia
     * `None`. [stripTrailingCourtesy] tira esse rabo — e ANTES do artigo, porque o artigo pode
     * ser justamente o que separa o alvo da cortesia: em "apaga essa por favor" o "essa " saía
     * como artigo, o alvo sobrava começando em "por favor" e o rabo — sem espaço à frente, já
     * que a cortesia ficou no começo da string — era cortado no meio (" favor"), deixando o alvo
     * `por`. Tirando o rabo primeiro, o alvo é só o demonstrativo, que é o que ela disse.
     */
    private fun targetAfter(folded: String, from: Int): String =
        stripLeadingArticles(
            stripTrailingCourtesy(
                folded.substring(from).trim().trim(',', '.', '!', '?', ';', ':', ' ').substringBefore(',').trim(),
            ),
        )

    /**
     * O rabo de cortesia que fecha a fala ("... por favor", "... obrigada") e não faz parte do
     * alvo. Sem a vírgula que [targetAfter] já corta, essas palavras viravam significativas e
     * derrubavam o casamento por maioria: "cancela o médico por favor" (1 de 3) respondia "Não
     * achei nenhuma tarefa com esse nome" — a fala mais provável dela falhando calada.
     *
     * "sim", "ok", "beleza" e "tá" entram pelo mesmo motivo: confirmam o pedido, não nomeiam a
     * tarefa. Só o rabo é cortado — a cortesia no meio do alvo não é tocada.
     *
     * O início do rabo é `(^|\s+)`, e não `\s+`: depois de o artigo sair, a cortesia pode ficar
     * colada no começo do alvo ("apaga essa por favor" → "por favor"), e aí um `\s+` obrigatório
     * fazia o motor casar a alternativa CURTA no meio — " favor" — e devolver `por`.
     *
     * Quem segura [demonstrativoComCortesiaSemVirgulaNaoViraAlvo] é a ORDEM — a cortesia antes do
     * artigo —, não esta âncora: trocar `(^|\s+)` por `\s+` mantendo a ordem deixa a suíte verde
     * (as duas metades são redundantes aqui). Não remova a âncora achando que ela é o que
     * protege; o teste é da ordem.
     */
    private val TRAILING_COURTESY = Regex(
        "(^|\\s+)(por favor|por gentileza|favor|obrigada|obrigado|sim|ok|beleza|ta)\\s*$",
    )

    private fun stripTrailingCourtesy(text: String): String =
        text.replace(TRAILING_COURTESY, "").trim()

    /**
     * O determinante que abre o alvo e não é parte do nome: o artigo ("o médico" → "médico") e o
     * demonstrativo ("essa consulta" → "consulta"). Sem o demonstrativo aqui, "apaga essa
     * consulta" deixava o alvo `essa consulta`, e o `essa` — que não casa título nenhum —
     * derrubava a maioria estrita: a tarefa existia e o app dizia que não achou.
     *
     * "isso/isto" ficam de fora de propósito: eles NÃO têm substantivo depois, então o alvo
     * seria vazio — e o caminho certo para eles é o ERASE sem nome, não um alvo limpo.
     *
     * A alternação sai de [DETERMINANTES] — a mesma lista que decide o alvo sem nome —, do mais
     * longo para o mais curto: com "a" antes de "as", o motor casaria o prefixo e o `\s+`
     * seguinte falharia, e ainda que o retrocesso resolvesse, a ordem explícita deixa a
     * intenção legível.
     */
    private val LEADING_DETERMINERS = Regex(
        "^(" + DETERMINANTES.sortedByDescending { it.length }.joinToString("|") + ")\\s+",
    )

    private fun stripLeadingArticles(text: String): String =
        text.replaceFirst(LEADING_DETERMINERS, "").trim()
}

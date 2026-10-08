package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * O corpus da correção de data, gerado por FORMA GRAMATICAL — não por frases que soam naturais
 * para quem escreveu a lista de conectores.
 *
 * O #83 foi reprovado três vezes pelo mesmo vício: o corpus saía da mesma intuição do código, e
 * ficava verde com o defeito vivo. A pergunta que este arquivo faz é a inversa: **que forma esta
 * lista NÃO cobre?** Por isso as falas são o produto cartesiano de
 *
 *     prefixo × valor-descartado × conector × valor-corrigido
 *
 * e as formas do conector entram por CLASSE gramatical, não como exemplo solto: com vírgula dos
 * dois lados, só antes, só depois, colada sem vírgula nenhuma, e com o sujeito explícito antes da
 * correção. Os conectores que a lista do código não conhecia ("só que", "ou melhor", "ou seja",
 * "isto é", "espera") entram como instância, e a classe é que é exigida.
 *
 * O ORÁCULO É INDEPENDENTE. O esperado não sai de rodar o parser numa "frase de referência" —
 * isso seria o parser conferindo a si mesmo. Sai da doutrina do produto escrita à mão, na tabela
 * [DIA] / [HORA]: *ela disse o valor corrigido, então é o valor corrigido que vale*. A
 * aritmética do calendário está resolvida abaixo, à mão, com o relógio fixo declarado.
 *
 * Relógio fixo: **2026-08-20 10:00 America/Sao_Paulo**, uma quinta-feira.
 *
 * E o OUTRO LADO mora aqui, porque um corpus de um lado só é o espelho que a própria doc do PR
 * condena: [aContinuacaoComValorNaCaudaNaoViraCorrecao] percorre as MESMAS formas com uma cauda
 * de continuação ("não tomei hoje"), onde o dia dito antes do conector tem de sobreviver. É esse
 * lado que o critério de lista de verbos derrubava, e é ele que a correção não pode reconquistar
 * por acidente.
 */
class LocalTaskParserCorrecaoDeDataCorpusTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    /**
     * O que ela diz → o que vale, escrito à mão. A coluna da direita é a doutrina: "ela disse
     * hoje, então é hoje".
     */
    private val DIA = listOf(
        "hoje" to LocalDate.of(2026, 8, 20),
        "amanha" to LocalDate.of(2026, 8, 21),
        "depois de amanha" to LocalDate.of(2026, 8, 22),
        "sabado" to LocalDate.of(2026, 8, 22),
        "domingo" to LocalDate.of(2026, 8, 23),
        "segunda" to LocalDate.of(2026, 8, 24),
        "terca" to LocalDate.of(2026, 8, 25),
        "quarta" to LocalDate.of(2026, 8, 26),
        "semana que vem" to LocalDate.of(2026, 8, 27),
        "dia 25" to LocalDate.of(2026, 8, 25),
        "dia 28" to LocalDate.of(2026, 8, 28),
    )

    private val HORA = listOf(
        "as oito" to LocalTime.of(8, 0),
        "as 9" to LocalTime.of(9, 0),
        "as dez" to LocalTime.of(10, 0),
        "meio-dia" to LocalTime.of(12, 0),
        "as tres" to LocalTime.of(3, 0),
        "as nove e meia" to LocalTime.of(9, 30),
    )

    /** Os prefixos: sujeito implícito, sujeito explícito, verbo de anotação. */
    private val PREFIXOS = listOf(
        "me lembra de tomar remedio ",
        "anota tomar remedio ",
        "tomar remedio ",
        "eu preciso tomar remedio ",
    )

    /**
     * As CLASSES de forma do conector — a doutrina, escrita à mão. Cada classe é uma forma
     * gramatical distinta da correção falada, e a lista de instâncias logo abaixo é que dá corpo
     * a ela. O teste de cobertura pergunta por CLASSE, e não pela instância: uma classe sem
     * nenhuma forma no corpus é o que aparece nomeado em vez de passar em silêncio.
     */
    private enum class ClasseDaForma {
        /** O `não` com vírgula dos dois lados, a forma canônica da correção falada. */
        NAO_VIRGULA_DUPLA,

        /** O `não` com a vírgula só ANTES ("amanhã, não hoje") — a que escapou da 1ª versão. */
        NAO_VIRGULA_ANTES,

        /** O `não` com a vírgula só DEPOIS ("amanhã não, hoje"). */
        NAO_VIRGULA_DEPOIS,

        /**
         * O `não` COLADO, sem vírgula nenhuma ("amanhã não hoje"). É a forma que o reconhecedor
         * entrega quando não pontua, e o teclado entrega sempre — `WriteTaskScreen` manda o texto
         * cru para o mesmo `understandSpeech`.
         */
        NAO_SEM_VIRGULA,

        /** A reformulação explícita ("quer dizer", "digo", "na verdade", "errei"). */
        REFORMULACAO,

        /** A reformulação que o código não conhecia ("só que", "ou melhor", "ou seja", "isto é"). */
        REFORMULACAO_NAO_LISTADA,

        /**
         * O sujeito explícito antes da correção ("não, eu quero hoje").
         *
         * Esta classe é RESIDUAL DECLARADO, e não uma classe coberta: a correção com a moldura
         * verbal depois do "não" tem, na FORMA da frase, o mesmo desenho da continuação
         * ("não, **eu quero** o de diabetes" × "não, **eu quero** descansar"), e separar as duas
         * pede semântica, não forma. É a mesma assimetria que o `SpeechIntentClassifier` já
         * declara no eixo do alvo (F-C e o residual da forma aberta): lá o desfecho é escalar; aqui
         * é o `nao` não virar fronteira, e o dia dito antes continua valendo — o lado SEGURO, que
         * é o que o corpus de continuação prende.
         *
         * O custo é medido em [oResidualDaMolduraVerbalFicaDeclarado]: ela corrige "não, eu quero
         * hoje" e o app mantém o dia anterior, na tela de confirmação — um toque a mais para ela,
         * contra o app cravar o dia errado.
         */
        SUJEITO_EXPLICITO,
    }

    /**
     * As formas de cada classe. O invariante de montagem: **toda forma termina com espaço**, e o
     * produto abaixo concatena o valor corrigido direto — sem isso a última palavra colaria na
     * primeira do valor ("amanhã, naohoje") e o caso não testaria nada.
     */
    private val FORMAS_POR_CLASSE: Map<ClasseDaForma, List<String>> = mapOf(
        ClasseDaForma.NAO_VIRGULA_DUPLA to listOf(", nao, "),
        ClasseDaForma.NAO_VIRGULA_ANTES to listOf(", nao "),
        ClasseDaForma.NAO_VIRGULA_DEPOIS to listOf(" nao, "),
        ClasseDaForma.NAO_SEM_VIRGULA to listOf(" nao "),
        ClasseDaForma.REFORMULACAO to listOf(
            ", quer dizer, ", ", quer dizer ", ", digo, ", ", na verdade, ", ", melhor, ",
            ", errei, ", ", em vez disso, ", ", ao inves disso, ",
        ),
        ClasseDaForma.REFORMULACAO_NAO_LISTADA to listOf(
            ", so que ", ", ou melhor, ", ", ou seja, ", ", isto e, ", " espera ",
        ),
        ClasseDaForma.SUJEITO_EXPLICITO to listOf(", nao, eu quero ", ", nao, eu queria "),
    )

    /** As classes que a doutrina exige cobertas — todas menos o residual declarado. */
    private val CLASSES_EXIGIDAS: Set<ClasseDaForma> =
        ClasseDaForma.entries.toSet() - ClasseDaForma.SUJEITO_EXPLICITO

    private val FORMAS: List<String> = FORMAS_POR_CLASSE
        .filterKeys { it in CLASSES_EXIGIDAS }
        .values
        .flatten()

    /**
     * O relatório de cobertura por CLASSE: o defeito não pode voltar pela forma que a lista não
     * cobre. O esperado sai da tabela [DIA] escrita à mão — "amanhã" descartado, "hoje" corrigido,
     * 20/08 —, e a classe que não estiver coberta aparece aqui, nomeada.
     */
    @Test
    fun todaClasseDeFormaDaDoutrinaEstaCobertaPeloParser() {
        val descartado = "amanha"
        val corrigido = "hoje"
        val esperado = LocalDate.of(2026, 8, 20)
        val cobertura = FORMAS_POR_CLASSE.mapValues { (_, formas) ->
            formas.all { forma ->
                parser.parse("me lembra de tomar remedio $descartado$forma$corrigido").localDate == esperado
            }
        }
        println("CORPUS-COBERTURA|$cobertura")
        val descobertas = cobertura.filterValues { !it }.keys
        println("CORPUS-DESCOBERTAS|${descobertas.ifEmpty { listOf("nenhuma") }}")
        assertThat(descobertas).isEqualTo(setOf(ClasseDaForma.SUJEITO_EXPLICITO))
        assertThat(CLASSES_EXIGIDAS.none { cobertura[it] != true }).isTrue()
    }

    /**
     * O residual da moldura verbal, medido e DECLARADO em vez de escondido — a troca é explícita,
     * como no `SpeechIntentCorrecaoCorpusIndependenteTest` do #83.
     *
     * `"amanhã, não, eu quero hoje"` é correção de verdade, e o parser NÃO a pega: o "não" deixa
     * de ser fronteira e o dia dito antes sobrevive (21/08 em vez de 20/08). O desfecho é o lado
     * SEGURO — a tela de confirmação mostra a data antiga e ela corrige com um toque —, contra o
     * app cravar o dia errado com `ambiguous=false`.
     *
     * Fechar isto custaria a família de continuação que abre com a MESMA moldura ("não, eu quero
     * mais", "não, eu queria descansar"), que é fala comum e é o caso clínico do #83. Não é troca
     * limpa: é o mesmo par que o eixo do alvo já declarou, e a decisão é a mesma — declarar.
     */
    private val RESIDUAL_DA_MOLDURA_VERBAL = 2

    @Test
    fun oResidualDaMolduraVerbalFicaDeclarado() {
        val correcoes = listOf(
            "me lembra de tomar remedio amanha, nao, eu quero hoje",
            "me lembra de tomar remedio amanha, nao, eu queria hoje",
        )
        var naoPegou = 0
        correcoes.forEach { fala ->
            if (parser.parse(fala).localDate != LocalDate.of(2026, 8, 20)) naoPegou++
        }
        println("CORPUS-RESIDUAL|casos=${correcoes.size}|naoPegou=$naoPegou")
        assertThat(naoPegou).isEqualTo(RESIDUAL_DA_MOLDURA_VERBAL)
    }

    @Test
    fun aCorrecaoDeDiaValeEmTodaFormaGramatical() {
        var casos = 0
        val violacoes = mutableListOf<String>()
        PREFIXOS.forEach { prefixo ->
            DIA.forEach { (descartado, _) ->
                DIA.forEach { (corrigido, esperado) ->
                    if (descartado == corrigido) return@forEach
                    FORMAS.forEach { forma ->
                        val fala = "$prefixo$descartado$forma$corrigido"
                        casos++
                        val obtido = parser.parse(fala).localDate
                        if (obtido != esperado) {
                            violacoes += "«$fala» descartado=$descartado corrigido=$corrigido " +
                                "esperado=$esperado obtido=$obtido"
                        }
                    }
                }
            }
        }
        println("CORPUS-DIA|casos=$casos|violacoes=${violacoes.size}")
        relatorioPorForma(violacoes)
        violacoes.take(12).forEach { println("CORPUS-VIOL|dia|$it") }
        assertThat(violacoes).isEmpty()
    }

    /**
     * O relatório por FORMA: quantas violações cada classe acumula. É o que separa "o critério
     * está errado" de "uma forma do corpus é artificial" — sem ele, uma violação de forma ruim
     * some no meio do número total.
     */
    private fun relatorioPorForma(violacoes: List<String>) {
        val porForma = FORMAS.associateWith { forma -> violacoes.count { it.contains(forma) } }
        porForma.filterValues { it > 0 }.forEach { (forma, n) -> println("CORPUS-FORMA|«$forma»|viol=$n") }
    }

    @Test
    fun aCorrecaoDeHoraValeEmTodaFormaGramaticalEODiaSobrevive() {
        var casos = 0
        val violacoes = mutableListOf<String>()
        PREFIXOS.forEach { prefixo ->
            HORA.forEach { (descartado, _) ->
                HORA.forEach { (corrigido, esperado) ->
                    if (descartado == corrigido) return@forEach
                    FORMAS.forEach { forma ->
                        val fala = "${prefixo}amanha $descartado$forma$corrigido"
                        casos++
                        val draft = parser.parse(fala)
                        val problemas = buildList {
                            if (draft.localTime != esperado) {
                                add("hora esperado=$esperado obtido=${draft.localTime}")
                            }
                            // O dia que ela NÃO corrigiu não pode sumir junto com a hora trocada.
                            if (draft.localDate != LocalDate.of(2026, 8, 21)) {
                                add("dia esperado=2026-08-21 obtido=${draft.localDate}")
                            }
                        }
                        if (problemas.isNotEmpty()) {
                            violacoes += "«$fala» ${problemas.joinToString("; ")}"
                        }
                    }
                }
            }
        }
        println("CORPUS-HORA|casos=$casos|violacoes=${violacoes.size}")
        violacoes.take(12).forEach { println("CORPUS-VIOL|hora|$it") }
        assertThat(violacoes).isEmpty()
    }

    /**
     * O outro lado da balança, e o que prende o P0: a fala que CONTINUA depois do conector.
     *
     * "não tomei hoje" não volta atrás de nada — ela explica por que o lembrete existe, e o dia
     * dito ANTES do "não" continua valendo. O veredito não pode sair de uma lista de verbos: a
     * lista de 20 verbos de tarefa do `TASK_VERB` deixava de fora toda fala fora dela, e o valor
     * DESCARTADO vencia calado (`ambiguous=false`, caixa rápida confirmando o dia errado em um
     * toque).
     *
     * O oráculo é a doutrina escrita à mão: ela disse "amanhã" antes do conector e não corrigiu o
     * dia depois — então é amanhã, 21/08. O "hoje" da cauda é a razão, e NÃO pode virar a data.
     *
     * Os verbos são de propósito fora do `TASK_VERB` (com "tomei"/"vou" dentro, justamente para
     * provar que a lista não é o critério): 20 verbos × 10 valores × as formas do "não" e da
     * reformulação = 2600 casos, contra as 36 regressões medidas no critério antigo.
     */
    @Test
    fun aContinuacaoComValorNaCaudaNaoViraCorrecao() {
        val verbos = listOf(
            "tomei", "tomo", "deu", "consegui", "pude", "quero", "preciso", "tenho",
            "vou", "sei", "fui", "paguei", "marquei", "liguei", "vi", "soube",
            "achei", "encontrei", "recebi", "lembrei",
        )
        val valores = listOf(
            "hoje", "hoje as oito", "amanha", "ontem", "agora",
            "de manha", "de tarde", "de noite", "mais tarde", "hoje a tarde",
        )
        val violacoes = mutableListOf<String>()
        var casos = 0
        verbos.forEach { verbo ->
            valores.forEach { valor ->
                FORMAS.forEach { forma ->
                    casos++
                    val fala = "me lembra de tomar remedio amanha${forma}nao $verbo $valor"
                    val draft = parser.parse(fala)
                    if (draft.localDate != LocalDate.of(2026, 8, 21)) {
                        violacoes += "«$fala» esperado=2026-08-21 obtido=${draft.localDate} " +
                            "amb=${draft.ambiguous}"
                    }
                }
            }
        }
        println("CORPUS-CONTINUACAO|casos=$casos|violacoes=${violacoes.size}")
        violacoes.take(12).forEach { println("CORPUS-VIOL|continuacao|$it") }
        assertThat(violacoes).isEmpty()
    }

    /**
     * A continuação que NÃO tem valor temporal na cauda — a classe irmã, e a que a fronteira não
     * pode conquistar por acidente ao alargar o vocabulário de conectores. O dia dito antes
     * sobrevive em todas.
     *
     * O caso clínico do #83 está no meio: "não quero mais" e "não tenho como" são fala comum, e
     * uma fronteira que perguntasse "o verbo pode abrir um valor?" as descartaria junto.
     */
    @Test
    fun aContinuacaoSemValorNaCaudaNaoDescartaODia() {
        val caudas = listOf(
            "nao vou poder ir", "nao quero mais", "nao tenho como", "nao preciso mais",
            "nao deu tempo", "nao consigo de manha", "nao posso agora", "nao sei se da",
            "nao adianta", "nao funciona assim",
        )
        val violacoes = mutableListOf<String>()
        var casos = 0
        FORMAS.forEach { forma ->
            caudas.forEach { cauda ->
                casos++
                val fala = "me lembra de tomar remedio amanha${forma}$cauda"
                val draft = parser.parse(fala)
                if (draft.localDate != LocalDate.of(2026, 8, 21)) {
                    violacoes += "«$fala» esperado=2026-08-21 obtido=${draft.localDate}"
                }
            }
        }
        println("CORPUS-CONTINUACAO-SEM-VALOR|casos=$casos|violacoes=${violacoes.size}")
        violacoes.take(12).forEach { println("CORPUS-VIOL|continuacao-sem-valor|$it") }
        assertThat(violacoes).isEmpty()
    }

    /**
     * O outro lado do residual da moldura verbal: a continuação que abre com a MESMA moldura
     * ("não, eu quero mais") tem de continuar sendo continuação. É ela que paga o custo declarado
     * acima — fechar a correção com moldura reabriria justamente esta família.
     */
    @Test
    fun aContinuacaoComMolduraVerbalNaoDescartaODia() {
        val continuacoes = listOf(
            "me lembra de tomar remedio amanha, nao, eu quero mais",
            "me lembra de tomar remedio amanha, nao, eu queria descansar",
            "me lembra de tomar remedio amanha, nao, eu vou poder ir",
        )
        val violacoes = continuacoes.mapNotNull { fala ->
            val obtido = parser.parse(fala).localDate
            if (obtido == LocalDate.of(2026, 8, 21)) null else "«$fala» esperado=2026-08-21 obtido=$obtido"
        }
        println("CORPUS-MOLDURA-CONTINUACAO|casos=${continuacoes.size}|violacoes=${violacoes.size}")
        violacoes.forEach { println("CORPUS-VIOL|moldura-continuacao|$it") }
        assertThat(violacoes).isEmpty()
    }
}

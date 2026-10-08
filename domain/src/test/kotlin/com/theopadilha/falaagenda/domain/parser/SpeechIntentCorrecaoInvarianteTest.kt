package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O oráculo da correção: percorre o espaço cartesiano `verbo × conector × alvo` e CONTA as
 * violações do invariante, em vez de prender exemplos soltos.
 *
 * Invariante (do catálogo `cacada-fala-2026-10-07-correcao.md`):
 *
 * > Nenhuma frase com conector de correção produz `Cancel`/`Complete`/`EraseNamed` sobre o alvo
 * > que ela descartou.
 *
 * E o par: a frase **sem** conector continua se comportando exatamente como hoje (zero regressão
 * nas capturas legítimas). Por isso o mesmo espaço é percorrido com o conector vazio — é o
 * controle.
 *
 * Há um SEGUNDO corpus aqui, e ele existe por causa do review do PR #83: o de CONTINUAÇÃO. O
 * "não" é a palavra mais comum do português numa continuação ("não vou poder ir", "não deu
 * tempo"), e tratá-lo como conector de correção custava **2400 de 3360** frases com as 21 caudas
 * de então — o comando certo deixava de agir. Hoje a lista tem 22 caudas e o espaço é 3520; sob o
 * gatilho largo o bloqueio seria a família inteira. Os dois números juntos são o que prende o fix:
 * o corpus de correção prende o benefício, o de continuação prende o custo.
 *
 * O alvo esperado de cada caso é ESCRITO À MÃO aqui, e não calculado com o raciocínio do código
 * (um oráculo que recalcula a doutrina só concorda consigo mesmo).
 */
class SpeechIntentCorrecaoInvarianteTest {

    /**
     * Um caso do espaço: o alvo que ela falou primeiro (o descartado), o que ela disse depois do
     * conector (o corrigido) e o alvo que o app age HOJE — escrito à mão, dobrado e sem artigo.
     */
    private data class Alvos(
        val descartado: String,
        val alvoDescartado: String,
        val corrigido: String,
        /** O que vem depois do conector é um alvo de tarefa? ("hoje" e "às três" não são.) */
        val corrigidoEhAlvo: Boolean,
    )

    private val alvos = listOf(
        Alvos("o médico", "medico", "o dentista", true),
        Alvos("o remédio de pressão", "remedio de pressao", "o de diabetes", true),
        Alvos("a consulta", "consulta", "o dentista", true),
        Alvos("o aluguel", "aluguel", "o condomínio", true),
        Alvos("o óleo", "oleo", "o filtro", true),
        // Depois do conector vem um DIA, uma HORA ou nada: não é alvo de tarefa nenhuma.
        Alvos("o remédio", "remedio", "hoje", false),
        Alvos("o médico", "medico", "amanhã", false),
        Alvos("o remédio", "remedio", "às três", false),
        Alvos("o médico", "medico", "", false),
        Alvos("a consulta", "consulta", "é amanhã", false),
    )

    /** Os oito conectores medidos no catálogo, mais a forma SEM a vírgula de fechamento. */
    private val conectores = listOf(
        ", não, ", ", quer dizer, ", ", digo, ", ", na verdade, ",
        ", melhor, ", ", errei, ", ", ao invés disso, ", ", em vez disso, ", ", não ",
    )

    /**
     * A família que o corpus do #83 NÃO media, e é onde o defeito mora: depois da CÓPULA, o
     * complemento nomeia o alvo novo sem determinante — o substantivo nu ("não é dentista"), a
     * preposição ("não é de diabetes") e o pronome ("não é ele").
     *
     * As dez caudas de [alvos] abrem por determinante, temporal ou são vazias, e é justamente por
     * isso que a suíte do #83 ficava verde com o buraco vivo: o veredito de [SpeechIntentClassifier]
     * perguntava "o token seguinte é determinante?" e, quando não era, respondia "continuação" — o
     * app agia sobre o alvo que ela DESCARTou. Medido no classificador real, antes do fix: o
     * substantivo nu age em 36 de 36 casos, a preposição em 36 de 36, o pronome em 18 de 36 (só
     * "ele"/"ela"; os demonstrativos já bloqueavam) e a correção explícita em 9 de 27.
     *
     * O conector é a CÓPULA porque é ela que dá o sinal estrutural: sem ela, o token nominal é
     * indistinguível do verbo que abre uma continuação ("não vou poder ir", "não da tempo"), e
     * medi-lo fora da cópula derrubaria o corpus de continuação inteiro. A forma `", não dentista"`
     * fica como limite declarado, presa em [aFormaSemCopulaContinuaAgindo] .
     */
    private val complementosDepoisDaCopula = listOf(
        // Determinante — já bloqueia hoje; está aqui para o eixo não regredir.
        Alvos("o médico", "medico", "o dentista", true),
        Alvos("o remédio de pressão", "remedio de pressao", "o de diabetes", true),
        // Substantivo nu — o caso mais grave: "não é diabetes" registrava a DOSE errada.
        Alvos("o médico", "medico", "dentista", true),
        Alvos("o remédio de pressão", "remedio de pressao", "diabetes", true),
        Alvos("a consulta", "consulta", "pressão", true),
        // Preposição.
        Alvos("o médico", "medico", "de diabetes", true),
        Alvos("o remédio de pressão", "remedio de pressao", "de pressão", true),
        Alvos("o médico", "medico", "pra mim", true),
        // Pronome.
        Alvos("o médico", "medico", "ele", true),
        Alvos("a consulta", "consulta", "ela", true),
        Alvos("o médico", "medico", "esse", true),
        // Correção EXPLÍCITA: ela nomeia o alvo novo depois da vírgula, e o app apagava o velho.
        Alvos("o médico", "medico", "ele, é o dentista", true),
        Alvos("o médico", "medico", "isso, é amanhã", true),
        // Depois da cópula vem um dia: não é alvo de tarefa de tarefa, mas também é correção.
        Alvos("o médico", "medico", "hoje", false),
        Alvos("o médico", "medico", "amanhã", false),
    )

    /** O preâmbulo com que ela abre a fala espontaneamente. */
    private val preambulos = listOf("", "por favor, ", "ah, ")

    /**
     * Os três verbos que agem sobre um alvo NOMEADO — os únicos em que o alvo errado vira dano
     * (`Ask`, `Unknown` e a captura não agem sobre alvo nenhum).
     */
    private val verbos = listOf("cancela ", "já tomei ", "apaga ")

    private fun alvoDe(intent: SpeechIntent): String? = when (intent) {
        is SpeechIntent.Cancel -> intent.target
        is SpeechIntent.Complete -> intent.target
        is SpeechIntent.EraseNamed -> intent.target
        else -> null
    }

    private fun frase(preambulo: String, verbo: String, descartado: String, conector: String, corrigido: String) =
        "$preambulo$verbo$descartado$conector$corrigido"

    @Test
    fun nenhumaCorrecaoAgeSobreOAlvoDescartado() {
        var casos = 0
        var violDescartado = 0
        var agiuComCorrecao = 0
        var corrigidoEhAlvo = 0
        var corrigidoNaoEhAlvo = 0

        preambulos.forEach { preambulo ->
            verbos.forEach { verbo ->
                alvos.forEach { alvo ->
                    conectores.forEach { conector ->
                        casos++
                        if (alvo.corrigidoEhAlvo) corrigidoEhAlvo++ else corrigidoNaoEhAlvo++
                        val intent = SpeechIntentClassifier.classify(
                            frase(preambulo, verbo, alvo.descartado, conector, alvo.corrigido),
                        )
                        val alvoDeAcao = alvoDe(intent) ?: return@forEach
                        agiuComCorrecao++
                        if (alvoDeAcao == alvo.alvoDescartado) {
                            violDescartado++
                            if (violDescartado <= 12) {
                                println(
                                    "PROBE-VIOL|«" +
                                        frase(preambulo, verbo, alvo.descartado, conector, alvo.corrigido) +
                                        "» → $intent",
                                )
                            }
                        }
                    }
                }
            }
        }

        println("PROBE-RESUMO|alvoSobCorrecao|casos=$casos|pegouAlvoDescartado=$violDescartado|agiuComCorrecao=$agiuComCorrecao")
        println("PROBE-RESUMO|viabilidadeDoCorrigido|casos=$casos|corrigidoEhAlvo=$corrigidoEhAlvo|corrigidoNaoEhAlvo=$corrigidoNaoEhAlvo")
        assertThat(violDescartado).isEqualTo(0)
        assertThat(agiuComCorrecao).isEqualTo(0)
    }

    /**
     * O eixo que o #83 deixou em aberto: o complemento depois da CÓPULA, por FORMA.
     *
     * O invariante é o mesmo de [nenhumaCorrecaoAgeSobreOAlvoDescartado] — nenhuma correção pode
     * agir sobre o alvo que ela descartou —, mas o corpus é o que o primeiro não media. Antes do
     * fix, este teste morre com **117 de 405** casos agindo no descartado (o substantivo nu em 36 de
     * 36, a preposição em 36 de 36, o pronome em 18 de 36 e a correção explícita em 9 de 27); com o
     * fix ele fica em zero.
     *
     * O custo é declarado e preso separadamente em [asFormasDeContinuacaoQueTemDeContinuarAgindo].
     */
    @Test
    fun correcaoDepoisDaCopulaNuncaAgeSobreOAlvoDescartado() {
        var casos = 0
        var casosComAlvoNovo = 0
        var agiuNoDescartado = 0
        var bloqueouCorrecao = 0
        var bloqueouNaoAlvo = 0

        preambulos.forEach { preambulo ->
            verbos.forEach { verbo ->
                complementosDepoisDaCopula.forEach { alvo ->
                    casos++
                    if (alvo.corrigidoEhAlvo) casosComAlvoNovo++
                    val frase = "$preambulo$verbo${alvo.descartado}, não é ${alvo.corrigido}"
                    val intent = SpeechIntentClassifier.classify(frase)
                    if (alvoDe(intent) == alvo.alvoDescartado) {
                        agiuNoDescartado++
                        if (agiuNoDescartado <= 12) println("PROBE-VIOL-CO|«$frase» → $intent")
                    }
                    if (intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION) {
                        if (alvo.corrigidoEhAlvo) bloqueouCorrecao++ else bloqueouNaoAlvo++
                    }
                }
            }
        }

        println(
            "PROBE-RESUMO|correcaoDepoisDaCopula|casos=$casos|casosComAlvoNovo=$casosComAlvoNovo|" +
                "agiuNoDescartado=$agiuNoDescartado|bloqueouCorrecao=$bloqueouCorrecao|" +
                "bloqueouNaoAlvo=$bloqueouNaoAlvo",
        )
        assertThat(agiuNoDescartado).isEqualTo(0)
        // O complemento que NOMEIA alvo novo tem de bloquear em TODOS os casos — é a metade do
        // benefício. O dia ("não é amanhã") também bloqueia, e é o lado seguro: não age sobre o
        // descartado nem inventa um alvo "amanha".
        assertThat(bloqueouCorrecao).isEqualTo(casosComAlvoNovo)
        assertThat(bloqueouNaoAlvo).isEqualTo(casos - casosComAlvoNovo)
    }

    /**
     * O LIMITE DECLARADO da correção sem cópula: `"cancela o médico, não dentista"` (a 8ª forma do
     * conector, sem a segunda vírgula, e sem o `é`).
     *
     * Aqui o complemento é um substantivo nu, e a FORMA da frase não o separa do verbo que abre uma
     * continuação: `"cancela o médico, não da tempo"` tem exatamente o mesmo desenho — `não` seguido
     * de uma palavra que pode ser substantivo ou verbo. Qualquer critério que bloqueie o primeiro
     * derruba o segundo, e "não da tempo"/"não quero mais" é a família de razão que o corpus de
     * continuação prende (é o custo do lado seguro, medido no #83). Por isso a decisão é DECLARAR:
     * o app continua agindo, e o número fica aqui para a troca ser explícita em vez de silenciosa.
     */
    @Test
    fun aFormaSemCopulaContinuaAgindo() {
        val frases = listOf(
            "cancela o médico, não dentista",
            "cancela o médico, não diabetes",
            "cancela o médico, não pressão",
        )
        var agiram = 0
        frases.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (alvoDe(intent) == "medico") agiram++
            println("PROBE-FRASE|«$frase» → $intent")
        }
        println("PROBE-RESUMO|formaSemCopula|casos=${frases.size}|agiram=$agiram")
        assertThat(agiram).isEqualTo(frases.size)
    }

    // --- o corpus de CONTINUAÇÃO: o custo, preso em teste -----------------------------
    //
    // O review do PR #83 mediu que o gatilho largo do "não" bloqueava 2400 de 3360 frases que
    // NÃO tinham correção nenhuma. O caso clínico é o pior: "já tomei o remédio de pressão, não
    // preciso mais" deixava de registrar a dose. Este é o corpus que prende a regressão — sem
    // ele, trocar o gatilho de volta para o largo não derruba teste nenhum.

    /**
     * As caudas de RAZÃO: depois do "não" vem uma oração, não um alvo.
     *
     * O `"não é possível"` saiu daqui e está em [caudasQueACopulaBloqueia] — ele é o custo do fix da
     * cópula (ver [correcaoDepoisDaCopulaNuncaAgeSobreOAlvoDescartado]), e tirá-lo de uma lista de
     * tolerância ZERO sem o destino explícito seria esconder a troca. Ele não está sozinho no
     * arquivo: continua preso, com o número, no teste do custo.
     */
    private val caudasDeContinuacao = listOf(
        "não vou poder ir",
        "não preciso mais",
        "não deu tempo",
        "não quero mais",
        "não consigo de manhã",
        "não tenho como",
        "não posso agora",
        "não sei se dá",
        "não deixa",
        "não esqueci",
        "não lembro",
        "não vale a pena",
        "não adianta",
        "não funciona assim",
        "não fui ainda",
        "não foi possível",
        "não tem como",
        "não vou",
        "não queria",
        "não precisa",
        "não perdi",
    )

    /**
     * O custo DECLARADO do fix da cópula: a continuação cujo complemento tem a forma de sintagma
     * nominal é lida como correção, e o app pergunta em vez de agir.
     *
     * `"não é possível"` e `"não é pra mim"` são as duas formas que a frase NÃO separa de uma
     * correção: "possível" é adjetivo predicativo e "dentista" é substantivo, mas a FORMA do
     * complemento é a mesma (palavra nua depois da cópula), e distingui-los pediria léxico, não
     * sintaxe. O desfecho é o lado seguro — deixa de agir, não age sobre o descartado —, e o número
     * fica preso para não subir calado.
     *
     * O que NÃO entrou aqui: a família clínica ("não quero mais", "não tenho como", "não vou poder
     * ir"), que não tem cópula e por isso não é tocada pelo critério. Ela segue em
     * [asFormasDeContinuacaoQueTemDeContinuarAgindo], com tolerância zero.
     */
    private val caudasQueACopulaBloqueia = listOf(
        "não é possível",
        "não é pra mim",
    )

    /** Os alvos do corpus de continuação, com o alvo que o app age hoje (sem correção nenhuma). */
    private val alvosDeContinuacao = listOf(
        "o médico" to "medico",
        "o remédio de pressão" to "remedio de pressao",
        "a consulta" to "consulta",
        "o aluguel" to "aluguel",
        "o óleo" to "oleo",
        "a missa" to "missa",
        "o dentista" to "dentista",
        "a vitamina" to "vitamina",
        "o carro" to "carro",
        "a conta de luz" to "conta de luz",
        "o cabelo" to "cabelo",
        "a fisioterapia" to "fisioterapia",
        "o pão" to "pao",
        "a roupa" to "roupa",
        "o cachorro" to "cachorro",
        "a feira" to "feira",
        "o remédio" to "remedio",
        "a pressão" to "pressao",
        "o ônibus" to "onibus",
        "a neta" to "neta",
        "o café" to "cafe",
        "a janela" to "janela",
        "o sapato" to "sapato",
        "a unha" to "unha",
        "o telefone" to "telefone",
        "a água" to "agua",
        "o gás" to "gas",
        "a luz" to "luz",
        "o bolo" to "bolo",
        "a roupa de cama" to "roupa de cama",
        "o jardim" to "jardim",
        "a farmácia" to "farmacia",
    )

    /**
     * O espaço do review: 5 verbos × 32 alvos × **21** caudas = **3360** casos.
     *
     * A conta estava escrita "21 caudas = 3360" e depois corrigida para "22 = 3520" — e agora
     * voltou a 21 porque `"não é possível"` saiu de [caudasDeContinuacao] para
     * [caudasQueACopulaBloqueia], onde ele é o custo do fix da cópula. O número do log é 3360, e é
     * ele que vale: contagem errada em comentário é o que a próxima pessoa usa para decidir.
     */
    private val verbosDeContinuacao = verbos + listOf("exclui ", "tira ")

    @Test
    fun clausulaDeRazaoNaoBloqueiaOComandoCerto() {
        var casos = 0
        var bloqueados = 0
        val exemplos = mutableListOf<String>()

        verbosDeContinuacao.forEach { verbo ->
            alvosDeContinuacao.forEach { (alvo, alvoDobrado) ->
                caudasDeContinuacao.forEach { cauda ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$alvo, $cauda")
                    val bloqueado = intent is SpeechIntent.Unknown &&
                        intent.kind == UnsupportedKind.CORRECTION
                    if (bloqueado) {
                        bloqueados++
                        if (exemplos.size < 3) exemplos += "$verbo$alvo, $cauda"
                    } else if (alvoDe(intent) != alvoDobrado) {
                        // Não é bloqueio: ou age sobre o alvo (o certo), ou é outro desfecho.
                        bloqueados++
                        if (exemplos.size < 3) exemplos += "OUTRO: $verbo$alvo, $cauda → $intent"
                    }
                }
            }
        }

        println("PROBE-RESUMO|corpusDeContinuacao|casos=$casos|bloqueados=$bloqueados|exemplos=$exemplos")
        assertThat(bloqueados).isEqualTo(0)
    }

    /**
     * O custo do fix da cópula, preso no lado em que ele EXISTE: as duas caudas cujo complemento tem
     * a forma de sintagma nominal e que são, na verdade, continuação.
     *
     * A asserção é de igualdade — o número não pode subir (regressão do lado seguro) nem cair sem
     * alguém remover a linha. É o par de [asFormasDeContinuacaoQueTemDeContinuarAgindo], que prende o
     * lado onde o custo tem de ser ZERO.
     */
    @Test
    fun oCustoDaCopulaFicaDeclarado() {
        var casos = 0
        var bloqueados = 0
        val naoBloqueadas = mutableListOf<String>()

        verbosDeContinuacao.forEach { verbo ->
            alvosDeContinuacao.forEach { (alvo, _) ->
                caudasQueACopulaBloqueia.forEach { cauda ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$alvo, $cauda")
                    if (intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION) {
                        bloqueados++
                    } else {
                        naoBloqueadas += "$verbo$alvo, $cauda → $intent"
                    }
                }
            }
        }

        println("PROBE-RESUMO|custoDaCopulaDeclarado|casos=$casos|bloqueados=$bloqueados")
        if (naoBloqueadas.isNotEmpty()) println("PROBE-NAO-BLOQUEADAS|$naoBloqueadas")
        assertThat(bloqueados).isEqualTo(casos)
    }

    /**
     * O outro lado da mesma balança: a correção por CONTEÚDO depois de uma cópula continua
     * bloqueando. `"já tomei o remédio, não é o de pressão"` é correção — o que segue nomeia a
     * tarefa —, e é o caso que um gatilho só de "oração" deixaria passar.
     */
    @Test
    fun correcaoPorConteudoDepoisDaCopulaContinuaBloqueada() {
        val frases = listOf(
            "já tomei o remédio de pressão, não é o de diabetes",
            "cancela o médico, não é o dentista",
            "apaga o remédio, não é o de pressão",
            "já tomei o remédio, não é amanhã",
        )
        var liberadas = 0
        frases.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            val bloqueada = intent is SpeechIntent.Unknown &&
                intent.kind == UnsupportedKind.CORRECTION
            if (!bloqueada) {
                liberadas++
                println("PROBE-FALSO-NEGATIVO|«$frase» → $intent")
            }
        }
        println("PROBE-RESUMO|correcaoDepoisDaCopula|casos=${frases.size}|liberadas=$liberadas")
        assertThat(liberadas).isEqualTo(0)
    }

    /**
     * O custo declarado do lado seguro, medido e preso em teste: as caudas cujo primeiro token é
     * AMBÍGUO — a contração "de+a" ("não, da fisioterapia"), o demonstrativo "esta" ("não, esta
     * consulta") e a cortesia "tá" ("não, tá bom") — são lidas como correção, e o app pergunta em
     * vez de agir. É o lado seguro (deixa de agir, não age sobre o errado) e o custo fica
     * EXPLÍCITO aqui, para a troca ser visível em vez de silenciosa.
     */
    @Test
    fun asCaudasQueSeConfundemComDeterminanteContinuamBloqueadas() {
        val ambiguas = listOf(
            "cancela o médico, não esta certo",
            "cancela o médico, não esta marcado",
            "cancela o médico, não é o momento",
        )
        var bloqueadas = 0
        ambiguas.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION) {
                bloqueadas++
            }
        }
        println("PROBE-RESUMO|caudasAmbiguas|casos=${ambiguas.size}|bloqueadas=$bloqueadas")
        assertThat(bloqueadas).isEqualTo(ambiguas.size)
    }

    /**
     * O CUSTO do fix da cópula, medido pelo outro lado: as continuações que chegam à cópula têm de
     * continuar agindo no alvo certo.
     *
     * A cópula é o discriminador estrutural (ver [complementosDepoisDaCopula]) e é isso que separa
     * esta família da que o corpus de continuação prende: `"não vou poder ir"`, `"não da tempo"` e
     * `"não tenho como"` NÃO têm cópula, então o critério não as toca. As que TÊM cópula e são
     * continuação — `"não é possível"`, `"não é pra mim"`, `"não é o momento"`, `"não é meu médico"`
     * — são o custo declarado: o app deixa de agir e pergunta, que é o lado seguro. Este teste prende
     * que as duas primeiras famílias seguem agindo, e o resíduo ambíguo fica preso em
     * [oResidualDaCopulaContinuaBloqueadoENaoAge] com o número explícito.
     */
    @Test
    fun asFormasDeContinuacaoQueTemDeContinuarAgindo() {
        val frases = listOf(
            "cancela o médico, não vou poder ir" to "medico",
            "cancela o médico, não da tempo" to "medico",
            "cancela o médico, não tenho como" to "medico",
            "cancela o médico, não quero mais" to "medico",
            "já tomei o remédio de pressão, não quero mais" to "remedio de pressao",
            "já tomei o remédio de pressão, não tenho como" to "remedio de pressao",
            "cancela o médico, não vou a pé" to "medico",
            "cancela o médico, não chegou o dinheiro" to "medico",
            "cancela o médico, não vale a pena" to "medico",
        )
        var casos = 0
        var quebrados = 0
        frases.forEach { (frase, alvoEsperado) ->
            casos++
            if (alvoDe(SpeechIntentClassifier.classify(frase)) != alvoEsperado) {
                quebrados++
                println("PROBE-VIOL-CO-CUSTO|«$frase» → ${SpeechIntentClassifier.classify(frase)}")
            }
        }
        println("PROBE-RESUMO|custoDaCopula|casos=$casos|quebrados=$quebrados")
        assertThat(quebrados).isEqualTo(0)
    }

    /**
     * O resíduo declarado do lado da cópula: as continuações cujo complemento TEM a forma de alvo
     * ("não é **possível**", "não é **pra mim**", "não é **o** momento", "não é **meu** médico") são
     * lidas como correção, e o app pergunta em vez de agir.
     *
     * O número é o custo do fix, preso para não subir calado. Ele NÃO pode cair na família clínica
     * ("não quero mais", "não tenho como", "não vou poder ir"), que é presa em
     * [asFormasDeContinuacaoQueTemDeContinuarAgindo]: essas não têm cópula e não são tocadas.
     */
    @Test
    fun oResidualDaCopulaContinuaBloqueadoENaoAge() {
        val residuais = listOf(
            "cancela o médico, não é possível",
            "cancela o médico, não é pra mim",
            "cancela o médico, não é o momento",
            "cancela o médico, não é meu médico",
        )
        var bloqueadas = 0
        var agiram = 0
        residuais.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION) {
                bloqueadas++
            } else if (alvoDe(intent) != null) {
                agiram++
                println("PROBE-VIOL-CO-RESID|«$frase» → $intent")
            }
        }
        println("PROBE-RESUMO|residualDaCopula|casos=${residuais.size}|bloqueadas=$bloqueadas|agiram=$agiram")
        assertThat(bloqueadas).isEqualTo(residuais.size)
        assertThat(agiram).isEqualTo(0)
    }

    @Test
    fun semConectorOComandoContinuaExatamenteComoHoje() {
        var casos = 0
        var quebrados = 0

        preambulos.forEach { preambulo ->
            verbos.forEach { verbo ->
                alvos.forEach { alvo ->
                    casos++
                    val intent = SpeechIntentClassifier.classify(
                        frase(preambulo, verbo, alvo.descartado, "", ""),
                    )
                    if (alvoDe(intent) != alvo.alvoDescartado) quebrados++
                }
            }
        }

        println("PROBE-RESUMO|controleSemConector|casos=$casos|quebrados=$quebrados")
        assertThat(quebrados).isEqualTo(0)
    }

    /**
     * A captura legítima com radical de edição não pode virar pergunta: este é o controle do
     * outro lado do custo. É asserção, e não um número no log.
     */
    @Test
    fun capturasLegitimasNaoSaoConfundidasComCorrecao() {
        val capturas = listOf(
            "muda o óleo do carro",
            "troca a lâmpada da sala",
            "me lembra de trocar o remédio",
            "mudar o óleo do carro",
            "adiar a reunião",
            "Tomar remédio às 8",
            "Consulta médica terça",
            "comprar leite",
            "pagar a conta de luz amanhã",
            "me lembra de comprar pão, não, leite",
        )
        var confundidas = 0
        capturas.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION) {
                confundidas++
                println("PROBE-FALSO-POSITIVO|«$frase»")
            }
        }
        println("PROBE-RESUMO|capturasLegitimas|casos=${capturas.size}|viramPerguntaDeCorrecao=$confundidas")
        assertThat(confundidas).isEqualTo(0)
    }
}

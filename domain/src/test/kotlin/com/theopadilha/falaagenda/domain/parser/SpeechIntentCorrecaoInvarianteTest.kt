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

    // --- o corpus de CONTINUAÇÃO: o custo, preso em teste -----------------------------
    //
    // O review do PR #83 mediu que o gatilho largo do "não" bloqueava 2400 de 3360 frases que
    // NÃO tinham correção nenhuma. O caso clínico é o pior: "já tomei o remédio de pressão, não
    // preciso mais" deixava de registrar a dose. Este é o corpus que prende a regressão — sem
    // ele, trocar o gatilho de volta para o largo não derruba teste nenhum.

    /** As caudas de RAZÃO: depois do "não" vem uma oração, não um alvo. */
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
        "não é possível",
        "não tem como",
        "não vou",
        "não queria",
        "não precisa",
        "não perdi",
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
     * O espaço do review: 5 verbos × 32 alvos × **22** caudas = **3520** casos.
     *
     * A conta estava escrita "21 caudas = 3360" e não batia com a lista — que tem 22 desde que
     * "não tem como" entrou. O número do log é 3520, e é ele que vale: contagem errada em
     * comentário é o que a próxima pessoa usa para decidir.
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

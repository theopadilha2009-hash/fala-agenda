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
 * O oráculo mede também a viabilidade da outra saída possível do fix (usar o alvo CORRIGIDO, o
 * que vem depois do conector): quantos casos do espaço têm, depois do conector, um alvo de
 * tarefa de verdade, e quantos têm um dia, uma hora ou nada. É esse número que decide entre as
 * duas saídas — não a preferência de quem escreve.
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
                        if (alvoDeAcao == alvo.alvoDescartado) violDescartado++
                    }
                }
            }
        }

        println("PROBE-RESUMO|alvoSobCorrecao|casos=$casos|pegouAlvoDescartado=$violDescartado|agiuComCorrecao=$agiuComCorrecao")
        println("PROBE-RESUMO|viabilidadeDoCorrigido|casos=$casos|corrigidoEhAlvo=$corrigidoEhAlvo|corrigidoNaoEhAlvo=$corrigidoNaoEhAlvo")
        assertThat(violDescartado).isEqualTo(0)
        assertThat(agiuComCorrecao).isEqualTo(0)
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
     * O CUSTO do lado seguro, medido em vez de suposto.
     *
     * O gatilho é o conector de correção, e ele é procurado no alvo INTEIRO. Se o nome de uma
     * tarefa de verdade contiver uma dessas palavras como palavra própria ("Remédio, não é o de
     * pressão"), a fala deixa de agir e vira a pergunta — deixa de concluir ou apagar, o que é o
     * lado seguro. O que não pode acontecer é a captura legítima com radical de edição virar
     * pergunta: por isso o controle abaixo é asserção, e não um número no log.
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

    /**
     * O outro lado do custo: uma fala que ERA comando e cujo nome de tarefa contém uma das
     * palavras do conector ("Remédio, não é o de pressão") deixa de agir e vira pergunta. É
     * medido, não escondido: o desfecho é o lado seguro (não conclui nem apaga a tarefa errada),
     * e a contagem fica no log para quem for revisar.
     */
    @Test
    fun comandoCujoNomeContemAPalavraDoConectorViraPergunta() {
        val frases = listOf(
            "já tomei o remédio, não é o de pressão",
            "cancela o médico, melhor não",
        )
        var viraramPergunta = 0
        frases.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION) {
                viraramPergunta++
            }
        }
        println("PROBE-RESUMO|comandoComPalavraDoConector|casos=${frases.size}|viraramPergunta=$viraramPergunta")
        assertThat(viraramPergunta).isEqualTo(frases.size)
    }
}

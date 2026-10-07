package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O corpus INDEPENDENTE da correção — e ele existe porque o primeiro oráculo era circular.
 *
 * O review do PR #83 mediu o vício: as caudas do corpus anterior abriam com um token da MESMA
 * lista que decide o resultado (`VERBOS_CONJUGADOS`). O teste só podia CONFIRMAR a lista, nunca
 * contradizê-la — "3520 casos, 0 bloqueados" não media o custo real. Num corpus independente
 * (131 caudas naturais), 1500 de 3960 (37,9%) ficaram bloqueadas.
 *
 * Aqui as caudas são fala natural, sem curadoria para agradar a lista: muitas abrem com um token
 * que o classificador NÃO conhece ("da", "to", "esta", "me", "gosto", "recebi"), que é
 * exatamente o que o corpus anterior não podia representar.
 *
 * O outro lado também mora aqui: as reformulações que o token lia como continuação e que são
 * CORREÇÃO de verdade ("não, quero o de diabetes"), com o app agindo sobre o alvo descartado —
 * a dose errada que o PR existe para matar.
 */
class SpeechIntentCorrecaoCorpusIndependenteTest {

    private fun alvoDe(intent: SpeechIntent): String? = when (intent) {
        is SpeechIntent.Cancel -> intent.target
        is SpeechIntent.Complete -> intent.target
        is SpeechIntent.EraseNamed -> intent.target
        else -> null
    }

    private fun bloqueado(intent: SpeechIntent): Boolean =
        intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION

    // --- o custo: caudas de continuação que NÃO são correção ---------------------------

    /**
     * Fala natural de continuação. Muitas abrem com um token fora de qualquer lista do
     * classificador — é isso que quebra o vício circular do corpus anterior.
     */
    private val caudasNaturais = listOf(
        "não da tempo",
        "não to conseguindo",
        "não esta certo",
        "não me sinto bem",
        "não gosto dele",
        "não recebi",
        "não paguei",
        "não marquei",
        "não aguento",
        "não me lembro",
        "não me avisaram",
        "não volto mais",
        "não tinha vaga",
        "não achei",
        "não encontrei",
        "não vi",
        "não ouvi",
        "não entendi",
        "não ficou pronto",
        "não chegou",
        "não apareceu",
        "não resolveu",
        "não adiantou",
        "não vale a pena",
        "não me atendeu",
        "não retornaram",
        "não autorizaram",
        "não liberaram",
        "não melhorou",
        "não passou",
        "não sarou",
        "não tem vaga",
        "não tem ninguém",
        "não tinha lugar",
        "não me devolveram",
        "não me pagaram",
        "não depositaram",
        "não caiu",
        "não entrou",
        "não choveu",
        "não vou poder ir",
        "não preciso mais",
        "não deu tempo",
        "não quero mais",
        "não posso hoje",
        "não sei ainda",
        "não deu certo",
        "não aconteceu",
        "não tenho certeza",
        "não consigo sozinha",
        "não vou conseguir",
        "não fui",
        "não consegui",
        "não tive tempo",
        "não vai dar tempo",
        "não me sinto segura",
        "não esta marcado",
        "não me deixaram",
        "não deixaram",
        "não foi liberado",
        "não é o momento",
        "não vou mais",
        "não tenho mais",
        "não sobrou",
        "não veio",
        "não tem ninguém pra levar",
        "não deu resultado",
        "não estou bem",
        "não vou sair",
        "não deu pra sair",
        "não vou a pé",
        "não vou de ônibus",
        "não tinha ninguém",
        "não ficou claro",
        "não me explicaram",
        "não me falaram",
        "não me contaram",
        "não me devolveram o dinheiro",
        "não chegou o dinheiro",
        "não vou poder pagar",
        "não tenho dinheiro",
        "não tenho como pagar",
    )

    private val alvosNaturais = listOf(
        "o médico" to "medico",
        "o remédio de pressão" to "remedio de pressao",
        "a consulta" to "consulta",
        "o dentista" to "dentista",
        "o aluguel" to "aluguel",
        "a fisioterapia" to "fisioterapia",
    )

    private val verbosNaturais = listOf("cancela ", "já tomei ", "apaga ", "exclui ", "tira ")

    /**
     * O TETO do custo, e não o zero. Três caudas do corpus são genuinamente ambíguas para um
     * classificador que só olha a forma da frase — "não **esta** certo" (o "esta" é o verbo
     * "está" e o demonstrativo), "não **esta** marcado" e "não **é o** momento" (o "é o" tem a
     * forma de alvo, e "momento" é substantivo como "de pressão") —, e separá-las pediria
     * semântica, não forma.
     *
     * O teto existe para o número NÃO poder subir calado: ele é a regressão. O baseline do review
     * era 1500 de 3960 (37,9%); o teto aqui é 5%, e o valor medido fica no log.
     */
    private val TETO_DE_BLOQUEIO = 0.05

    @Test
    fun corpusIndependenteDeContinuacaoNaoBloqueiaOComandoCerto() {
        var casos = 0
        var bloqueados = 0
        val distintas = mutableSetOf<String>()

        verbosNaturais.forEach { verbo ->
            alvosNaturais.forEach { (alvo, alvoDobrado) ->
                caudasNaturais.forEach { cauda ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$alvo, $cauda")
                    if (bloqueado(intent)) {
                        bloqueados++
                        distintas += cauda
                    } else if (alvoDe(intent) != alvoDobrado) {
                        bloqueados++
                        distintas += "OUTRO: $verbo$alvo, $cauda → $intent"
                    }
                }
            }
        }

        val taxa = bloqueados.toDouble() / casos
        println(
            "PROBE-RESUMO|corpusIndependente|casos=$casos|bloqueados=$bloqueados|" +
                "taxa=${"%.3f".format(taxa)}|caudasDistintas=${distintas.size}",
        )
        if (distintas.isNotEmpty()) println("PROBE-BLOQUEADAS|${distintas.sorted().joinToString(" | ")}")
        assertThat(taxa).isLessThan(TETO_DE_BLOQUEIO)
    }

    /**
     * O residual declarado, preso em teste para a troca ser explícita: as três caudas ambíguas
     * acima continuam bloqueando, e o desfecho é o lado seguro (o app pergunta em vez de agir
     * sobre o alvo descartado). O "meu médico" do F8 do review entra aqui também — "meu" é
     * determinante, então "não é meu médico" é lido como alvo, e a direção é a segura.
     */
    @Test
    fun oResidualAmbiguoContinuaBloqueadoENaoAge() {
        val residuais = listOf(
            "cancela o médico, não esta certo",
            "cancela o médico, não esta marcado",
            "cancela o médico, não é o momento",
            "cancela o médico, não é meu médico",
        )
        var bloqueadas = 0
        var agiram = 0
        residuais.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            when {
                bloqueado(intent) -> bloqueadas++
                alvoDe(intent) != null -> agiram++
            }
        }
        println("PROBE-RESUMO|residualAmbiguo|casos=${residuais.size}|bloqueadas=$bloqueadas|agiram=$agiram")
        assertThat(bloqueadas).isEqualTo(residuais.size)
        assertThat(agiram).isEqualTo(0)
    }

    // --- o dano: a reformulação que o token lia como continuação -----------------------

    /**
     * F1 do review: o alvo corrigido abre com um verbo, e o veredito de "oração ⇒ continuação"
     * lia a CORREÇÃO como razão — o app agia sobre o alvo descartado.
     */
    private val reformulacoes = listOf(
        "não, quero o de diabetes",
        "não, quero o dentista",
        "não, tenho o de pressão",
        "não, prefiro o dentista",
        "não, quero o de pressão",
        "não, tenho o dentista",
        "não, quero a consulta",
    )

    private val descartadosDaReformulacao = listOf(
        "o médico" to "medico",
        "o remédio de pressão" to "remedio de pressao",
        "a consulta" to "consulta",
        "o aluguel" to "aluguel",
    )

    @Test
    fun reformulacaoQueAbreComVerboNaoAgeSobreOAlvoDescartado() {
        var casos = 0
        var agiram = 0

        verbosNaturais.forEach { verbo ->
            descartadosDaReformulacao.forEach { (descartado, alvoDescartado) ->
                reformulacoes.forEach { reformulacao ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$descartado, $reformulacao")
                    if (alvoDe(intent) == alvoDescartado) agiram++
                }
            }
        }

        println("PROBE-RESUMO|reformulacaoComVerbo|casos=$casos|agiramSobreODescartado=$agiram")
        assertThat(agiram).isEqualTo(0)
    }

    @Test
    fun osCasosPontuaisDoReview() {
        // F2: "isso"/"isto" não nomeiam alvo para o veredito da cópula, e a correção passava.
        assertThat(bloqueado(SpeechIntentClassifier.classify("cancela o médico, não é isso, o dentista")))
            .isTrue()
        // F3: só "quis dizer" estava na lista; a forma corrente é "quero dizer".
        assertThat(bloqueado(SpeechIntentClassifier.classify("cancela o médico, quero dizer, o dentista")))
            .isTrue()
        // F6: o rabo do regex só aceitava espaço ou vírgula — "." não fechava o conector.
        assertThat(bloqueado(SpeechIntentClassifier.classify("cancela o médico, errei. o dentista")))
            .isTrue()
        assertThat(bloqueado(SpeechIntentClassifier.classify("cancela o médico, errei! o dentista")))
            .isTrue()
        // F4: a ênfase final com "não" bloqueava o comando certo.
        assertThat(alvoDe(SpeechIntentClassifier.classify("cancela o médico, não vou poder ir, não")))
            .isEqualTo("medico")
    }
}

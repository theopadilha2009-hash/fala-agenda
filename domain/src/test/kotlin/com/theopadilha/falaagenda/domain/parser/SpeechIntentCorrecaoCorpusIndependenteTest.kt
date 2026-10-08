package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O corpus INDEPENDENTE da correção — e ele existe porque o primeiro oráculo era circular.
 *
 * O review do PR #83 mediu o vício: as caudas do corpus anterior abriam com um token da MESMA
 * lista que decide o resultado (`VERBOS_CONJUGADOS`). O teste só podia CONFIRMAR a lista, nunca
 * contradizê-la — "3520 casos, 0 bloqueados" não media o custo real.
 *
 * Aqui as caudas são fala natural, sem curadoria para agradar a lista: muitas abrem com um token
 * que o classificador NÃO conhece ("da", "to", "esta", "me", "gosto", "recebi"), que é
 * exatamente o que o corpus anterior não podia representar.
 *
 * Este arquivo tem [caudasNaturais] = 82 caudas, e com [alvosNaturais] (6) × [verbosNaturais] (5)
 * o espaço é de **2460 casos**. Contra o desenho ANTERIOR (a heurística de token, commit
 * `8f41bc3`), esse mesmo espaço bloqueava **1320 de 2460 (53,7%)** — o número que o review
 * independente mediu fora da árvore, e maior do que o que este KDoc declarava.
 *
 * A quarta rodada acrescentou a família que FALTAVA aqui, e a ausência dela é o defeito que
 * sobreviveu a três rodadas: das 82 caudas, **zero** tinham a forma `MOLDURA + determinante`
 * ("não tenho **a** receita"). Enquanto o corpus é construído com as formas que a lista do código
 * já cobre, a suíte fica verde com o defeito vivo. Elas estão em [caudasComAlvoMencionado], com o
 * custo DECLARADO em vez de escondido.
 *
 * O outro lado também mora aqui: as reformulações que o token lia como continuação e que são
 * CORREÇÃO de verdade ("não, quero o de diabetes", "não, **eu** quero o de diabetes"), com o app
 * agindo sobre o alvo descartado — a dose errada que o PR existe para matar.
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

    /**
     * A família que FALTAVA nas três rodadas anteriores (F-C do review): a continuação que MENCIONA
     * um substantivo, abrindo com um verbo de [MOLDURA] seguido de determinante — "não tenho **a**
     * receita", "não quero **o** remédio". Zero das 82 caudas acima tinham essa forma, e é por isso
     * que a suíte ficava verde com o defeito vivo.
     *
     * Ela é a fronteira entre duas falas que a FORMA da frase não separa:
     *
     * - `"já tomei o remédio de pressão, não quero mais"` — a cauda começa com "quero", e o "mais"
     *   não nomeia tarefa nenhuma: é a RAZÃO, o comando tem de agir e registrar a dose;
     * - `"já tomei o remédio de pressão, não quero o de diabetes"` — a cauda começa com o MESMO
     *   "quero" e nomeia a tarefa: é CORREÇÃO, e agir sobre o alvo descartado registra a dose errada.
     *
     * O que separa as duas é o determinante depois do verbo, e não o verbo. Qualquer critério que
     * pergunte "o verbo pode abrir um alvo?" é obrigado a bloquear as duas — e é aí que mora o caso
     * clínico. O custo de bloquear a família de razão é medido em [CUSTO_DA_FAMILIA_COM_ALVO], e
     * não escondido.
     */
    private val caudasComAlvoMencionado = listOf(
        "não tenho a receita",
        "não tenho o dinheiro",
        "não quero o remédio",
        "não quero a consulta",
        "não tenho o telefone",
        "não quero o dinheiro",
    )

    /**
     * As caudas de RAZÃO que abrem com o MESMO verbo de [caudasComAlvoMencionado].
     *
     * O `"não é possível"` saiu daqui: ele é a cópula seguida de palavra nua, e é o custo DECLARADO
     * do fix do complemento sem determinante (ver `SpeechIntentCorrecaoInvarianteTest`). Ele
     * continua no corpus — só que em [caudasQueACopulaBloqueia], com o número explícito, em vez de
     * numa lista de tolerância zero onde ele faria a asserção mentir.
     */
    private val caudasDeRazaoComOMesmoVerbo = listOf(
        "não quero mais",
        "não tenho como",
        "não tem como",
        "não queria",
        "não posso",
        "não consigo",
        "não preciso mais",
    )

    private val verbosNaturais = listOf("cancela ", "já tomei ", "apaga ", "exclui ", "tira ")

    /**
     * O custo DECLARADO do fix do complemento sem determinante: a continuação cujo complemento,
     * depois da cópula, tem a forma de um sintagma nominal — palavra nua ("não é possível"), ou
     * preposição ("não é pra mim").
     *
     * A FORMA não separa essas duas de uma correção de verdade ("não é dentista", "não é de
     * diabetes"): as quatro são `cópula + palavra nua` ou `cópula + preposição + nome`. Separá-las
     * pediria léxico (saber que "possível" é adjetivo e "dentista" é substantivo), e o classificador
     * é puro e não tem léxico. O desfecho é o lado seguro — deixa de agir, não age sobre o alvo
     * descartado —, e o número fica preso para não subir calado.
     *
     * O que NÃO entra aqui é a família clínica (`"não quero mais"`, `"não tenho como"`, `"não vou
     * poder ir"`): nenhuma delas tem cópula, então o critério não as toca e elas continuam em
     * [caudasNaturais] com tolerância zero.
     */
    private val caudasQueACopulaBloqueia = listOf(
        "não é possível",
        "não é pra mim",
    )

    @Test
    fun oCustoDaCopulaFicaDeclarado() {
        var casos = 0
        var bloqueados = 0
        val naoBloqueadas = mutableListOf<String>()

        verbosNaturais.forEach { verbo ->
            alvosNaturais.forEach { (alvo, _) ->
                caudasQueACopulaBloqueia.forEach { cauda ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$alvo, $cauda")
                    if (bloqueado(intent)) {
                        bloqueados++
                    } else {
                        naoBloqueadas += "$verbo$alvo, $cauda → $intent"
                    }
                }
            }
        }

        println("PROBE-RESUMO|custoDaCopulaCorpusIndependente|casos=$casos|bloqueados=$bloqueados")
        if (naoBloqueadas.isNotEmpty()) println("PROBE-NAO-BLOQUEADAS|$naoBloqueadas")
        assertThat(bloqueados).isEqualTo(casos)
    }

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
            "cancela o médico, não é pra mim",
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

    // --- F-A e F-B: a correção de verdade que o desenho de token deixava passar --------------

    /**
     * F-A do review: a moldura da reformulação era pulada UMA vez, e o sujeito "eu" — ubíquo na
     * fala espontânea — caía no lugar do alvo. O veredito de token lia "eu" (não é determinante)
     * como continuação e o app agia sobre o alvo DESCARTADO.
     *
     * `"já tomei o remédio de pressão, não, eu quero o de diabetes"` registrava a dose errada.
     * Medido no espaço `5 verbos × 4 descartados × 9 caudas com "eu"`: **54 de 216 (25%)**.
     */
    private val reformulacoesComSujeito = listOf(
        "eu quero o de diabetes",
        "eu quero o dentista",
        "eu tenho o de pressão",
        "eu prefiro o dentista",
        "eu queria o de diabetes",
        "eu quero a consulta",
        "eu vou querer o dentista",
        "eu escolho o de pressão",
        "eu quero o aluguel",
    )

    /**
     * As duas formas do conector, e as duas importam: a vírgula de FECHAMENTO fecha a correção
     * sozinha, e a forma sem ela (a 8ª do catálogo, `", não "`) depende do veredito do alvo — que
     * é onde o sujeito "eu" atrapalhava.
     */
    private val conectoresDeCorrecao = listOf(", não, ", ", não ")

    private fun contaAgiramSobreODescartado(conector: String, caudas: List<String>): Pair<Int, Int> {
        var casos = 0
        var agiram = 0
        verbosNaturais.forEach { verbo ->
            descartadosDaReformulacao.forEach { (descartado, alvoDescartado) ->
                caudas.forEach { cauda ->
                    casos++
                    val frase = "$verbo$descartado$conector$cauda"
                    val intent = SpeechIntentClassifier.classify(frase)
                    if (alvoDe(intent) == alvoDescartado) {
                        agiram++
                        if (agiram <= 6) println("PROBE-VIOL-FA|«$frase» → $intent")
                    }
                }
            }
        }
        return casos to agiram
    }

    @Test
    fun aReformulacaoComSujeitoNaoAgeSobreOAlvoDescartado() {
        val (casos, agiram) = contaAgiramSobreODescartado(", não, ", reformulacoesComSujeito)
        println("PROBE-RESUMO|reformulacaoComSujeito|casos=$casos|agiramSobreODescartado=$agiram")
        assertThat(agiram).isEqualTo(0)
    }

    /**
     * O residual DECLARADO da forma aberta (`", não "`, sem a segunda vírgula), e ele é de 20: a
     * única cauda que ainda age é `"não, eu vou querer o dentista"` — o auxiliar "vou" entre o
     * sujeito e o verbo de moldura.
     *
     * Ele não é fechado porque é genuinamente ambíguo na FORMA, e o custo do outro lado está no
     * corpus: `"não vou **a** pé"` é continuação e tem exatamente o mesmo desenho — determinante
     * mais substantivo — que `"eu vou querer **o** dentista"`. Pular os auxiliares transformaria a
     * primeira em correção, e `não vou a pé` é fala que o corpus de continuação prende. É a mesma
     * assimetria de F-C, e a decisão é a mesma: declarar em vez de trocar.
     *
     * Na forma COM a segunda vírgula o auxiliar não importa: a vírgula fecha a correção sozinha, e
     * o residual é zero (teste acima).
     */
    private val RESIDUAL_DA_FORMA_ABERTA = 20

    @Test
    fun oResidualDaFormaAbertaFicaDeclarado() {
        val (casos, agiram) = contaAgiramSobreODescartado(", não ", reformulacoesComSujeito)
        println("PROBE-RESUMO|residualDaFormaAberta|casos=$casos|agiramSobreODescartado=$agiram")
        assertThat(agiram).isEqualTo(RESIDUAL_DA_FORMA_ABERTA)
    }

    /**
     * F-B do review: `"não é isso"` TERMINANDO a fala. O veredito exigia um token depois do
     * determinante (`if (i + 1 >= tokens.size) return false`) e, sem continuação, não havia esse
     * token — então a correção passava e o app apagava o médico.
     *
     * O caso sem continuação é o mais perigoso: ela abandonou o comando e o app executa assim
     * mesmo. Medido: **24 de 144 (16,7%)**.
     */
    private val correcoesQueTerminamNaCopula = listOf(
        "não é isso",
        "não é isto",
        "não é esse",
        "não é essa",
        "não é esse não",
        "não é o de diabetes",
    )

    @Test
    fun aCorrecaoQueTerminaNaCopulaNaoAgeSobreOAlvoDescartado() {
        var casos = 0
        var agiram = 0

        verbosNaturais.forEach { verbo ->
            descartadosDaReformulacao.forEach { (descartado, alvoDescartado) ->
                correcoesQueTerminamNaCopula.forEach { cauda ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$descartado, $cauda")
                    if (alvoDe(intent) == alvoDescartado) {
                        agiram++
                        if (agiram <= 6) println("PROBE-VIOL-FB|«$verbo$descartado, $cauda» → $intent")
                    }
                }
            }
        }

        println("PROBE-RESUMO|correcaoQueTerminaNaCopula|casos=$casos|agiramSobreODescartado=$agiram")
        assertThat(agiram).isEqualTo(0)
    }

    // --- F-C: o custo declarado, medido e NÃO escondido --------------------------------------

    /**
     * F-C do review, e a DECISÃO DE PRODUTO que este PR registra em vez de consertar.
     *
     * A continuação que MENCIONA um substantivo ("não tenho **a** receita") tem, na forma da
     * frase, o mesmo desenho de uma correção ("não, **eu** quero **o** de diabetes"): verbo de
     * moldura seguido de determinante. **384 de 384 (100%)** são lidas como correção e o comando
     * deixa de agir — a dose não é registrada.
     *
     * Fechar isso custa a família de razão que abre com o MESMO verbo (`não quero mais`, `não
     * tenho como`), que é fala comum e é o caso clínico: `"já tomei o remédio de pressão, não
     * quero mais"` deixaria de registrar a dose. É trocar um buraco conhecido por outro maior — a
     * assimetria não é limpa. O teste abaixo prende os DOIS lados para a troca ser explícita, e a
     * constante é o custo DECLARADO: 1.0 = a família inteira fica bloqueada.
     */
    private val CUSTO_DA_FAMILIA_COM_ALVO = 1.0

    @Test
    fun aFamiliaComAlvoMencionadoEhBloqueadaECustoDeclarado() {
        var casos = 0
        var bloqueados = 0

        verbosNaturais.forEach { verbo ->
            alvosNaturais.forEach { (alvo, _) ->
                caudasComAlvoMencionado.forEach { cauda ->
                    casos++
                    if (bloqueado(SpeechIntentClassifier.classify("$verbo$alvo, $cauda"))) bloqueados++
                }
            }
        }

        val taxa = bloqueados.toDouble() / casos
        println(
            "PROBE-RESUMO|familiaComAlvoMencionado|casos=$casos|bloqueados=$bloqueados|" +
                "taxa=${"%.3f".format(taxa)}",
        )
        assertThat(taxa).isEqualTo(CUSTO_DA_FAMILIA_COM_ALVO)
    }

    /**
     * O outro lado da mesma moeda, e o motivo de F-C ficar como decisão de produto: a família de
     * RAZÃO abre com os mesmos verbos ("quero", "tenho", "tem", "é") e NÃO menciona alvo. Ela tem
     * de continuar agindo — é a dose que a segunda rodada existiu para proteger.
     */
    @Test
    fun aFamiliaDeRazaoComOMesmoVerboContinuaAgindo() {
        var casos = 0
        var bloqueados = 0

        verbosNaturais.forEach { verbo ->
            alvosNaturais.forEach { (alvo, alvoDobrado) ->
                caudasDeRazaoComOMesmoVerbo.forEach { cauda ->
                    casos++
                    val intent = SpeechIntentClassifier.classify("$verbo$alvo, $cauda")
                    if (bloqueado(intent) || alvoDe(intent) != alvoDobrado) {
                        bloqueados++
                        if (bloqueados <= 6) println("PROBE-VIOL-FC|«$verbo$alvo, $cauda» → $intent")
                    }
                }
            }
        }

        println("PROBE-RESUMO|familiaDeRazaoComOMesmoVerbo|casos=$casos|bloqueados=$bloqueados")
        assertThat(bloqueados).isEqualTo(0)
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

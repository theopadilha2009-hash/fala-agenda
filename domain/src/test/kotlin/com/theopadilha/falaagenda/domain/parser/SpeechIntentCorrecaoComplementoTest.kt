package com.theopadilha.falaagenda.domain.parser

import org.junit.Test

/**
 * O buraco da MESMA classe que o #83 não fechou: a correção cujo complemento NÃO é determinante.
 *
 * O #83 fez o veredito da cópula depender de um determinante depois dela ("não é **o** dentista"
 * bloqueia). O que ele não mediu é o complemento que nomeia alvo SEM determinante — o substantivo
 * nu ("não é dentista"), a preposição ("não é de diabetes") e o pronome ("não é ele") —, e nesses o
 * veredito é "continuação": o app executa sobre o alvo que ela DESCARTou.
 *
 * Este arquivo é a REPRODUÇÃO, medida no classificador real, e o oráculo é escrito à mão: a
 * doutrina ("ela corrigiu, então não pode agir no alvo velho") diz o esperado, e o classificador
 * diz o obtido. Nenhum esperado é calculado com o raciocínio do código.
 *
 * O custo do lado seguro também é medido aqui: as continuações legítimas ("não quero mais", "não
 * tenho como", "não é possível") TÊM de continuar agindo — bloquear é a metade perigosa da troca.
 */
class SpeechIntentCorrecaoComplementoTest {

    private fun alvoDe(intent: SpeechIntent): String? = when (intent) {
        is SpeechIntent.Cancel -> intent.target
        is SpeechIntent.Complete -> intent.target
        is SpeechIntent.EraseNamed -> intent.target
        else -> null
    }

    private fun bloqueou(intent: SpeechIntent): Boolean =
        intent is SpeechIntent.Unknown && intent.kind == UnsupportedKind.CORRECTION

    private val verbos = listOf("cancela ", "já tomei ", "apaga ")
    private val descartados = listOf(
        "o médico" to "medico",
        "o remédio de pressão" to "remedio de pressao",
        "a consulta" to "consulta",
    )

    private enum class Esperado { BLOQUEAR, AGIR }

    private data class Forma(val nome: String, val caudas: List<String>, val esperado: Esperado)

    /**
     * O espaço por FORMA do complemento depois da cópula. [Esperado.BLOQUEAR] é a correção (não pode
     * agir no descartado); [Esperado.AGIR] é a continuação legítima (tem de agir no alvo certo).
     */
    private val formas = listOf(
        // --- a correção: qualquer forma de sintagma nominal depois da cópula -----------------
        Forma(
            "substantivo-nu",
            listOf(", não é dentista", ", não é diabetes", ", não é pressão", ", não é aluguel"),
            Esperado.BLOQUEAR,
        ),
        Forma(
            "com-determinante",
            listOf(", não é o dentista", ", não é a consulta", ", não é o de diabetes"),
            Esperado.BLOQUEAR,
        ),
        Forma(
            "preposicao",
            listOf(", não é de diabetes", ", não é de pressão", ", não é pra mim", ", não é do dentista"),
            Esperado.BLOQUEAR,
        ),
        Forma(
            "pronome",
            listOf(", não é ele", ", não é ela", ", não é esse", ", não é isso"),
            Esperado.BLOQUEAR,
        ),
        Forma(
            "correcao-explicita",
            listOf(", não é ele, é o dentista", ", não é isso, é amanhã", ", não é isso, o dentista"),
            Esperado.BLOQUEAR,
        ),
        // --- a continuação: a metade que NÃO pode regredir -----------------------------------
        Forma(
            "continuacao",
            listOf(
                ", não tenho como", ", não quero mais", ", não vou poder ir",
                ", não é possível", ", não vou a pé", ", não tenho a receita",
            ),
            Esperado.AGIR,
        ),
    )

    @Test
    fun medeOCustoEODanoPorFormaDoComplemento() {
        var totalCasos = 0
        var totalAgiuNoDescartado = 0
        var totalBloqueouCorrecao = 0
        var totalContinuacaoDerrubada = 0

        formas.forEach { forma ->
            var casos = 0
            var agiuNoDescartado = 0
            var bloqueouCorrecao = 0
            var agiuNoCerto = 0
            var outro = 0
            val exemplos = mutableListOf<String>()

            verbos.forEach { verbo ->
                descartados.forEach { (descartado, alvoDescartado) ->
                    forma.caudas.forEach { cauda ->
                        casos++
                        val frase = "$verbo$descartado$cauda"
                        val intent = SpeechIntentClassifier.classify(frase)
                        when {
                            bloqueou(intent) -> bloqueouCorrecao++
                            alvoDe(intent) == alvoDescartado -> agiuNoDescartado++
                            alvoDe(intent) != null -> agiuNoCerto++
                            else -> outro++
                        }
                        if (exemplos.size < 2 && alvoDe(intent) == alvoDescartado) {
                            exemplos += "«$frase» → $intent"
                        }
                    }
                }
            }

            totalCasos += casos
            totalAgiuNoDescartado += agiuNoDescartado
            totalBloqueouCorrecao += bloqueouCorrecao
            if (forma.esperado == Esperado.AGIR) totalContinuacaoDerrubada += bloqueouCorrecao

            println(
                "PROBE-FORMA|${forma.nome}|esperado=${forma.esperado}|casos=$casos|" +
                    "agiuNoDescartado=$agiuNoDescartado|bloqueouCorrecao=$bloqueouCorrecao|" +
                    "agiuNoCerto=$agiuNoCerto|outro=$outro|ex=$exemplos",
            )
        }

        println(
            "PROBE-RESUMO|complementoSemDeterminante|casos=$totalCasos|" +
                "agiuNoDescartado=$totalAgiuNoDescartado|bloqueouCorrecao=$totalBloqueouCorrecao|" +
                "continuacaoDerrubada=$totalContinuacaoDerrubada",
        )
    }

    /**
     * O CUSTO do critério, medido nos dois lados, em corpus amplo.
     *
     * Lado 1 — a família clínica: continuação SEM cópula ("não quero mais", "não tenho como"). O
     * critério novo só age depois da cópula, então aqui o custo tem de ser ZERO. É a família que o
     * coordenador avisou que não pode cair.
     *
     * Lado 2 — a continuação COM cópula ("não é possível", "não é pra mim", "não é o momento"): é
     * o custo declarado. A forma não separa essas de uma correção de verdade, e o desfecho é o lado
     * seguro (o app pergunta em vez de agir no descartado).
     */
    @Test
    fun medeOCustoDoCriterio() {
        val semCopula = listOf(
            "não quero mais", "não tenho como", "não vou poder ir", "não da tempo",
            "não consigo", "não posso", "não sei ainda", "não deu certo", "não fui",
            "não me lembro", "não preciso mais", "não vale a pena", "não adianta",
            "não vou a pé", "não chegou o dinheiro", "não tem vaga",
        )
        val comCopula = listOf(
            "não é possível", "não é pra mim", "não é o momento", "não é meu médico",
            "não é assim", "não é bom", "não é o caso", "não é necessário", "não é urgente",
            "não é pra agora", "não é da minha conta", "não é muito longe", "não é aqui",
            "não é verdade", "não é certo", "não é bem assim", "não é hoje",
        )

        var semCopulaBloqueados = 0
        var semCopulaCasos = 0
        val derrubadas = mutableListOf<String>()
        verbos.forEach { verbo ->
            descartados.forEach { (descartado, alvoDescartado) ->
                semCopula.forEach { cauda ->
                    semCopulaCasos++
                    val intent = SpeechIntentClassifier.classify("$verbo$descartado, $cauda")
                    if (bloqueou(intent) || alvoDe(intent) != alvoDescartado) {
                        semCopulaBloqueados++
                        derrubadas += "$verbo$descartado, $cauda → $intent"
                    }
                }
            }
        }

        var comCopulaBloqueados = 0
        var comCopulaCasos = 0
        verbos.forEach { verbo ->
            descartados.forEach { (descartado, _) ->
                comCopula.forEach { cauda ->
                    comCopulaCasos++
                    if (bloqueou(SpeechIntentClassifier.classify("$verbo$descartado, $cauda"))) {
                        comCopulaBloqueados++
                    }
                }
            }
        }

        println(
            "PROBE-CUSTO|semCopula|casos=$semCopulaCasos|derrubadas=$semCopulaBloqueados|" +
                "ex=$derrubadas",
        )
        println("PROBE-CUSTO|comCopula|casos=$comCopulaCasos|bloqueadas=$comCopulaBloqueados")
    }

    /** As frases exatas do despacho, uma a uma, para o output cru. */
    @Test
    fun asFrasesDoDespacho() {
        val frases = listOf(
            "cancela o médico, não é dentista",
            "já tomei o remédio de pressão, não é diabetes",
            "cancela o médico, não é ele, é o dentista",
            "já tomei o remédio de pressão, não é de diabetes",
            "cancela o médico, não é o dentista",
            "cancela o médico, não é a consulta",
            "cancela o médico, não é pra mim",
            "cancela o médico, não é ela",
            "cancela o médico, não é esse",
            "cancela o médico, não é isso, é amanhã",
            "cancela o médico, não tenho como",
            "cancela o médico, não quero mais",
            "cancela o médico, não vou poder ir",
            "cancela o médico, não é possível",
            "cancela o médico, não vou a pé",
            "cancela o médico, não tenho a receita",
        )
        frases.forEach { frase ->
            println("PROBE-FRASE|«$frase» → ${SpeechIntentClassifier.classify(frase)}")
        }
    }
}

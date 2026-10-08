package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O buraco da MESMA classe que o #83 não fechou: a correção cujo complemento NÃO é determinante.
 *
 * O #83 fez o veredito da cópula depender de um determinante depois dela ("não é **o** dentista"
 * bloqueia). O que ele não mediu é o complemento que nomeia alvo SEM determinante — o substantivo
 * nu ("não é dentista"), a preposição ("não é de diabetes") e o pronome ("não é ele") —, e nesses o
 * veredito é "continuação": o app executa sobre o alvo que ela DESCARTou.
 *
 * Este arquivo é a REPRODUÇÃO, medida no classificador real. Até a quarta rodada ele era só
 * `println`: 3 `@Test`, **zero `assertThat`** — passava contra o código da base sem uma falha, e o
 * corpo do PR creditava a ele uma asserção que não existia ali. Os `PROBE` continuam ao lado das
 * asserções (a reprodução tem valor documental), mas quem prende o desfecho agora é a asserção.
 *
 * O oráculo é escrito à mão: a doutrina ("ela corrigiu, então não pode agir no alvo velho") diz o
 * esperado, e o classificador diz o obtido. Nenhum esperado é calculado com o raciocínio do código.
 *
 * O custo do lado seguro também é medido aqui: as continuações legítimas SEM cópula ("não quero
 * mais", "não tenho como") TÊM de continuar agindo — bloquear é a metade perigosa da troca. A
 * família COM cópula cuja forma não decide é [Esperado.AMBIGUA], e o desfecho dela é escalar.
 *
 * A DOUTRINA ÚNICA deste lote: a cauda `"não é X"`, onde X tem forma de alvo, é AMBÍGUA — o
 * classificador é puro e a forma não separa `"não é dentista"` (correção) de `"não é possível"`
 * (continuação). Quando a forma não decide, o repo ESCALA, e escalar nunca age sobre o alvo
 * descartado. Os três oráculos da correção falam a mesma língua sobre a mesma cauda.
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

    /**
     * O desfecho que a DOUTRINA manda. [BLOQUEAR] é a correção (não pode agir no descartado);
     * [AGIR] é a continuação legítima (tem de agir no alvo certo); e [AMBIGUA] é a fala cuja forma
     * NÃO separa a correção da continuação — o desfecho é escalar (`Unknown(CORRECTION)`), o lado
     * seguro, e o número fica preso por igualdade para não subir nem cair calado.
     *
     * A pergunta que a doutrina responde — *a cauda `"não é X"`, onde X tem forma de alvo, é
     * correção ou continuação?* — é: **não dá para decidir pela forma**. `"não é dentista"` é
     * correção, `"não é possível"` é continuação, e as duas são `cópula + palavra nua`: o
     * classificador é puro e não tem léxico para separá-las. Quando a forma não decide, o repo
     * ESCALA (é a mesma doutrina de `HybridParser`: "duas expressões de tempo discordam ⇒ escala"),
     * e escalar nunca age sobre o alvo descartado — que é a razão de existir do #103.
     */
    private enum class Esperado { BLOQUEAR, AGIR, AMBIGUA }

    private data class Forma(val nome: String, val caudas: List<String>, val esperado: Esperado)

    /**
     * O espaço por FORMA do complemento depois da cópula. [Esperado.BLOQUEAR] é a correção clara;
     * [Esperado.AGIR] é a continuação legítima; e [Esperado.AMBIGUA] é a família em que a forma não
     * decide — a mesma cauda aparece como "custo declarado" nos outros dois oráculos, com o MESMO
     * desfecho e a MESMA palavra: ambígua, escala.
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
            listOf(", não é de diabetes", ", não é de pressão", ", não é do dentista"),
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
        // --- a continuação SEM cópula: a metade que NÃO pode regredir -------------------------
        Forma(
            "continuacao-sem-copula",
            listOf(", não tenho como", ", não quero mais", ", não vou poder ir", ", não vou a pé"),
            Esperado.AGIR,
        ),
        // --- a AMBÍGUA: a forma não decide, e o desfecho escala ------------------------------
        //
        // `"não é pra mim"` (sintagma preposicional), `"não é possível"` (adjetivo predicativo) e
        // `"não tenho a receita"` (moldura + determinante) são continuações de verdade; a FORMA
        // delas não as separa de uma correção (`"não é de diabetes"`, `"não é dentista"`). Quando a
        // forma não decide, o desfecho é escalar, e a cauda fica presa por igualdade para não subir
        // nem cair calado.
        //
        // `"não é pra mim"` morava na forma `preposicao` (como se fosse correção clara) e, ao mesmo
        // tempo, na lista de "custo declarado" dos outros dois oráculos: a mesma cauda com dois
        // rótulos opostos. A doutrina única resolve os dois — é AMBÍGUA, e escala nos três.
        Forma(
            "ambigua-com-copula",
            listOf(", não é pra mim", ", não é possível", ", não tenho a receita"),
            Esperado.AMBIGUA,
        ),
    )

    @Test
    fun medeOCustoEODanoPorFormaDoComplemento() {
        var totalCasos = 0
        var totalAgiuNoDescartado = 0
        var totalViolacao = 0
        var totalBloqueouCorrecao = 0
        var totalContinuacaoDerrubada = 0
        var totalAmbigua = 0

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
            // A família de continuação SEM cópula age no alvo por definição — ela não é violação.
            // Só as formas em que o app NÃO pode agir no descartado entram no acumulador de dano.
            if (forma.esperado != Esperado.AGIR) totalViolacao += agiuNoDescartado
            if (forma.esperado == Esperado.AGIR) totalContinuacaoDerrubada += bloqueouCorrecao
            if (forma.esperado == Esperado.AMBIGUA) totalAmbigua += bloqueouCorrecao

            println(
                "PROBE-FORMA|${forma.nome}|esperado=${forma.esperado}|casos=$casos|" +
                    "agiuNoDescartado=$agiuNoDescartado|bloqueouCorrecao=$bloqueouCorrecao|" +
                    "agiuNoCerto=$agiuNoCerto|outro=$outro|ex=$exemplos",
            )

            // A asserção é POSITIVA e de igualdade sobre o desfecho, por forma.
            when (forma.esperado) {
                Esperado.BLOQUEAR -> {
                    assertThat(agiuNoDescartado).isEqualTo(0)
                    assertThat(bloqueouCorrecao).isEqualTo(casos)
                }
                Esperado.AGIR -> {
                    assertThat(agiuNoDescartado).isEqualTo(casos)
                    assertThat(bloqueouCorrecao).isEqualTo(0)
                }
                Esperado.AMBIGUA -> {
                    assertThat(agiuNoDescartado).isEqualTo(0)
                    assertThat(bloqueouCorrecao).isEqualTo(casos)
                }
            }
        }

        println(
            "PROBE-RESUMO|complementoSemDeterminante|casos=$totalCasos|" +
                "agiuNoDescartado=$totalAgiuNoDescartado|violacao=$totalViolacao|" +
                "bloqueouCorrecao=$totalBloqueouCorrecao|" +
                "continuacaoDerrubada=$totalContinuacaoDerrubada|ambigua=$totalAmbigua",
        )
        assertThat(totalViolacao).isEqualTo(0)
        assertThat(totalContinuacaoDerrubada).isEqualTo(0)
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
     * seguro (o app pergunta em vez de agir no descartado). As duas listas ficam presas por
     * igualdade: a primeira em zero, a segunda na família inteira.
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
        assertThat(semCopulaBloqueados).isEqualTo(0)
        assertThat(comCopulaBloqueados).isEqualTo(comCopulaCasos)
    }

    /**
     * O CUSTO ACEITO da `CONTENT_CUE` dentro do ALVO, declarado com o número exato.
     *
     * A âncora do conector casa a palavra em QUALQUER posição, inclusive dentro do alvo de um
     * comando legítimo — e `espera`/`pera`/`calma` são vocabulário de título de tarefa neste app.
     * Medido com a agenda de uma tarefa só, sem ambiguidade nenhuma:
     *
     *     BASE   (a1d5f58)          7 de 7 executavam
     *     HEAD   (#103, 61da139)    0 de 7 — as 7 passam a responder "Não entendi qual é a tarefa…"
     *
     * `"lista de espera do dentista"` é o caso mais plausível. Este teste é o REGISTRO do custo: a
     * asserção é de igualdade e prende o número em 7, para ele não subir nem cair calado.
     *
     * POR QUE NÃO CONSERTAR (a opção (a), ancorar o conector só DEPOIS do alvo): ela foi medida e
     * REABRE o P0 do #103. O alvo de várias palavras engole a cauda até a vírgula, e sem a vírgula
     * — que é o caminho do Vosk, que não pontua — o conector fica FORA do alvo recortado:
     *
     *     "cancela o remédio de pressão espera, o dentista"   head BLOQUEOU → (a) Cancel("remedio
     *     "já tomei o remédio de pressão calma, o dentista"   de pressao")
     *
     * 10 de 10 casos dessa forma (5 verbos × `espera`/`calma` sobre o alvo de duas palavras) voltam
     * a agir sobre o alvo descartado — a dose errada que o #103 existe para matar. A (a) também
     * desfaz a família declarada da interrupção (`"cancela o médico, espera o resultado"`, presa em
     * [SpeechIntentCorrecaoTest.oCustoDaFamiliaDeInterrupcaoFicaDeclarado]). Zero regressões de ação
     * contra a base, mas 10 contra o head — e o P0 é o critério. Então o custo fica DECLARADO.
     */
    @Test
    fun oCustoDaContentCueDentroDoAlvoFicaDeclarado() {
        val doAlvo = listOf(
            "cancela a lista de espera do dentista",
            "apaga a sala de espera",
            "cancela o tempo de espera",
            "cancela o remédio de espera",
            "cancela comprar pera",
            "já tomei comprar pera e banana",
            "cancela o chá de calma",
        )
        var bloqueados = 0
        val agiram = mutableListOf<String>()
        doAlvo.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (bloqueou(intent)) {
                bloqueados++
            } else if (alvoDe(intent) != null) {
                agiram += "«$frase» → $intent"
            }
        }
        println(
            "PROBE-RESUMO|cueDentroDoAlvo|casos=${doAlvo.size}|bloqueados=$bloqueados|" +
                "agiram=$agiram",
        )
        // O número preso por igualdade: é o custo da troca, e ele não pode mudar em silêncio.
        assertThat(bloqueados).isEqualTo(doAlvo.size)
    }

    /**
     * O defeito PRÉ-EXISTENTE da mesma classe, declarado em vez de calado.
     *
     * `"cancela o médico, não é"` — a correção que ela começou na cópula e abandonou — AGE no alvo
     * descartado nas TRÊS árvores (base `a1d5f58`, head do #103 `61da139` e este lote): o
     * `nomeiaAlvoDepois` esbarra em `if (i >= tokens.size) return false` (`SpeechIntent.kt:211`) e
     * lê "continuação". Medido no classificador real, o desfecho é `Cancel(target=medico)`.
     *
     * Não é regressão deste lote e o fix da `CONTENT_CUE` NÃO o resolve (são eixos diferentes: um
     * é o conector por presença, o outro é o `não` sem cauda). Fica DECLARADO, com o número preso
     * por igualdade, para a próxima pessoa ver o buraco em vez de descobri-lo por acidente — e para
     * que fechá-lo mude este teste de propósito, não em silêncio.
     */
    @Test
    fun aCorrecaoAbandonadaNaCopulaSegueAgindoPreExistente() {
        val frases = listOf(
            "cancela o médico, não é",
            "já tomei o remédio, não é",
            "apaga o remédio, não é",
        )
        var agiramNoDescartado = 0
        frases.forEach { frase ->
            val intent = SpeechIntentClassifier.classify(frase)
            if (alvoDe(intent) != null) {
                agiramNoDescartado++
                println("PROBE-PRE-EXISTENTE|«$frase» → $intent")
            }
        }
        println(
            "PROBE-RESUMO|correcaoAbandonadaNaCopula|casos=${frases.size}|" +
                "agiramNoDescartado=$agiramNoDescartado",
        )
        assertThat(agiramNoDescartado).isEqualTo(frases.size)
    }

    /** As frases exatas do despacho, uma a uma, com o desfecho preso por igualdade. */
    @Test
    fun asFrasesDoDespacho() {
        val frases = listOf(
            "cancela o médico, não é dentista" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "já tomei o remédio de pressão, não é diabetes" to
                SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é ele, é o dentista" to
                SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "já tomei o remédio de pressão, não é de diabetes" to
                SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é o dentista" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é a consulta" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é pra mim" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é ela" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é esse" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não é isso, é amanhã" to
                SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não tenho como" to SpeechIntent.Cancel("medico"),
            "cancela o médico, não quero mais" to SpeechIntent.Cancel("medico"),
            "cancela o médico, não vou poder ir" to SpeechIntent.Cancel("medico"),
            "cancela o médico, não é possível" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
            "cancela o médico, não vou a pé" to SpeechIntent.Cancel("medico"),
            "cancela o médico, não tenho a receita" to SpeechIntent.Unknown(UnsupportedKind.CORRECTION),
        )
        frases.forEach { (frase, esperado) ->
            val obtido = SpeechIntentClassifier.classify(frase)
            println("PROBE-FRASE|«$frase» → $obtido")
            assertThat(obtido).isEqualTo(esperado)
        }
    }
}

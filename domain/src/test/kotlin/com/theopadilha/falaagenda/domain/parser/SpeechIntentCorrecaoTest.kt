package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A correção na própria fala: ela fala, percebe que errou e se corrige sem parar de falar.
 *
 * O defeito medido (`.context/docs/cacada-fala-2026-10-07-correcao.md`, seção P1): o
 * classificador não tinha o conceito de correção e agia sobre o alvo que ela DESCARTou.
 *
 *   "cancela o médico, não, o dentista"  → Cancel(target=medico)  → o app apagava o médico
 *
 * Ela corrigiu para **dentista** e o app apagava o médico. **275 de 300** casos pegaram o alvo
 * descartado. No `Complete` o dano é maior: concluir o remédio errado é dose errada registrada.
 *
 * O desfecho aqui é NÃO AGIR e pedir o nome: o que vem depois do conector pode ser a tarefa
 * ("o dentista"), o dia ("hoje"), a hora ("às três") ou nada — o classificador é puro e não tem
 * como decidir. O app prefere escalar a adivinhar (ver a doutrina de `HybridParser`: "duas
 * expressões de tempo discordam ⇒ escala"), e o pior desfecho deste aplicativo é apagar ou
 * concluir a tarefa errada em silêncio.
 */
class SpeechIntentCorrecaoTest {

    private fun intent(text: String) = SpeechIntentClassifier.classify(text)

    /** Os oito conectores de correção medidos no catálogo. */
    private val conectores = listOf(
        ", não, ",
        ", quer dizer, ",
        ", digo, ",
        ", na verdade, ",
        ", melhor, ",
        ", errei, ",
        ", ao invés disso, ",
        ", em vez disso, ",
    )

    // --- o defeito: com correção, o alvo descartado nunca é o alvo ---------------------

    @Test
    fun cancelaComCorrecaoNaoAgeSobreOAlvoDescartado() {
        conectores.forEach { conector ->
            assertThat(intent("cancela o médico${conector}o dentista"))
                .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        }
    }

    /**
     * O caso mais grave: no remédio, concluir o alvo errado é dose errada registrada.
     *
     * A frase do catálogo (`PROBE-ESP-INTENT`) devolvia `Complete(target=remedio de pressao)`.
     */
    @Test
    fun jaTomeiComCorrecaoNaoAgeSobreOAlvoDescartado() {
        conectores.forEach { conector ->
            assertThat(intent("já tomei o remédio de pressão${conector}o de diabetes"))
                .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        }
    }

    @Test
    fun marcaComoFeitoComCorrecaoNaoAgeSobreOAlvoDescartado() {
        assertThat(intent("marca como feito o remédio de pressão, não, o de diabetes"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    /**
     * "apaga o remédio, não, o de pressão": o `EraseNamed` é o terceiro caminho destrutivo —
     * quem decide o desfecho é a agenda, e com o alvo descartado o app apagaria a tarefa errada.
     * A mesma classe de dano do `Cancel`, e o mesmo desfecho: não agir.
     */
    @Test
    fun apagaComCorrecaoNaoAgeSobreOAlvoDescartado() {
        conectores.forEach { conector ->
            assertThat(intent("apaga o remédio de pressão${conector}o de diabetes"))
                .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        }
    }

    @Test
    fun excluiTiraComCorrecaoNaoAgemSobreOAlvoDescartado() {
        assertThat(intent("exclui a consulta, não, o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("tira o remédio, digo, a vitamina"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    /**
     * O preâmbulo de cortesia não esconde a correção — ela fala assim.
     */
    @Test
    fun cortesiaNaoEscondeACorrecao() {
        assertThat(intent("por favor, cancela o médico, não, o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("Ah, já tomei o remédio, não, o de pressão"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    // --- o outro lado: sem conector, tudo continua exatamente como hoje ----------------

    @Test
    fun semConectorOComandoContinuaIgual() {
        assertThat(intent("cancela o médico")).isEqualTo(SpeechIntent.Cancel("medico"))
        assertThat(intent("já tomei o remédio")).isEqualTo(SpeechIntent.Complete("remedio"))
        assertThat(intent("apaga o remédio")).isEqualTo(SpeechIntent.EraseNamed("remedio"))
        assertThat(intent("cancela o médico, por favor")).isEqualTo(SpeechIntent.Cancel("medico"))
        assertThat(intent("já tomei o remédio.")).isEqualTo(SpeechIntent.Complete("remedio"))
    }

    /**
     * As capturas legítimas com radical de edição — as que a camada de comando não pode roubar.
     * Nenhuma tem conector de correção, e todas continuam nascendo tarefa.
     */
    @Test
    fun capturasLegitimasComRadicalDeEdicaoContinuamTarefa() {
        assertThat(intent("muda o óleo do carro")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("troca a lâmpada da sala")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("me lembra de trocar o remédio")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("mudar o óleo do carro")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("adiar a reunião")).isEqualTo(SpeechIntent.Capture)
    }

    /**
     * A correção DENTRO de um recado é captura, e a captura não se toca: sem gatilho de comando
     * abrindo a fala, o texto segue o caminho de sempre (o título e a data são do parser).
     */
    @Test
    fun correcaoDentroDeUmRecadoContinuaTarefa() {
        assertThat(intent("me lembra de comprar pão, não, leite")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("comprar pão, não, leite")).isEqualTo(SpeechIntent.Capture)
        assertThat(intent("pagar a conta de luz, quer dizer, de água")).isEqualTo(SpeechIntent.Capture)
    }

    /**
     * O gatilho no MEIO da frase continua sendo o verbo de outra oração: a correção não pode
     * virar comando onde não havia comando nenhum.
     */
    @Test
    fun gatilhoNoMeioDaFraseComCorrecaoContinuaTarefa() {
        assertThat(intent("perguntar se a médica cancela a consulta, não, o dentista"))
            .isEqualTo(SpeechIntent.Capture)
    }

    /**
     * A pergunta da agenda com correção de dia: o alvo dela é o dia, não uma tarefa, e a
     * resposta já sai com o dia corrigido — `Ask` não apaga nem conclui nada.
     */
    @Test
    fun perguntaComCorrecaoDeDiaRespondeODiaCorrigido() {
        assertThat(intent("o que tenho hoje, não, amanhã?"))
            .isEqualTo(SpeechIntent.Ask(AskWhen.TOMORROW))
    }

    /**
     * "melhor" e "errei" são as alternativas do conector que podem abrir o gatilho sem a vírgula
     * na frente (a primeira vírgula já foi consumida). Sem elas, a frase voltaria a agir sobre o
     * alvo descartado — o defeito de volta, calado.
     */
    @Test
    fun conectoresSemVirgulaNaFrenteTambemContam() {
        assertThat(intent("cancela o médico, melhor, o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("cancela o médico, errei, o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("cancela o médico, ao invés disso, o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    // --- o custo do "não": a cláusula de RAZÃO não é correção --------------------------
    //
    // O review do PR #83 mediu a regressão: com o "não" tratado como conector sempre, 2400 de
    // 3360 frases que NÃO tinham correção nenhuma deixavam de agir. O caso clínico é o pior —
    // "já tomei o remédio de pressão, não preciso mais" deixava de registrar a dose. O que separa
    // a razão da correção é o que vem depois do "não": uma oração, não um sintagma nominal.

    @Test
    fun clausulaDeRazaoNaoBloqueiaOComando() {
        assertThat(intent("cancela o médico, não vou poder ir"))
            .isEqualTo(SpeechIntent.Cancel("medico"))
        assertThat(intent("cancela o médico, não deu tempo"))
            .isEqualTo(SpeechIntent.Cancel("medico"))
        assertThat(intent("já tomei o remédio de pressão, não preciso mais"))
            .isEqualTo(SpeechIntent.Complete("remedio de pressao"))
        assertThat(intent("apaga o remédio, não quero mais"))
            .isEqualTo(SpeechIntent.EraseNamed("remedio"))
        assertThat(intent("cancela a consulta, não posso agora"))
            .isEqualTo(SpeechIntent.Cancel("consulta"))
        assertThat(intent("já tomei o remédio, não esqueci"))
            .isEqualTo(SpeechIntent.Complete("remedio"))
    }

    /**
     * O RESIDUAL declarado de "melhor": ele fica no gatilho largo (o review o listou como
     * conector de conteúdo, sem a ambiguidade do "não"), e por isso `"cancela o médico, melhor
     * semana que vem"` continua bloqueando. É medido e preso aqui para a troca ser explícita.
     *
     * A direção é a segura, e o desfecho é defensável: ela disse "melhor semana que vem", ou
     * seja, NÃO cancele agora — a pergunta do app ("Não entendi qual é a tarefa") deixa o comando
     * sem efeito em vez de apagar o médico. O que fica registrado é que é o gatilho, e não a
     * intenção dela, que decide — e que separar "melhor, o dentista" de "melhor semana que vem"
     * pediria a mesma análise nominal-vs-oração que o "não" já faz (e "semana" é substantivo, de
     * modo que a análise sozinha não bastaria).
     */
    @Test
    fun melhorComCaudaDeTempoContinuaBloqueado() {
        assertThat(intent("cancela o médico, melhor semana que vem"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("cancela o médico, melhor, o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    /**
     * A correção por CONTEÚDO depois de uma cópula continua bloqueando: é o que um gatilho só de
     * "oração" deixaria passar. `"não é o de pressão"` — o que segue nomeia a tarefa.
     */
    @Test
    fun correcaoPorConteudoDepoisDaCopulaContinuaBloqueada() {
        assertThat(intent("já tomei o remédio de pressão, não é o de diabetes"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("cancela o médico, não é o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    /**
     * O "não" SEM a segunda vírgula — `"cancela o medico, nao o dentista"` — é a fala mais
     * provável dela e continua bloqueando. Era exatamente o caso que a variante do review
     * (exigir a vírgula de fechamento, `\s*,`) deixava voltar a agir sobre o alvo descartado:
     * a dose errada que este PR existe para matar.
     */
    @Test
    fun correcaoSemASegundaVirgulaContinuaBloqueada() {
        assertThat(intent("cancela o médico, não o dentista"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("já tomei o remédio de pressão, não o de diabetes"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("apaga o remédio, não a vitamina"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    /**
     * Os conectores de correção que o catálogo não media e o review apontou como faltantes. São
     * ela voltando atrás no que acabou de dizer — vocabulário plausível de quem fala.
     */
    @Test
    fun conectoresNaturaisQueFaltavamTambemBloqueiam() {
        listOf(
            "cancela o médico, esquece, o dentista",
            "cancela o médico, peraí, o dentista",
            "cancela o médico, deixa pra lá, o dentista",
            "cancela o médico, mentira, o dentista",
            "cancela o médico, tá errado, o dentista",
            "cancela o médico, corrigindo, o dentista",
            "cancela o médico, quis dizer, o dentista",
            "cancela o médico, me enganei, o dentista",
            "cancela o médico, aliás, o dentista",
            "cancela o médico, desculpa, o dentista",
        ).forEach { frase ->
            assertThat(intent(frase)).isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        }
    }

    /**
     * A família que o comentário de [SpeechIntentClassifier] declarava fora e que TAMBÉM volta
     * atrás: "espera", "calma", "ops", "pera" e "deixa eu ver".
     *
     * A caçada de 07/10 mediu o dano ponta-a-ponta em Robolectric, com a agenda `{Médico,
     * Dentista}`: `"cancela o médico, espera, o dentista"` respondia "Tarefa excluída." e deixava
     * `restam=[Dentista]` — o MÉDICO tinha sido apagado. No `Complete` o dano é o de sempre e o
     * maior do app: `"já tomei o remédio, espera, o de pressão"` registrava a dose errada.
     *
     * A cobertura era ZERO: o `grep` dos testes só achava `"peraí"`, que já estava na lista.
     */
    @Test
    fun asInterrupcoesDeCorrecaoQueFaltavamTambemBloqueiam() {
        listOf(
            "cancela o médico, espera, o dentista",
            "cancela o médico, calma, o dentista",
            "cancela o médico, ops, o dentista",
            "cancela o médico, pera, o dentista",
            "cancela o médico, deixa eu ver, o dentista",
        ).forEach { frase ->
            assertThat(intent(frase)).isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        }
        // O caso mais caro: a dose errada registrada.
        assertThat(intent("já tomei o remédio, espera, o de pressão"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
        assertThat(intent("apaga o remédio, espera, o de pressão"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CORRECTION))
    }

    /**
     * O outro lado da interrupção: o "depois" NÃO é correção — é a continuação ("cancela o médico,
     * depois eu vejo"), e bloqueá-lo custaria o comando certo. Fica de fora da lista de propósito,
     * e o custo de tê-lo fora é este teste.
     */
    @Test
    fun aContinuacaoComDepoisNaoBloqueiaOComando() {
        assertThat(intent("cancela o médico, depois eu vejo")).isEqualTo(SpeechIntent.Cancel("medico"))
        assertThat(intent("cancela o médico, agora eu vejo")).isEqualTo(SpeechIntent.Cancel("medico"))
        assertThat(intent("cancela o médico, então eu vejo")).isEqualTo(SpeechIntent.Cancel("medico"))
    }

    /**
     * O CUSTO da família da interrupção, medido — a metade que o coordenador mandou medir.
     *
     * Diferente do `não`, o gatilho de [SpeechIntentClassifier] para essas palavras é por PRESENÇA
     * (elas estão em `CONTENT_CUE`), sem veredito de alvo. A consequência é que uma continuação que
     * use a palavra como VERBO de outra oração também bloqueia: `"cancela o médico, espera o
     * resultado"` deixa de cancelar e o app pergunta.
     *
     * O desfecho é o lado seguro — deixa de agir, não age sobre o alvo descartado —, e o custo fica
     * DECLARADO aqui em vez de escondido. A asserção é a igualdade: o número não pode subir calado.
     *
     * O que este teste também prende é que a família clínica não tem nenhuma dessas palavras, e por
     * isso não é tocada (ver [clausulaDeRazaoNaoBloqueiaOComando]).
     */
    private val CUSTO_DA_FAMILIA_DE_INTERRUPCAO = 5

    @Test
    fun oCustoDaFamiliaDeInterrupcaoFicaDeclarado() {
        val custosas = listOf(
            "cancela o médico, espera o resultado",
            "cancela o médico, calma que eu resolvo",
            "cancela o médico, pera aí que eu já volto",
            "cancela o médico, ops esqueci",
            "cancela o médico, deixa eu ver o papel",
        )
        var bloqueadas = 0
        custosas.forEach { frase ->
            if (intent(frase) == SpeechIntent.Unknown(UnsupportedKind.CORRECTION)) bloqueadas++
        }
        println("PROBE-RESUMO|custoDaInterrupcao|casos=${custosas.size}|bloqueadas=$bloqueadas")
        assertThat(bloqueadas).isEqualTo(CUSTO_DA_FAMILIA_DE_INTERRUPCAO)
    }
}

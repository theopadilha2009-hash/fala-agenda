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
}

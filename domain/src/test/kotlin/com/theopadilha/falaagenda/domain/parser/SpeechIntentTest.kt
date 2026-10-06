package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A classificação da fala em recado novo ou intenção de comando.
 *
 * Os casos que falham aqui são os da auditoria: hoje todos viram tarefa. "cancela o médico"
 * cria a tarefa "Cancela o médico" e ela acredita que cancelou. Os falsos positivos, do outro
 * lado, são o que a camada nova NÃO pode roubar: "Tomar remédio às 8" é uma tarefa de verdade e
 * continua sendo.
 */
class SpeechIntentTest {

    private fun intent(text: String) = SpeechIntentClassifier.classify(text)

    // --- os casos da auditoria -------------------------------------------------------

    @Test
    fun cancelaOMedicoViraComandoDeCancelar() {
        val intent = intent("cancela o médico")
        assertThat(intent).isInstanceOf(SpeechIntent.Cancel::class.java)
        // O alvo sai dobrado (sem acento): é a forma que o casamento com os títulos usa.
        assertThat((intent as SpeechIntent.Cancel).target).isEqualTo("medico")
    }

    @Test
    fun jaTomeiViraComandoDeConcluir() {
        val intent = intent("já tomei")
        assertThat(intent).isInstanceOf(SpeechIntent.Complete::class.java)
        // Sem alvo: "já tomei" sozinho não diz de quê — quem resolve o alvo é a matcher, e
        // sem nome ela não escolhe nada.
        assertThat((intent as SpeechIntent.Complete).target).isEmpty()
    }

    /**
     * "já tomei o remédio": o alvo sai do resto da frase, sem o artigo.
     */
    @Test
    fun jaTomeiO_RemedioTrazOAlvo() {
        val intent = intent("já tomei o remédio")
        assertThat((intent as SpeechIntent.Complete).target).isEqualTo("remedio")
    }

    /**
     * "apaga isso" é destrutivo e não aponta nome nenhum: o app NÃO pode escolher no chute.
     * Ele reconhece a intenção de apagar e diz que ainda não sabe fazer — o que ele não pode
     * é criar a tarefa "Apaga isso".
     */
    @Test
    fun apagaIssoEhReconhecidoENaoExecutado() {
        assertThat(intent("apaga isso"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.ERASE))
    }

    @Test
    fun oQueTenhoHojeViraPerguntaDeHoje() {
        assertThat(intent("o que tenho hoje?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    @Test
    fun oQueTenhoAmanhaViraPerguntaDeAmanha() {
        assertThat(intent("o que tenho amanhã?")).isEqualTo(SpeechIntent.Ask(AskWhen.TOMORROW))
    }

    @Test
    fun oQueTemHojeMarcadoViraPergunta() {
        assertThat(intent("o que tem hoje marcado?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    @Test
    fun quaisOsCompromissosDeHojeViraPergunta() {
        assertThat(intent("quais os compromissos de hoje?")).isEqualTo(SpeechIntent.Ask(AskWhen.TODAY))
    }

    @Test
    fun temAlgoAmanhaViraPerguntaDeAmanha() {
        assertThat(intent("tem algo amanhã?")).isEqualTo(SpeechIntent.Ask(AskWhen.TOMORROW))
    }

    /**
     * "muda pra quinta" muda uma tarefa que já existe. O app ainda não faz isso, e o certo é
     * dizer que não sabe — não criar a tarefa "Muda pra", que era o que acontecia.
     */
    @Test
    fun mudaPraQuintaEhReconhecidoENaoExecutado() {
        assertThat(intent("muda pra quinta"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CHANGE))
    }

    @Test
    fun remarcaAConsultaEhReconhecidoENaoExecutado() {
        assertThat(intent("remarca a consulta"))
            .isEqualTo(SpeechIntent.Unknown(UnsupportedKind.CHANGE))
    }

    // --- falsos positivos: isto é captura --------------------------------------------

    @Test
    fun tomarRemedioAsOitoContinuaTarefa() {
        assertThat(intent("Tomar remédio às 8")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun consultaMedicaTercaContinuaTarefa() {
        assertThat(intent("Consulta médica terça")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun comprarRemedioContinuaTarefa() {
        assertThat(intent("Comprar remédio")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun tenhoQueTomarRemedioContinuaTarefa() {
        assertThat(intent("tenho que tomar remédio às 8")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun mudarOleoDoCarroContinuaTarefa() {
        assertThat(intent("mudar o óleo do carro")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun adiarAReuniaoContinuaTarefa() {
        assertThat(intent("adiar a reunião")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun cancelarAConsultaContinuaTarefa() {
        // "me lembra de cancelar a consulta" é a captura; o infinitivo não é imperativo.
        assertThat(intent("me lembra de cancelar a consulta")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun concluirAFaculdadeContinuaTarefa() {
        assertThat(intent("concluir a faculdade")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun pagarAContaContinuaTarefa() {
        assertThat(intent("pagar a conta de luz amanhã")).isEqualTo(SpeechIntent.Capture)
    }

    @Test
    fun textoVazioEhCaptura() {
        assertThat(intent("   ")).isEqualTo(SpeechIntent.Capture)
    }

    // --- a regra de ouro: na dúvida, captura -----------------------------------------

    @Test
    fun tenhoAlgoMarcadoComODentistaContinuaTarefa() {
        // "tenho algo" sozinho não pergunta: sem o dia/indefinido de pergunta, é afirmação.
        assertThat(intent("tenho algo marcado com o dentista")).isEqualTo(SpeechIntent.Capture)
    }
}

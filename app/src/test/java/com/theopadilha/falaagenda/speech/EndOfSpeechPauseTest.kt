package com.theopadilha.falaagenda.speech

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pausa no meio da frase não pode fechar o recado.
 *
 * O endpointer do Vosk é o do Kaldi, e a regra que dispara primeiro (`rule2`) fecha com
 * 0,5 s de silêncio depois de uma palavra que parece final. Para quem fala devagar e pausa
 * no meio ("tomar… remédio… de pressão") isso entrega um recado pela metade. Aqui mora a
 * espera que mantém a escuta aberta depois do sinal do motor.
 *
 * O relógio é injetado de propósito: a espera é medida em milissegundos de silêncio
 * contínuo, e o teste não pode depender do tempo de parede para afirmar isso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndOfSpeechPauseTest {
    private var agora = 0L
    private val pausa = EndOfSpeechPause(clock = { agora })

    /** O caso medido: meio segundo de pausa é o limiar do Vosk, e não o fim do recado. */
    @Test
    fun aPausaCurtaQueOVoskJaAceitaNaoFechaORecado() {
        pausa.speechHeard()
        assertThat(pausa.silenceLastedEnough()).isFalse()

        agora = 500
        assertThat(pausa.silenceLastedEnough()).isFalse()
    }

    @Test
    fun oSilencioSoFechaDepoisDoMinimoInteiro() {
        pausa.speechHeard()
        pausa.silenceLastedEnough()

        agora = MINIMUM_PAUSE_MS - 1
        assertThat(pausa.silenceLastedEnough()).isFalse()

        agora = MINIMUM_PAUSE_MS
        assertThat(pausa.silenceLastedEnough()).isTrue()
    }

    /** Ela voltou a falar: a contagem recomeça, e o que disser entra no mesmo recado. */
    @Test
    fun falaQueVoltaRecomecaAContagem() {
        pausa.speechHeard()
        pausa.silenceLastedEnough()

        agora = MINIMUM_PAUSE_MS - 100
        assertThat(pausa.silenceLastedEnough()).isFalse()

        pausa.speechHeard() // "remédio"
        agora += MINIMUM_PAUSE_MS - 100
        assertThat(pausa.silenceLastedEnough()).isFalse()

        agora += MINIMUM_PAUSE_MS
        assertThat(pausa.silenceLastedEnough()).isTrue()
    }

    /** A contagem começa no primeiro sinal do motor, não no relógio do processo. */
    @Test
    fun oSilencioContaAPartirDoPrimeiroSinal() {
        agora = 10_000
        assertThat(pausa.silenceLastedEnough()).isFalse()

        agora = 10_000 + MINIMUM_PAUSE_MS
        assertThat(pausa.silenceLastedEnough()).isTrue()
    }

    /**
     * Durante a pausa o Vosk repete o mesmo parcial a cada leitura do microfone. Recontar a
     * espera a cada repetição seria esperar para sempre — o recado só sairia pelo prazo do
     * controller, cortado, que é justamente o defeito que esta espera existe para consertar.
     */
    @Test
    fun oParcialRepetidoNaoReiniciaAEsperaNemVaiDeNovoParaATela() {
        val utterance = VoskUtterance(pausa)

        assertThat(utterance.onPartial("tomar")).isEqualTo("tomar")
        // Primeiro aviso do motor: é aqui que a contagem começa.
        assertThat(utterance.mayClose()).isFalse()

        agora += MINIMUM_PAUSE_MS - 100
        // A mesma string, lida de novo: não é fala nova, e a contagem não recomeça.
        assertThat(utterance.onPartial("tomar")).isNull()
        assertThat(utterance.mayClose()).isFalse()

        agora += 200
        assertThat(utterance.mayClose()).isTrue()
    }

    /** Ela voltou a falar: a espera recomeça, e o parcial novo entra no mesmo recado. */
    @Test
    fun aFalaNovaReiniciaAEsperaEVoltaParaATela() {
        val utterance = VoskUtterance(pausa)

        utterance.onPartial("tomar")
        assertThat(utterance.mayClose()).isFalse()
        agora += MINIMUM_PAUSE_MS
        assertThat(utterance.mayClose()).isTrue()

        // "remédio" chegou: o recado não acabou, e a espera conta de novo a partir daqui.
        assertThat(utterance.onPartial("tomar remédio")).isEqualTo("tomar remédio")
        assertThat(utterance.mayClose()).isFalse()
        agora += MINIMUM_PAUSE_MS
        assertThat(utterance.mayClose()).isTrue()
    }

    /** Nada ouvido não é recado cortado: é a espera sem fala, e aí o motor fecha como sempre. */
    @Test
    fun semNadaOuvidoOMotorFechaNaHora() {
        val utterance = VoskUtterance(pausa)

        assertThat(utterance.onPartial("")).isNull()
        assertThat(utterance.mayClose()).isTrue()
    }
}

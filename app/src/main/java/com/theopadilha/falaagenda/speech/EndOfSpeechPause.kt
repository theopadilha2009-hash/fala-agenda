package com.theopadilha.falaagenda.speech

import android.os.SystemClock

/**
 * Quanto de silêncio contínuo ainda se espera depois que o motor já sinalizou o fim da fala.
 *
 * O endpointer do Vosk é o do Kaldi, e a regra que dispara primeiro
 * (`--endpoint.rule2.min-trailing-silence`, 0,5 s) fecha assim que a última palavra parece
 * um estado final. Meio segundo de pausa basta. Para quem fala devagar e pausa no meio da
 * frase — "tomar… remédio… de pressão" — isso entrega um recado pela metade, e nada dizia
 * a ela que o app tinha parado de ouvir.
 *
 * A espera não troca o motor: `acceptWaveForm` só *avisa* que o endpointer achou silêncio,
 * e quem decide encerrar é este arquivo. Enquanto o silêncio não dura o mínimo, a escuta
 * continua aberta e o que ela disser em seguida entra no mesmo recognizer — o resultado
 * final sai com tudo.
 */
internal class EndOfSpeechPause(
    private val minimumMs: Long = MINIMUM_PAUSE_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var silenceStartedAt: Long? = null

    /** Chegou fala nova: a pausa que estava contando não era o fim do recado. */
    fun speechHeard() {
        silenceStartedAt = null
    }

    /**
     * O motor avisou que a fala acabou. Devolve `true` só quando esse silêncio já dura o
     * mínimo; antes disso a escuta continua e o recado não fecha.
     */
    fun silenceLastedEnough(): Boolean {
        val started = silenceStartedAt
        if (started == null) {
            silenceStartedAt = clock()
            return false
        }
        return clock() - started >= minimumMs
    }
}

// atalho: o teto continua sendo o LISTENING_TIMEOUT_MS do controller (20 s), rearmado a cada
// parcial; revisitar quando a queixa de corte no meio da frase voltar — o número a subir é
// este, e não o do controller
internal const val MINIMUM_PAUSE_MS = 2_500L

/**
 * O que a escuta do Vosk já ouviu, e se a pausa de agora pode fechar o recado.
 *
 * O parcial fica *parado* durante a pausa — o Vosk repete a mesma string a cada leitura do
 * microfone. Recontar a espera a cada leitura seria esperar para sempre (e o recado só sairia
 * pelo prazo do controller, cortado): por isso só a fala nova reinicia a contagem, e é o
 * mesmo teste que decide se há o que emitir para a tela.
 */
internal class VoskUtterance(private val pause: EndOfSpeechPause = EndOfSpeechPause()) {
    private var heard = ""

    /** O parcial novo, ou `null` quando é a repetição do mesmo texto (ou silêncio). */
    fun onPartial(partial: String): String? {
        if (partial.isBlank() || partial == heard) return null
        heard = partial
        pause.speechHeard()
        return partial
    }

    /**
     * O endpointer achou silêncio. Fecha o recado quando o silêncio já dura o mínimo — ou
     * quando nada foi ouvido, que não é um recado cortado e sim a espera sem fala.
     */
    fun mayClose(): Boolean = heard.isBlank() || pause.silenceLastedEnough()
}
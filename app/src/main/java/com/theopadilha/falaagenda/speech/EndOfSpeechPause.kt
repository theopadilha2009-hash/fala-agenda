package com.theopadilha.falaagenda.speech

import android.os.SystemClock

/**
 * Quanto de silêncio contínuo ainda se espera depois que o motor já sinalizou o fim da fala.
 *
 * O endpointer do Vosk é o do Kaldi, e o aviso que ele dá é o da **primeira** regra que
 * disparar (`kaldi/src/online2/online-endpoint.h`):
 *
 * | regra | silêncio | quando |
 * |---|---|---|
 * | `rule2` | 0,5 s | a última palavra parece um estado final |
 * | `rule3` | 1,0 s | houve fala, sem estado final |
 * | `rule4` | 2,0 s | houve fala, e nada foi reconhecido como final |
 * | `rule1` | 5,0 s | desde o começo do áudio, sem fala nenhuma |
 *
 * O relógio daqui começa no **primeiro** aviso, então qual regra disparou muda o total até
 * o recado fechar: ~3,0 s para fala confiante (rule2 + 2,5 s), ~3,5 s para a hesitante
 * (rule3) e até ~4,5 s no rule4. Meio segundo de pausa bastava para cortar "tomar… remédio…
 * de pressão" no primeiro "tomar", e nada dizia a ela que o app tinha parado de ouvir — é
 * por isso que a espera agora passa dos 2800 ms de silêncio do motor do sistema.
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

// atalho: sem teto próprio — quem corta é o LISTENING_TIMEOUT_MS do controller (20 s); é ele que
// sobe se a queixa de corte no meio da frase voltar. O prazo é rearmado em `onSpeechBegin` e em
// `onPartial` (e este só emite quando a string muda): para quem fala sem parar ele é empurrado
// para frente a cada parcial e nunca vence, e para quem para no meio ele conta do último parcial
// — ou do `onReady`, se nunca houve parcial —, não de "20 s de escuta".
internal const val MINIMUM_PAUSE_MS = 2_500L

/**
 * O que a escuta do Vosk já ouviu, e se a pausa de agora pode fechar o recado.
 *
 * O parcial **tende** a ficar parado durante a pausa — a mesma string relida a cada leitura do
 * microfone —, e recontar a espera a cada leitura seria esperar para sempre (o recado só sairia
 * pelo prazo do controller, cortado). Por isso só a fala nova reinicia a contagem, e é o mesmo
 * teste que decide se há o que emitir para a tela.
 *
 * O que **não** está provado em aparelho é se o `partialResult` do Vosk chega idêntico durante a
 * pausa ou se oscila. Se ele oscilar — uma revisão do mesmo enunciado, e não fala nova —, cada
 * revisão entra aqui como fala nova e a espera recomeça. O desfecho é gracioso (espera mais, não
 * volta ao defeito do corte), mas o teste não prende isso: o que ele prende é o caso da string
 * idêntica.
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
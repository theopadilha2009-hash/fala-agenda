package com.theopadilha.falaagenda.platform

import android.media.AudioManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/**
 * O mínimo do volume de ALARME como o aparelho dele responde de verdade.
 *
 * O `ShadowAudioManager` do Robolectric **não implementa** `getStreamMinVolume` (conferido por
 * `javap` na 4.14.1: só há `getStreamMaxVolume`, `getStreamVolume`, `getStreamVolumeDb`,
 * `setStreamVolume`, `setStreamMaxVolume`, `isStreamMute` e `setIsStreamMute`). Sem o método, a
 * chamada resolve para o default da instrumentação — zero —, e no teste o mínimo do alarme fica
 * **0** enquanto no aparelho é **1** (`MIN_STREAM_VOLUME[STREAM_ALARM]`, Android 9+).
 *
 * A consequência disso não é cosmética: com o mínimo valendo zero no ambiente de teste, o
 * critério `volume <= minimo` e o critério `volume == 0` ficam **indistinguíveis** — o mutante
 * que troca um pelo outro sobrevive à suíte inteira, e o teste que afirma "volume 1 ainda é
 * saudável" passa verde por acidente, medindo o eixo errado. Modelar o mínimo real é o que dá
 * ao teste o poder de separar os dois.
 *
 * O valor é 1 e não 0 porque é o que o AOSP traz: `MIN_STREAM_VOLUME` tem `1 // STREAM_ALARM`, e
 * sem `MODIFY_AUDIO_SETTINGS` o app é chamador não-privilegiado, cujo `mIndexMin` nasce em
 * `MIN_STREAM_VOLUME * 10` e só é rebaixado se a curva do alarme cruzar -36 dB — a curva do
 * alto-falante (`0,-2970 / 33,-2010 / 66,-1020 / 100,0`) fica toda acima disso, então o mínimo
 * permanece 1.
 */
@Implements(AudioManager::class)
class ShadowAudioManagerComMinimoDeAlarme : ShadowAudioManager() {

    @Implementation
    protected fun getStreamMinVolume(streamType: Int): Int = MINIMO_DO_ALARME

    companion object {
        /** O mínimo do stream de alarme no aparelho: 1, não 0. */
        const val MINIMO_DO_ALARME = 1
    }
}

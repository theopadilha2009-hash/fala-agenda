package com.theopadilha.falaagenda.reminders

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private const val TAG = "VozDoAparelho"

/** O português do Brasil é a língua dela, e é a única que este aplicativo fala. */
internal val LOCALE_DA_VOZ: Locale = Locale.forLanguageTag("pt-BR")

/**
 * A velocidade da fala. Um pouco abaixo do normal: quem ouve aqui é uma pessoa idosa, e a frase
 * mais importante do aplicativo não é lugar de pressa.
 */
internal const val VELOCIDADE_DA_VOZ = 0.85f

/**
 * A voz que fala de verdade, por cima do `TextToSpeech` do aparelho.
 *
 * O motor do aparelho, e não um arquivo de áudio empacotado, pelo mesmo motivo do som do #77: é a
 * voz que ela já conhece do sistema, não engorda o APK, e continua sendo a que ela escolheria nos
 * Ajustes de acessibilidade. Uma gravação nossa teria que ser baixada e não soaria como o celular
 * dela.
 *
 * O `AudioAttributes` sobe em [AudioAttributes.USAGE_ALARM] — o mesmo raciocínio do canal do
 * lembrete. Com `USAGE_MEDIA` a frase sairia no volume de mídia, que costuma estar baixo ou mudo,
 * e um lembrete de remédio que sai mudo é o defeito que este trabalho existe para consertar.
 *
 * A fala é enfileirada com `QUEUE_ADD` de propósito: o aviso substitui a fala anterior por fora
 * (ver [AvisoFalado]), e uma troca no meio da frase não pode engolir a repetição que ainda vai
 * sair.
 */
internal class VozDoAparelho(context: Context) : SintetizadorDeVoz {
    private val appContext = context.applicationContext
    private var motor: TextToSpeech? = null
    private var pronto: ((Boolean) -> Unit)? = null
    private var aoTerminar: ((String) -> Unit)? = null
    private var solto = false

    init {
        motor = TextToSpeech(appContext) { status ->
            val resposta = pronto ?: return@TextToSpeech
            pronto = null
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "O aparelho não tem sintetizador de voz (status $status)")
                resposta(false)
                return@TextToSpeech
            }
            resposta(configurar())
        }
    }

    /**
     * Deixa o motor do jeito que esta voz precisa, e diz se ele ficou utilizável.
     *
     * A checagem de idioma é o que separa "o aparelho tem TTS" de "o aparelho sabe falar com ela".
     * Sem ela, um aparelho só com inglês instalado aceitaria a frase e leria "Está na hora do seu
     * remédio" com sotaque de quem não sabe o que está dizendo — ou não leria nada.
     */
    private fun configurar(): Boolean {
        val tts = motor ?: return false
        val idioma = try {
            tts.setLanguage(LOCALE_DA_VOZ)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível definir o idioma da voz", e)
            return false
        }
        if (idioma == TextToSpeech.LANG_MISSING_DATA || idioma == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "O aparelho não tem voz em português do Brasil (resultado $idioma)")
            return false
        }
        // `isLanguageAvailable` é a segunda pergunta porque a primeira tem uma armadilha: um motor
        // com a língua instalada mas sem NENHUMA voz dela responde OK ao `setLanguage` e não fala.
        val falavel = try {
            tts.isLanguageAvailable(LOCALE_DA_VOZ)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível consultar o idioma da voz", e)
            return false
        }
        if (falavel < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "O aparelho não tem voz em português do Brasil (disponibilidade $falavel)")
            return false
        }
        val vozEscolhida = escolherVoz(tts)
        if (vozEscolhida != null) {
            try {
                tts.voice = vozEscolhida
            } catch (e: Exception) {
                // Não é motivo para calar: a voz padrão do idioma serve.
                Log.w(TAG, "Não foi possível escolher a voz ${vozEscolhida.name}", e)
            }
        }
        tts.setSpeechRate(VELOCIDADE_DA_VOZ)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                aoTerminar?.invoke(utteranceId.orEmpty())
            }

            /** O motor recusou ou falhou no meio: sem este aviso a política esperaria para sempre. */
            @Deprecated("Assinatura exigida pela classe base")
            override fun onError(utteranceId: String?) {
                aoTerminar?.invoke(utteranceId.orEmpty())
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "A fala $utteranceId falhou (código $errorCode)")
                aoTerminar?.invoke(utteranceId.orEmpty())
            }
        })
        return true
    }

    /**
     * A voz local da língua, se houver alguma. Sem esta escolha o motor pode cair numa voz de rede,
     * e a frase do remédio depende de internet no horário em que ela mais precisa dela.
     */
    private fun escolherVoz(tts: TextToSpeech): Voice? = try {
        tts.voices.orEmpty()
            .filter { it.locale.language == LOCALE_DA_VOZ.language }
            .filterNot { it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
    } catch (e: Exception) {
        Log.w(TAG, "Não foi possível listar as vozes do aparelho", e)
        null
    }

    override fun quandoPronto(bloco: (Boolean) -> Unit) {
        val tts = motor
        // O `onInit` pode ter chegado antes de alguém perguntar. Quem pergunta depois recebe a
        // resposta na hora — e é por isso que `falar` de novo funciona depois do primeiro disparo.
        if (tts == null || solto) {
            bloco(false)
            return
        }
        val disponivel = try {
            tts.isLanguageAvailable(LOCALE_DA_VOZ) >= TextToSpeech.LANG_AVAILABLE
        } catch (e: Exception) {
            false
        }
        if (disponivel) bloco(true) else pronto = bloco
    }

    override fun falar(texto: String, id: String, aoTerminar: (String) -> Unit): Boolean {
        val tts = motor ?: return false
        this.aoTerminar = aoTerminar
        return try {
            // O `speak` é sincrono na recusa: ele devolve o código de erro na hora e não fala nada.
            // Um motor com a voz corrompida, um idioma listado sem dado de fala, o motor ocupado —
            // o mesmo caminho que o `catch` abaixo, só que sem exceção. Sem olhar o retorno, o fim
            // da fala era avisado e a barra anunciava uma voz que não existiu.
            val resultado = tts.speak(texto, TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (resultado == TextToSpeech.ERROR) {
                Log.w(TAG, "O motor recusou a fala $id")
                aoTerminar(id)
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível falar a fala $id", e)
            aoTerminar(id)
            false
        }
    }

    override fun parar() {
        try {
            motor?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível interromper a fala", e)
        }
    }

    override fun soltar() {
        if (solto) return
        solto = true
        pronto = null
        aoTerminar = null
        try {
            motor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível fechar o motor de voz", e)
        }
        motor = null
    }
}

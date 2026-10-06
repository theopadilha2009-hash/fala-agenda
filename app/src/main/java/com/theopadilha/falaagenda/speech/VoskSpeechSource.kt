package com.theopadilha.falaagenda.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import org.vosk.Model
import org.vosk.Recognizer

/**
 * O motor offline: microfone cru (16 kHz, mono, PCM 16 bits) direto no Vosk, sem
 * passar pelo serviço de reconhecimento do aparelho — é por isso que funciona sem
 * rede e sem a conta do fabricante.
 *
 * O AudioRecord bloqueia, então a escuta roda em thread própria; os avisos voltam
 * na thread principal, que é onde o controller e a tela vivem.
 *
 * `acceptWaveForm` devolve `true` quando o endpointer do Vosk acha silêncio no fim — mas
 * isso é um aviso, não uma ordem: o recognizer continua decodificando, e quem fecha o
 * recado é o [EndOfSpeechPause]. Fechar no primeiro aviso era o truncamento silencioso —
 * a pausa no meio da frase entregava "tomar" como se fosse o recado inteiro.
 */
class VoskSpeechSource(
    context: Context,
    private val model: () -> Model,
    private val sampleRate: Int = SAMPLE_RATE,
    /** Avisa quem emprestou o modelo que esta escuta acabou — ver `VoskOfflineSpeech`. */
    private val onFinished: () -> Unit = {},
) : SpeechSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private val encerrada = AtomicBoolean(false)

    @Volatile private var listener: SpeechSource.Listener? = null

    @Volatile private var record: AudioRecord? = null

    @Volatile private var running = false

    override fun start(listener: SpeechSource.Listener) {
        if (!hasMicrophone()) {
            // Sem escuta de pé não há motivo para o modelo continuar aberto.
            finish()
            listener.onError(VoiceEngine.INSUFFICIENT_PERMISSIONS)
            return
        }
        this.listener = listener
        running = true
        worker = Thread({ transcribe() }, "vosk-escuta").also { it.start() }
    }

    override fun cancel() {
        listener = null
        stop()
    }

    override fun destroy() {
        listener = null
        stop()
    }

    private fun stop() {
        running = false
        releaseRecord()
    }

    /**
     * Avisos só chegam se a escuta ainda é a de agora: um parcial que ficou na fila
     * da thread principal não pode reabrir a tela depois de um cancelar.
     */
    private fun emit(block: (SpeechSource.Listener) -> Unit) {
        main.post {
            listener?.let(block)
        }
    }

    private fun transcribe() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var recognizer: Recognizer? = null
        try {
            recognizer = Recognizer(model(), sampleRate.toFloat())
            val recorder = openRecorder()
            if (recorder == null) {
                emit { it.onError(VoiceRetry.CLIENT) }
                return
            }
            record = recorder
            recorder.startRecording()
            emit { it.onReady() }

            val buffer = ByteArray(BUFFER_BYTES)
            val utterance = VoskUtterance()
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) break // microfone caiu (ou foi liberado por baixo)
                if (read == 0) continue
                val atTheEnd = recognizer.acceptWaveForm(buffer, read)
                utterance.onPartial(VoskOutcome.partial(recognizer.partialResult))?.let { fresh ->
                    emit { it.onPartial(fresh) }
                }
                // O endpointer avisou, e o recado só fecha quando a pausa já dura o mínimo:
                // o que ela disser em seguida continua entrando nesta mesma escuta.
                if (atTheEnd && utterance.mayClose()) {
                    val text = VoskOutcome.text(recognizer.result)
                    emit { it.onEndOfSpeech() }
                    emit { it.onFinal(text) }
                    return
                }
            }
        } catch (_: Exception) {
            // Modelo pela metade, microfone ocupado: quem decide o próximo motor é o
            // controller, não esta thread.
            emit { it.onError(VoiceRetry.CLIENT) }
        } catch (_: LinkageError) {
            // A lib nativa é o caso mais grave, e este catch é o que separa "a fala volta
            // para o motor do sistema" de "o app fecha sozinho". `UnsatisfiedLinkError` não
            // é Exception, e no aparelho a falha de ligação sai do bloco estático do
            // `LibVosk` como `ExceptionInInitializerError` (e na tentativa seguinte, com a
            // classe já marcada, como `NoClassDefFoundError`) — a família toda é
            // `LinkageError`. Sem pegar isto aqui, o erro sobe pela thread `vosk-escuta`,
            // que não tem handler, e em aparelho isso derruba o processo.
            //
            // E ele condena o motor offline pelo resto do processo: a classe que não
            // ligou não liga depois, então tentar de novo a cada toque é só pagar a
            // falha de novo e deixar o microfone lento. Microfone ocupado e modelo pela
            // metade continuam sendo `Exception`, e esses o motor resolve sozinho — quem
            // cai ali segue tentando o offline na escuta seguinte.
            VoiceEngine.condemnOffline()
            emit { it.onError(VoiceRetry.CLIENT) }
        } finally {
            releaseRecord()
            runCatching { recognizer?.close() }
            finish()
        }
    }

    /** Uma vez só por escuta: a contagem do modelo emprestado não pode passar do ponto. */
    private fun finish() {
        if (encerrada.compareAndSet(false, true)) onFinished()
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // start() já barrou sem a permissão; o que sobra é ela ter sido revogada no meio.
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return null
        val size = maxOf(minimum, BUFFER_BYTES * 2)
        val recorder = try {
            AudioRecord(
                // VOICE_RECOGNITION já vem sem o processamento agressivo de voz do telefone.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                size,
            )
        } catch (_: SecurityException) {
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return null
        }
        return recorder
    }

    private fun releaseRecord() {
        val opened = record ?: return
        record = null
        runCatching { if (opened.recordingState == AudioRecord.RECORDSTATE_RECORDING) opened.stop() }
        runCatching { opened.release() }
        worker = null
    }

    private companion object {
        /** 16 kHz é a taxa que o modelo do Vosk espera; outra taxa piora o resultado. */
        const val SAMPLE_RATE = 16_000

        /** ~0,25 s de áudio por leitura, como no exemplo oficial do Vosk. */
        const val BUFFER_BYTES = 8_000
    }
}

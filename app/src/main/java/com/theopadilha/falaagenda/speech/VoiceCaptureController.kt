package com.theopadilha.falaagenda.speech

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoiceState { IDLE, PREPARING, LISTENING, UNDERSTANDING, ERROR }

data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val partial: String = "",
    val finalText: String? = null,
    val error: String? = null,
    val needSystem: Boolean = false,
    /**
     * O recado foi cortado pelo app: o texto entregue veio de um parcial (ou do prazo
     * vencido), não do fim normal da fala dela. Sem esta marca, "tomar" — o primeiro
     * pedaço de "tomar… remédio… de pressão" — chega à tela como se fosse o recado
     * inteiro, e ela confirma uma tarefa que não disse.
     */
    val truncated: Boolean = false,
)

/**
 * Toda a política de escuta mora aqui — prazos, retentativa, troca de motor.
 * Quem transcreve é o [SpeechSource], e o controller não sabe qual é: o do
 * aparelho ou o offline (quando o modelo está instalado).
 */
class VoiceCaptureController(
    private val context: Context,
    private val offline: () -> OfflineSpeech? = { null },
    private val requestOfflineModel: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var source: SpeechSource? = null
    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui

    private var session = false
    private var retries = 0
    private var heardReady = false
    private var startedAt = 0L
    private var hostContext: Context = context
    private var backend = VoiceEngine.Capture.IN_APP_DEFAULT

    /** Resolvido a cada escuta: o modelo pode ter chegado com o app já aberto. */
    private var offlineSpeech: OfflineSpeech? = null

    private val watchdog = Runnable { onWatchdog() }

    private fun armWatchdog(millis: Long) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, millis)
    }

    /** Nenhum estado de escuta pode durar para sempre: se o motor não responde, sai daqui. */
    private fun onWatchdog() {
        if (!session) return
        when (_ui.value.state) {
            VoiceState.PREPARING -> switchOrFail(VoiceRetry.CLIENT)
            VoiceState.LISTENING -> giveUpOnTimeout(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            VoiceState.UNDERSTANDING -> giveUpOnTimeout(SpeechRecognizer.ERROR_NO_MATCH)
            else -> Unit
        }
    }

    /**
     * O prazo venceu. O que já veio reconhecido não se joga fora: perder a fala no meio do
     * recado é pior que ouvir de novo. Sem parcial, aí sim é o erro de sempre.
     *
     * O prazo vencido marca **todo** parcial como cortado, e é possível que o recado estivesse
     * inteiro: o endpointer do Kaldi não avisou dentro dos 20 s (`LISTENING_TIMEOUT_MS`), e o
     * texto que ficou é o último parcial. Ela é então convidada a "falar de novo se faltou
     * algo" sem ter faltado nada.
     *
     * Não dá para distinguir os dois casos com o que existe aqui. "O parcial está completo" não
     * tem sinal: o `partialResult` do Vosk devolve o mesmo texto durante a pausa e depois dela,
     * e um resultado final que não chegou é, por definição, um resultado que não temos. A única
     * testemunha seria o próprio endpointer — que é justamente quem calou.
     *
     * E as duas leituras erradas não custam o mesmo. Marcar um recado íntegro manda ela conferir
     * um texto que já está certo: um convite a mais, que o aviso deixa claro que é opcional.
     * Não marcar um recado cortado é o defeito medido: o parcial "tomar" passa por recado
     * inteiro e vira uma tarefa que ela não disse. Fica o custo menor.
     */
    private fun giveUpOnTimeout(error: Int) {
        val heard = _ui.value.partial.trim()
        // O prazo venceu no meio do recado: o que veio vem marcado, porque a escuta parou
        // de ouvir por conta do app.
        if (heard.isEmpty()) failWith(error) else finishWith(heard, truncated = true)
    }

    fun start(host: Context = context) {
        if (session) stopInternal()
        hostContext = host
        // Por aqui passam todos os pedidos de voz — o botão da home, o atalho ACTION_SPEAK,
        // o "Falar" do widget e o ícone do lançador. É o pedido dela que pede o modelo
        // offline; abrir o app não pede nada. E não segura: `requestOfflineModel` só
        // enfileira o download, enquanto a escuta começa no motor do sistema.
        requestOfflineModel()
        val speechHost = unwrapActivity(host)
        offlineSpeech = offline()
        backend = VoiceEngine.initial(
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(speechHost),
            onDeviceAvailable = onDeviceAvailable(speechHost),
            offlineAvailable = offlineSpeech != null,
        )
        session = true
        retries = 0
        heardReady = false
        startedAt = SystemClock.elapsedRealtime()
        if (backend == VoiceEngine.Capture.SYSTEM_UI) {
            requestSystemUi()
            return
        }
        _ui.value = VoiceUiState(state = VoiceState.PREPARING)
        beginListening()
    }

    fun cancel() {
        session = false
        stopInternal()
        hostContext = context
        _ui.value = VoiceUiState()
    }

    fun consumeFinal() {
        // A marca de corte FICA: o texto já virou rascunho e o microfone volta a IDLE, mas
        // é na espera e na confirmação — depois daqui — que ela precisa saber que a escuta
        // parou antes do fim. Quem apaga é a escuta seguinte (ver `start`).
        _ui.value = VoiceUiState(truncated = _ui.value.truncated)
    }

    fun consumeSystemRequest() {
        _ui.value = _ui.value.copy(needSystem = false)
    }

    fun acceptTranscript(text: String) {
        finishWith(text.trim())
    }

    fun systemListenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Pode falar o recado")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun beginListening() {
        if (!session) return
        destroySource()
        val speechHost = unwrapActivity(hostContext)
        val created = runCatching { newSource(speechHost) }.getOrNull()
        if (created == null) {
            switchOrFail(VoiceRetry.CLIENT)
            return
        }
        source = created
        armWatchdog(PREPARING_TIMEOUT_MS)
        created.start(listener)
    }

    private fun newSource(speechHost: Context): SpeechSource = when (backend) {
        VoiceEngine.Capture.OFFLINE_VOSK -> offlineSpeech!!.newSource()
        VoiceEngine.Capture.IN_APP_ON_DEVICE -> SystemSpeechSource(speechHost, onDevice = true)
        else -> SystemSpeechSource(speechHost, onDevice = false)
    }

    private fun destroySource() {
        source?.cancel()
        source?.destroy()
        source = null
    }

    private fun stopSourceOnly() {
        handler.removeCallbacksAndMessages(null)
        destroySource()
    }

    private fun stopInternal() {
        session = false
        stopSourceOnly()
    }

    private fun finishWith(text: String, truncated: Boolean = false) {
        session = false
        stopSourceOnly()
        val clean = text.trim()
        _ui.value = if (clean.isEmpty()) {
            // Erro com estado IDLE some da tela: a mensagem precisa do estado ERROR para aparecer.
            VoiceUiState(state = VoiceState.ERROR, error = "Não entendi o que foi dito.")
        } else {
            VoiceUiState(state = VoiceState.IDLE, finalText = clean, truncated = truncated)
        }
    }

    private fun requestSystemUi() {
        session = false
        stopSourceOnly()
        _ui.value = VoiceUiState(state = VoiceState.PREPARING, needSystem = true)
    }

    private fun failWith(error: Int) {
        session = false
        stopSourceOnly()
        _ui.value = VoiceUiState(state = VoiceState.ERROR, error = voiceErrorMessage(error))
    }

    private fun switchOrFail(error: Int) {
        val next = VoiceEngine.afterFail(
            error = error,
            heardReady = heardReady,
            current = backend,
            onDeviceAvailable = onDeviceAvailable(unwrapActivity(hostContext)),
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(
                unwrapActivity(hostContext),
            ),
        )
        when (next) {
            VoiceEngine.Capture.OFFLINE_VOSK,
            VoiceEngine.Capture.IN_APP_DEFAULT,
            VoiceEngine.Capture.IN_APP_ON_DEVICE,
            -> {
                backend = next
                retries = 0
                heardReady = false
                startedAt = SystemClock.elapsedRealtime()
                _ui.value = VoiceUiState(state = VoiceState.PREPARING)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ if (session) beginListening() }, 350)
            }
            VoiceEngine.Capture.SYSTEM_UI -> requestSystemUi()
            null -> failWith(error)
        }
    }

    private fun onDeviceAvailable(host: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(host)
    }

    private val listener = object : SpeechSource.Listener {
        override fun onReady() {
            if (!session) return
            heardReady = true
            armWatchdog(LISTENING_TIMEOUT_MS)
            _ui.value = _ui.value.copy(state = VoiceState.LISTENING, error = null)
        }

        override fun onSpeechBegin() {
            // O motor começou a ouvir de fato: o prazo de escuta recomeça daqui.
            if (session) armWatchdog(LISTENING_TIMEOUT_MS)
        }

        override fun onPartial(text: String) {
            if (text.isNotBlank() && session) {
                // Re-arma: quem está falando há mais de 20 s não pode ser cortado no meio.
                // O prazo existe para o motor calado, não para quem está ditando.
                armWatchdog(LISTENING_TIMEOUT_MS)
                _ui.value = _ui.value.copy(state = VoiceState.LISTENING, partial = text)
            }
        }

        override fun onEndOfSpeech() {
            if (session) {
                armWatchdog(UNDERSTANDING_TIMEOUT_MS)
                _ui.value = _ui.value.copy(state = VoiceState.UNDERSTANDING)
            }
        }

        override fun onError(code: Int) {
            if (!session) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            when (VoiceRetry.decide(code, _ui.value.partial, retries, elapsed)) {
                // O parcial venceu o erro: o que ela disse até aqui é salvo, mas é um recado
                // cortado — o motor parou no meio, não ela.
                VoiceRetry.Action.USE_PARTIAL -> finishWith(_ui.value.partial.trim(), truncated = true)
                VoiceRetry.Action.RETRY -> {
                    retries += 1
                    destroySource()
                    handler.removeCallbacksAndMessages(null)
                    heardReady = false
                    // O partial da tentativa anterior não pode virar o texto final da próxima.
                    _ui.value = VoiceUiState(state = VoiceState.PREPARING)
                    handler.postDelayed({ if (session) beginListening() }, 350)
                }
                VoiceRetry.Action.FAIL -> switchOrFail(code)
            }
        }

        override fun onFinal(text: String) {
            if (!session) return
            val used = text.ifBlank { _ui.value.partial }
            // Final vazio é o motor fechando sem resultado: o que sobra é o parcial, e um
            // parcial é sempre um recado cortado.
            finishWith(used.trim(), truncated = text.isBlank() && _ui.value.partial.isNotBlank())
        }
    }

    private companion object {
        const val PREPARING_TIMEOUT_MS = 10_000L
        const val LISTENING_TIMEOUT_MS = 20_000L
        const val UNDERSTANDING_TIMEOUT_MS = 8_000L
    }
}

/**
 * O texto que ela lê quando a escuta falha. Mora fora da classe, como
 * [com.theopadilha.falaagenda.ui.month.emptyFrequentMessage], para o teste poder prendê-lo
 * sem dirigir o `SpeechRecognizer`.
 *
 * Frases curtas e concretas: quem lê é uma pessoa idosa, e "reconhecimento do aparelho" não
 * diz a ela o que fazer. O ramo de rede continua separado dos demais — trocar a causa não
 * era o pedido —, mas agora sem o jargão.
 */
internal fun voiceErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    -> "Não consegui ouvir. Fale mais perto do microfone."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
    -> "Preciso da permissão do microfone para ouvir você."
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    -> "Não consegui ouvir. Tente de novo ou escreva o recado."
    else -> "Não consegui ouvir. Toque de novo ou escreva o recado."
}

internal fun unwrapActivity(context: Context): Context {
    var current: Context = context
    val seen = HashSet<Context>()
    while (current is ContextWrapper) {
        if (current is Activity) return current
        if (!seen.add(current)) break
        current = current.baseContext ?: break
    }
    return context
}

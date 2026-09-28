package com.theopadilha.falaagenda.ui.home

import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val UNDERSTAND_FAILED_MESSAGE =
    "Não consegui entender o recado. Tente de novo ou escreva a tarefa."

/** O que a home mostra da fala: que está entendendo, o que entendeu e o que falhou. */
data class SpeechUiState(
    val understanding: Boolean = false,
    val draft: ParsedTaskDraft? = null,
    val error: String? = null,
)

/**
 * O parse da fala não pertence à tela. Com IA ele leva até ~20 s, e o escopo da composição
 * morria no meio da rotação: o `runCatching` da tela engolia o CancellationException como
 * `null` e o recado sumia sem nem a mensagem de erro chegar. Aqui o estado mora num escopo
 * que a composição não controla — o mesmo tratamento que o download da atualização ganhou
 * em `ui/update/UpdateSession.kt` — então quem volta para a home (ou para o app) encontra
 * o rascunho esperando, em vez de precisar falar tudo de novo.
 */
class SpeechSession(
    private val scope: CoroutineScope,
    private val parse: suspend (String) -> ParsedTaskDraft,
) {
    private val _state = MutableStateFlow(SpeechUiState())
    val state: StateFlow<SpeechUiState> = _state.asStateFlow()
    private val lock = Mutex()

    /** Um recado por vez: falar de novo antes de o anterior chegar atropelaria o parse. */
    fun understand(text: String) {
        val heard = text.trim()
        if (heard.isEmpty()) return
        scope.launch {
            lock.withLock {
                _state.value = SpeechUiState(understanding = true)
                val arrived = try {
                    SpeechUiState(draft = parse(heard))
                } catch (cancelled: CancellationException) {
                    _state.value = SpeechUiState()
                    throw cancelled
                } catch (_: Exception) {
                    SpeechUiState(error = UNDERSTAND_FAILED_MESSAGE)
                }
                _state.value = arrived
            }
        }
    }

    /** A home pegou o rascunho: ele não volta a aparecer numa próxima composição. */
    fun consumeDraft() {
        _state.value = _state.value.copy(draft = null)
    }

    /** Idem para o recado de erro, que fica disponível até alguém mostrá-lo. */
    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }
}

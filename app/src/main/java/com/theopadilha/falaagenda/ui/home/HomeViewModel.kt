package com.theopadilha.falaagenda.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.platform.UpdateCheck
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * A lista e a resposta para "isto já veio do banco?" no mesmo valor.
 *
 * Eram duas assinaturas independentes de `observeAgenda()`, e a resposta de um fluxo valia
 * para a lista do outro: com o app em outra tela, os valores retidos dos dois `stateIn`
 * diziam "carregou" enquanto a lista ainda era a inicial vazia, e o toque no aviso do
 * remédio respondia "Esta tarefa não está mais na agenda" — descartando o id. Quem busca a
 * ocorrência por id lê os dois campos da mesma emissão: [loaded] nunca fala de uma lista
 * que não é [sections].
 */
data class AgendaUi(
    val sections: AgendaSections,
    val loaded: Boolean,
)

/** Antes da primeira emissão: nada de concluir por ausência. */
internal val initialAgendaUi = AgendaUi(
    sections = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
    loaded = false,
)

internal fun agendaUiFrom(source: Flow<AgendaSections>): Flow<AgendaUi> =
    source.map { AgendaUi(sections = it, loaded = true) }

private const val TAG = "FalaAgendaHome"

class HomeViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val agendaUi: StateFlow<AgendaUi> = agendaUiFrom(container.tasks.observeAgenda()).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        initialAgendaUi,
    )

    /**
     * O parse da fala roda no escopo do ViewModel: sobrevive à rotação e a sair da home, e
     * o rascunho fica guardado até a tela mostrá-lo.
     */
    val speech = SpeechSession(scope = viewModelScope, parse = { parse(it) })

    private val _inexactWarning = MutableStateFlow(false)
    val inexactWarning: StateFlow<Boolean> = _inexactWarning

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _availableUpdate = MutableStateFlow<UpdateCheck?>(null)
    val availableUpdate: StateFlow<UpdateCheck?> = _availableUpdate

    /** Recado que o ViewModel não conseguiu entregar: a tela mostra e descarta. */
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.updater.check() }.getOrNull()
            }
            _availableUpdate.value = result?.takeIf { it.newer }
        }
    }

    fun setInexactWarning(value: Boolean) {
        _inexactWarning.value = value
    }

    fun consumeWriteError() {
        _writeError.value = null
    }

    /**
     * Toda escrita passa por aqui: sem try/catch uma exceção de IO derrubava o processo
     * e o usuário não sabia se a tarefa foi salva. Falha sem [onError] cai em [writeError],
     * que a tela mostra — falhar calado não é uma opção.
     */
    private fun <T> write(
        action: String,
        onError: ((String) -> Unit)? = null,
        onSuccess: (T) -> Unit = {},
        block: suspend () -> T,
    ) = viewModelScope.launch {
        _busy.value = true
        try {
            val result = withContext(Dispatchers.IO) { block() }
            onSuccess(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(action, error, onError)
        } finally {
            // Sem isto o botão Salvar segue ativo durante a gravação e dois toques
            // disparam dois editOccurrence concorrentes.
            _busy.value = false
        }
    }

    private fun fail(action: String, error: Exception, onError: ((String) -> Unit)?) {
        val message = "$action Tente de novo."
        Log.w(TAG, action, error)
        if (onError != null) onError(message) else _writeError.value = message
    }

    fun saveDraft(draft: ParsedTaskDraft, onDone: (Boolean) -> Unit, onError: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) { container.tasks.saveDraft(draft) }
                onDone(result.usedInexactAlarm)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                fail("Não consegui salvar o recado.", error, onError)
            } finally {
                _busy.value = false
            }
        }
    }

    fun complete(item: AgendaItem, onDone: () -> Unit = {}) = write(
        action = "Não consegui marcar como feito.",
        onSuccess = {
            lastCompleted = item
            onDone()
        },
    ) { container.tasks.complete(item.occurrence.id) }

    fun undoComplete() {
        val item = lastCompleted ?: return
        lastCompleted = null
        write("Não consegui desfazer.") { container.tasks.uncomplete(item) }
    }

    private var lastCompleted: AgendaItem? = null
    private var lastDeleted: AgendaItem? = null

    /**
     * Exclusão que ainda dá para desfazer. Enquanto isto não for nulo, a home mostra o
     * aviso de "Tarefa excluída" com o botão de desfazer.
     */
    private val _undoableDelete = MutableStateFlow<AgendaItem?>(null)
    val undoableDelete: StateFlow<AgendaItem?> = _undoableDelete

    fun delete(item: AgendaItem, onDeleted: () -> Unit = {}) = write(
        action = "Não consegui excluir.",
        onSuccess = {
            lastDeleted = item
            _undoableDelete.value = item
            onDeleted()
        },
    ) { container.tasks.deleteOccurrence(item.occurrence.id) }

    fun undoDelete() {
        val item = lastDeleted ?: return
        lastDeleted = null
        _undoableDelete.value = null
        write("Não consegui desfazer.") { container.tasks.restore(item) }
    }

    /** O aviso saiu da tela sem desfazer: a exclusão deixou de estar ao alcance. */
    fun forgetUndoDelete() {
        lastDeleted = null
        _undoableDelete.value = null
    }

    fun endSeries(seriesId: String, onDone: () -> Unit = {}) = write(
        action = "Não consegui encerrar a série.",
        onSuccess = { onDone() },
    ) { container.tasks.endSeries(seriesId) }

    fun snooze(id: String, minutes: Long = 30, onDone: (String) -> Unit = {}) = write(
        action = "Não consegui adiar.",
        onSuccess = {
            val at = java.time.ZonedDateTime.now().plusMinutes(minutes)
            onDone(AgendaFormat.announce(at.toLocalDate(), at.toLocalTime().withSecond(0).withNano(0), LocalDate.now()))
        },
    ) { container.tasks.snooze(id, minutes) }

    fun retryMissed(id: String, onDone: (String) -> Unit = {}) = write(
        action = "Não consegui remarcar.",
        onSuccess = { result ->
            if (result == null) {
                onDone("Não deu para remarcar esta tarefa.")
            } else {
                val whenLabel = AgendaFormat.dateLabel(result.date, LocalDate.now()).lowercase()
                onDone("Vai avisar $whenLabel às ${AgendaFormat.time(result.time)}.")
            }
        },
    ) { container.tasks.retryMissed(id) }

    fun edit(
        id: String,
        title: String,
        date: LocalDate,
        time: LocalTime,
        recurrence: RecurrenceRule,
        amountCents: Long? = null,
        observation: String = "",
        onDone: () -> Unit = {},
        onError: ((String) -> Unit)? = null,
    ) = write(
        action = "Não consegui salvar a mudança.",
        onError = onError,
        onSuccess = { onDone() },
    ) {
        container.tasks.editOccurrence(id, title, date, time, recurrence, amountCents, observation)
    }

    fun repeatTomorrow(item: AgendaItem, onDone: (String) -> Unit = {}) {
        val tomorrow = LocalDate.now().plusDays(1)
        val draft = ParsedTaskDraft(
            title = item.series.title,
            localDate = tomorrow,
            localTime = item.series.localTime,
            recurrence = RecurrenceRule(),
            confidence = 1.0,
            missingFields = emptySet(),
            ambiguous = false,
            transcript = "",
            source = DraftSource.MANUAL,
            amountCents = item.series.amountCents,
            observation = item.series.observation,
        )
        saveDraft(draft, onDone = { usedInexact ->
            setInexactWarning(usedInexact)
            onDone(AgendaFormat.announce(tomorrow, item.series.localTime, LocalDate.now()))
        })
    }

    suspend fun parse(text: String): ParsedTaskDraft =
        withContext(Dispatchers.IO) { container.hybridParser.parse(text) }
}

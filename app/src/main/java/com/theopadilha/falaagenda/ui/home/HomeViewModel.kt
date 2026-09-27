package com.theopadilha.falaagenda.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.QuickRemind
import com.theopadilha.falaagenda.platform.UpdateCheck
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

private const val TAG = "FalaAgendaHome"

class HomeViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val agenda = container.tasks.observeAgenda().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        com.theopadilha.falaagenda.data.repo.AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
    )

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
        try {
            val result = withContext(Dispatchers.IO) { block() }
            onSuccess(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(action, error, onError)
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

    fun delete(item: AgendaItem, onDeleted: () -> Unit = {}) = write(
        action = "Não consegui excluir.",
        onSuccess = {
            lastDeleted = item
            onDeleted()
        },
    ) { container.tasks.deleteOccurrence(item.occurrence.id) }

    fun undoDelete() {
        val item = lastDeleted ?: return
        lastDeleted = null
        write("Não consegui desfazer.") { container.tasks.restore(item) }
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

    fun quickRemind(title: String, minutes: Long, onDone: (String) -> Unit = {}) {
        val now = java.time.ZonedDateTime.now()
        val draft = QuickRemind.draft(title, minutes, now)
        if (!draft.isComplete) {
            onDone("Escreva o que precisa lembrar.")
            return
        }
        saveDraft(draft, onDone = { usedInexact ->
            setInexactWarning(usedInexact)
            onDone(AgendaFormat.announce(draft.localDate!!, draft.localTime!!, LocalDate.now()))
        })
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

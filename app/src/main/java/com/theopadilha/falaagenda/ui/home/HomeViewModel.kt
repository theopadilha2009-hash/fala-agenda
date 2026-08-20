package com.theopadilha.falaagenda.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

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

    fun setInexactWarning(value: Boolean) {
        _inexactWarning.value = value
    }

    fun saveDraft(draft: ParsedTaskDraft, onDone: (Boolean) -> Unit, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) { container.tasks.saveDraft(draft) }
                onDone(result.usedInexactAlarm)
            } catch (error: Exception) {
                onError(error.message ?: "Não foi possível salvar.")
            } finally {
                _busy.value = false
            }
        }
    }

    fun complete(id: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { container.tasks.complete(id) }
    }

    private var lastDeleted: AgendaItem? = null

    fun delete(item: AgendaItem, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        lastDeleted = item
        withContext(Dispatchers.IO) { container.tasks.deleteOccurrence(item.occurrence.id) }
        onDeleted()
    }

    fun undoDelete() = viewModelScope.launch {
        val item = lastDeleted ?: return@launch
        lastDeleted = null
        withContext(Dispatchers.IO) { container.tasks.restore(item) }
    }
    fun endSeries(seriesId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { container.tasks.endSeries(seriesId) }
    }
    fun snooze(id: String, minutes: Long = 30) = viewModelScope.launch {
        withContext(Dispatchers.IO) { container.tasks.snooze(id, minutes) }
    }

    fun retryMissed(id: String, onDone: (String) -> Unit = {}) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { container.tasks.retryMissed(id) }
        if (result == null) {
            onDone("Não deu para remarcar esta tarefa.")
            return@launch
        }
        val whenLabel = AgendaFormat.dateLabel(result.date, LocalDate.now()).lowercase()
        onDone("Vai avisar $whenLabel às ${AgendaFormat.time(result.time)}.")
    }

    fun edit(
        id: String,
        title: String,
        date: LocalDate,
        time: LocalTime,
        recurrence: RecurrenceRule,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            container.tasks.editOccurrence(id, title, date, time, recurrence)
        }
    }

    suspend fun parse(text: String): ParsedTaskDraft =
        withContext(Dispatchers.IO) { container.hybridParser.parse(text) }
}

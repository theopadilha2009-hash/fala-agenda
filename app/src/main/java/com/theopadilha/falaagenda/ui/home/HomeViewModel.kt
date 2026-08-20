package com.theopadilha.falaagenda.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    fun saveDraft(draft: ParsedTaskDraft, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = container.tasks.saveDraft(draft)
                onDone(result.usedInexactAlarm)
            } finally {
                _busy.value = false
            }
        }
    }

    fun complete(id: String) = viewModelScope.launch { container.tasks.complete(id) }
    fun delete(id: String) = viewModelScope.launch { container.tasks.deleteOccurrence(id) }
    fun endSeries(seriesId: String) = viewModelScope.launch { container.tasks.endSeries(seriesId) }
    fun snooze(id: String) = viewModelScope.launch { container.tasks.snooze(id) }

    fun edit(
        id: String,
        title: String,
        date: LocalDate,
        time: LocalTime,
        recurrence: RecurrenceRule,
    ) = viewModelScope.launch {
        container.tasks.editOccurrence(id, title, date, time, recurrence)
    }

    suspend fun parse(text: String): ParsedTaskDraft = container.hybridParser.parse(text)
}

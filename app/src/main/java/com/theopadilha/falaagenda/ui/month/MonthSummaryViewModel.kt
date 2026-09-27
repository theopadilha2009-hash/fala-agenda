package com.theopadilha.falaagenda.ui.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A agenda do mês mora aqui, e não no corpo do composable: um `Flow` novo a cada
 * recomposição fazia o `collectAsState` cancelar e reassinar as duas queries do banco
 * (com o `sectionsOf` inteiro no thread principal) a cada toque nas setas de mês.
 * Mesmo padrão do HomeViewModel.
 */
class MonthSummaryViewModel(tasks: TaskRepository) : ViewModel() {
    val agenda: StateFlow<AgendaSections> = tasks.observeAgenda().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(MonthSummaryViewModel::class.java)) {
                        return MonthSummaryViewModel(container.tasks) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
                }
            }
    }
}

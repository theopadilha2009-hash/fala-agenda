package com.theopadilha.falaagenda.ui.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.ui.home.AgendaUi
import com.theopadilha.falaagenda.ui.home.agendaUiFrom
import com.theopadilha.falaagenda.ui.home.initialAgendaUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A agenda do mês mora aqui, e não no corpo do composable: um `Flow` novo a cada
 * recomposição fazia o `collectAsState` cancelar e reassinar as duas queries do banco
 * (com o `sectionsOf` inteiro no thread principal) a cada toque nas setas de mês.
 * Mesmo padrão do HomeViewModel.
 *
 * O `stateIn` de três argumentos **não captura a exceção do upstream**: ela sobe pela
 * corrotina criada no `viewModelScope`, que não tem `CoroutineExceptionHandler`, e derruba o
 * processo — com o banco corrompido ou o disco cheio, ela abria o "Resumo de setembro" e o
 * app fechava sozinho, sem mensagem nenhuma. É por isso que a leitura passa pelo mesmo
 * [agendaUiFrom] da home, que espera e reassina em vez de morrer, e entrega o `failed` junto
 * da lista: a tela diz o que aconteceu, em vez de mostrar um mês vazio que é falso.
 */
class MonthSummaryViewModel(tasks: TaskRepository) : ViewModel() {
    val agenda: StateFlow<AgendaUi> = agendaUiFrom(tasks.observeAgenda()).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        initialAgendaUi,
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

package com.theopadilha.falaagenda.widget

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.AgendaSections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgendaWidgetSyncTest {

    /** Era este caminho que fechava o app ao abrir: exceção de leitura subindo pelo escopo. */
    @Test
    fun leituraQuebradaNaoPropagaERetomaQuandoOBancoVolta() {
        runTest {
            var tentativas = 0
            val agenda = flow {
                tentativas++
                if (tentativas == 1) error("banco corrompido")
                emit(agendaCom("Vitamina"))
            }
            val pintados = mutableListOf<String>()

            collectWidgetUpdates(agenda, flowOf(ThemeMode.LIGHT), retryDelayMs = 10) { sections, _ ->
                pintados += sections.today.first().series.title
            }

            assertThat(tentativas).isEqualTo(2)
            assertThat(pintados).containsExactly("Vitamina")
        }
    }

    @Test
    fun bancoQuebradoParaSempreNaoDerrubaNemGiraSemEsperar() {
        runTest {
            var tentativas = 0
            val agenda = flow<AgendaSections> {
                tentativas++
                error("disco cheio")
            }
            val job = launch {
                collectWidgetUpdates(agenda, flowOf(ThemeMode.SYSTEM), retryDelayMs = 5_000) { _, _ -> }
            }

            runCurrent()
            assertThat(tentativas).isEqualTo(1)
            advanceTimeBy(5_001)
            assertThat(tentativas).isEqualTo(2)

            job.cancel()
            advanceUntilIdle()
        }
    }

    /** Bug da "Aparência": sem o tema no fluxo, o widget só repintaria na próxima tarefa. */
    @Test
    fun temaNovoRepintaOWidgetSemAAgendaMudar() {
        runTest {
            val tema = MutableStateFlow(ThemeMode.SYSTEM)
            val pintados = mutableListOf<ThemeMode>()
            val job = launch {
                collectWidgetUpdates(flowOf(agendaCom("Vitamina")), tema, retryDelayMs = 10) { _, modo ->
                    pintados += modo
                }
            }

            advanceUntilIdle()
            tema.value = ThemeMode.LIGHT
            advanceUntilIdle()

            assertThat(pintados).containsExactly(ThemeMode.SYSTEM, ThemeMode.LIGHT).inOrder()

            job.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun temaIlegivelNaoImpedeAAgendaDePintar() {
        runTest {
            val temaQuebrado = flow<ThemeMode> { error("preferências ilegíveis") }
            val pintados = mutableListOf<ThemeMode>()

            collectWidgetUpdates(flowOf(agendaCom("Vitamina")), temaQuebrado, retryDelayMs = 10) { _, modo ->
                pintados += modo
            }

            assertThat(pintados).containsExactly(ThemeMode.SYSTEM)
        }
    }
}

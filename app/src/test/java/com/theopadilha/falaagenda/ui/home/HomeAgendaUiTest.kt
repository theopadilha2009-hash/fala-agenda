package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * O toque no aviso do remédio busca a ocorrência na lista e só pode concluir que ela não
 * existe mais quando essa mesma lista veio do banco. O caso perigoso é a agenda vazia: a
 * inicial e a que o banco devolve são iguais em conteúdo, e é só a emissão que diz qual é
 * qual — por isso os dois campos têm que sair do mesmo valor.
 */
class HomeAgendaUiTest {
    private val vazia = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun antesDoBancoResponderNadaSeConclui() {
        assertThat(initialAgendaUi.loaded).isFalse()
        assertThat(initialAgendaUi.sections.find("qualquer-id")).isNull()
    }

    /**
     * O caminho de quem estava em outra tela: o banco já respondeu, a assinatura foi
     * largada e ao voltar para a home chega o valor retido. Ele não pode chegar com o
     * "carregado" de um lado e a lista do outro.
     */
    @Test
    fun valorRetidoChegaJuntoComOCarregado() {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val fonte = MutableStateFlow(vazia)
            val estado = agendaUiFrom(fonte)
                .stateIn(escopo, SharingStarted.WhileSubscribed(0), initialAgendaUi)

            // A home ainda não assinou: nada chegou, então ela espera em vez de dizer que
            // o remédio saiu da agenda.
            assertThat(estado.value.loaded).isFalse()

            // Ao voltar, o valor guardado chega com os dois campos juntos.
            val depois = runBlocking { withTimeout(5_000) { estado.first { it.loaded } } }
            assertThat(depois.sections).isEqualTo(vazia)
            assertThat(depois.loaded).isTrue()
        } finally {
            escopo.cancel()
        }
    }
}

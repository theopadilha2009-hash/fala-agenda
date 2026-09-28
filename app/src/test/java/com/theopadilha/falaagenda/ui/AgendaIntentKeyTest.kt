package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.agendaIntentKey
import com.theopadilha.falaagenda.shouldApplyAgendaIntent
import org.junit.Test

/**
 * Girar o aparelho recria a Activity com o **mesmo** intent: o pedido que ele traz não
 * pode valer de novo, senão o app abre o microfone sozinho e reabre uma ocorrência já
 * atendida. Quem decide é a identidade do pedido, e é ela que este teste fixa — o resto
 * (o intent, a Activity) é ciclo de vida, não dá para provar aqui.
 */
class AgendaIntentKeyTest {
    @Test
    fun mesmoPedidoNaoValeDuasVezes() {
        val aberto = agendaIntentKey("ocorrencia-1", wantsSpeak = false)
        // A criação de verdade não tem nada guardado: o pedido vale.
        assertThat(shouldApplyAgendaIntent(null, aberto)).isTrue()
        // A Activity recriada devolve o pedido que já foi atendido: não vale.
        assertThat(shouldApplyAgendaIntent(aberto, aberto)).isFalse()
    }

    @Test
    fun pedidoDiferenteValeMesmoComEstadoGuardado() {
        val anterior = agendaIntentKey("ocorrencia-1", wantsSpeak = false)
        val novo = agendaIntentKey("ocorrencia-2", wantsSpeak = false)
        assertThat(shouldApplyAgendaIntent(anterior, novo)).isTrue()
        // O aviso e o atalho do microfone são pedidos diferentes pelo mesmo intent.
        assertThat(shouldApplyAgendaIntent(novo, agendaIntentKey("ocorrencia-2", wantsSpeak = true)))
            .isTrue()
    }

    @Test
    fun aChaveSeparaOcorrenciaDeMicrofone() {
        assertThat(agendaIntentKey(null, wantsSpeak = true))
            .isNotEqualTo(agendaIntentKey(null, wantsSpeak = false))
        assertThat(agendaIntentKey("a", wantsSpeak = false))
            .isNotEqualTo(agendaIntentKey("b", wantsSpeak = false))
        assertThat(agendaIntentKey("a", wantsSpeak = false))
            .isEqualTo(agendaIntentKey("a", wantsSpeak = false))
    }
}

package com.theopadilha.falaagenda.reminders

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.ActionOutcome
import org.junit.Test

/**
 * Trava a regra que separa o aviso do alarme falso: só quem não gravou nada merece notificação —
 * o [ActionResponse.GONE], em que a ocorrência saiu da agenda, e o [ActionResponse.UNFINISHED], em
 * que o trabalho não terminou e o que foi gravado é desconhecido.
 *
 * O despacho do `ReminderActionReceiver` em si não é exercitado: um `BroadcastReceiver` de verdade
 * pediria `FalaAgendaApplication`, Room e AlarmManager no teste, que é infraestrutura inventada.
 * O que este teste cobre é a decisão de falar ou calar, que é onde a regra mora.
 */
class PrecisaAvisarDeAcaoNaoAplicadaTest {
    @Test
    fun goneAvisa() {
        assertThat(precisaAvisarDeAcaoNaoAplicada(ActionResponse.GONE)).isTrue()
    }

    @Test
    fun trabalhoQueNaoTerminouAvisa() {
        assertThat(precisaAvisarDeAcaoNaoAplicada(ActionResponse.UNFINISHED)).isTrue()
    }

    @Test
    fun resolvidaNaoAvisa() {
        assertThat(precisaAvisarDeAcaoNaoAplicada(ActionResponse.RESOLVIDA)).isFalse()
    }

    @Test
    fun gravadoViraResolvida() {
        assertThat(ActionOutcome.APPLIED.paraResposta()).isEqualTo(ActionResponse.RESOLVIDA)
    }

    /**
     * Concluir o que já estava concluído é no-op legítimo, não falha: o repositório devolve
     * `UNCHANGED` e quem chamou tem que calar. Anunciá-lo seria trocar a mentira pelo alarme falso.
     */
    @Test
    fun inalteradoViraResolvidaESemAviso() {
        val resposta = ActionOutcome.UNCHANGED.paraResposta()

        assertThat(resposta).isEqualTo(ActionResponse.RESOLVIDA)
        assertThat(precisaAvisarDeAcaoNaoAplicada(resposta)).isFalse()
    }

    @Test
    fun oQueSaiuDaAgendaVirouGone() {
        assertThat(ActionOutcome.GONE.paraResposta()).isEqualTo(ActionResponse.GONE)
    }
}

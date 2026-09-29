package com.theopadilha.falaagenda.reminders

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.ActionOutcome
import org.junit.Test

/**
 * Trava a regra que separa o aviso do alarme falso: só o [ActionOutcome.GONE] merece notificação,
 * porque só nele nada foi gravado — e, no "adiar", nada foi agendado.
 *
 * O despacho do `ReminderActionReceiver` em si não é exercitado: um `BroadcastReceiver` de verdade
 * pediria `FalaAgendaApplication`, Room e AlarmManager no teste, que é infraestrutura inventada.
 * O que este teste cobre é a decisão de falar ou calar, que é onde a regra mora.
 */
class PrecisaAvisarDeAcaoNaoAplicadaTest {
    @Test
    fun goneAvisa() {
        assertThat(precisaAvisarDeAcaoNaoAplicada(ActionOutcome.GONE)).isTrue()
    }

    @Test
    fun aplicadoNaoAvisa() {
        assertThat(precisaAvisarDeAcaoNaoAplicada(ActionOutcome.APPLIED)).isFalse()
    }

    @Test
    fun inalteradoNaoAvisa() {
        assertThat(precisaAvisarDeAcaoNaoAplicada(ActionOutcome.UNCHANGED)).isFalse()
    }
}

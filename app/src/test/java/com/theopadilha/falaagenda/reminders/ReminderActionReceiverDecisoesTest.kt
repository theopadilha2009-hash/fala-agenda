package com.theopadilha.falaagenda.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * As duas decisões do `ReminderActionReceiver` que não dependem de Android nenhum: o que fazer com
 * o lembrete que está na barra depois que o toque dela foi respondido, e o que fazer com uma ação
 * que ele não conhece.
 *
 * O receiver em si continua sem harness: exercitá-lo pediria `FalaAgendaApplication`, Room e
 * AlarmManager no teste, que é infraestrutura inventada. O que se trava aqui é a regra — e ela é
 * onde o defeito morava.
 */
class ReminderActionReceiverDecisoesTest {
    /**
     * Sem nada a dizer (a ação foi gravada, ou já estava no estado pedido), o lembrete sai: ele já
     * cumpriu o papel, e a tarefa não está mais esperando resposta nenhuma.
     */
    @Test
    fun semNadaADizerOLembreteSai() {
        assertThat(deveCancelarOLembrete(ActionResponse.RESOLVIDA, avisoSaiu = false)).isTrue()
        assertThat(deveCancelarOLembrete(ActionResponse.RESOLVIDA, avisoSaiu = true)).isTrue()
    }

    /**
     * Dito o que havia de ser dito, o lembrete também sai: a evidência de que o toque não valeu é o
     * aviso, que tem id próprio e não é apagado por este cancelamento. Deixar o lembrete aqui só
     * ofereceria de novo os botões de uma ação que acabou de falhar.
     */
    @Test
    fun ditoOAvisoOLembreteSai() {
        assertThat(deveCancelarOLembrete(ActionResponse.GONE, avisoSaiu = true)).isTrue()
        assertThat(deveCancelarOLembrete(ActionResponse.UNFINISHED, avisoSaiu = true)).isTrue()
    }

    /**
     * Aqui está o defeito que este arquivo trava: havia o que dizer e o aviso NÃO saiu (canal
     * desligado, sistema recusando). Cancelar apagaria a última coisa na tela que conta a ela que
     * o toque não valeu — ela tocaria em "Adiar", nada seria agendado, e a barra ficaria limpa como
     * se tudo tivesse dado certo. O lembrete fica.
     */
    @Test
    fun quandoOAvisoNaoSaiuOLembreteFica() {
        assertThat(deveCancelarOLembrete(ActionResponse.GONE, avisoSaiu = false)).isFalse()
        assertThat(deveCancelarOLembrete(ActionResponse.UNFINISHED, avisoSaiu = false)).isFalse()
    }

    @Test
    fun asDuasAcoesDaNotificacaoSaoReconhecidas() {
        assertThat(AcaoDaNotificacao.de(AlarmIds.ACTION_COMPLETE))
            .isEqualTo(AcaoDaNotificacao.CONCLUIR)
        assertThat(AcaoDaNotificacao.de(AlarmIds.ACTION_SNOOZE))
            .isEqualTo(AcaoDaNotificacao.ADIAR)
    }

    /**
     * O `PendingIntent` do lembrete só nasce com "concluir" e "adiar", então nada disto acontece
     * hoje: é a aresta armada para o dia em que uma ação nova chegar aqui sem tratamento. O
     * `when (intent.action)` antigo não tinha `else` e caía no `cancel` — a notificação do lembrete
     * sumia da barra sem nada ter sido aplicado nem dito, e a tarefa continuava na agenda.
     */
    @Test
    fun acaoDesconhecidaNaoEhReconhecida() {
        assertThat(AcaoDaNotificacao.de("qualquer-outra-acao")).isNull()
        assertThat(AcaoDaNotificacao.de(AlarmIds.ACTION_FIRE)).isNull()
        assertThat(AcaoDaNotificacao.de(AlarmIds.ACTION_OPEN)).isNull()
        assertThat(AcaoDaNotificacao.de(null)).isNull()
    }
}

package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.reminders.NotificationHelper.ReminderAlerts
import org.junit.Test

/**
 * O cartão de avisos da home é a única saída que ela tem quando o lembrete não sai: o toque
 * precisa levar ao lugar onde se conserta, e o texto precisa dizer o que fazer. Sem teste,
 * o cartão "Os avisos estão desligados" ficava com um botão que abria uma tela onde não havia
 * nada para ligar — o mesmo aviso mudo, agora com uma tela no meio.
 */
class ReminderAlertCardTest {

    @Test
    fun avisosValendoNaoTemCartaoNemAcao() {
        assertThat(reminderAlertCard(ReminderAlerts.OK)).isNull()
        assertThat(alertFix(ReminderAlerts.OK, canAskAgain = true)).isNull()
        assertThat(alertFix(ReminderAlerts.OK, canAskAgain = false)).isNull()
    }

    @Test
    fun cadaEstadoTemTituloTextoEBotaoProprios() {
        val desligados = reminderAlertCard(ReminderAlerts.OFF)!!
        val semSom = reminderAlertCard(ReminderAlerts.QUIET)!!

        assertThat(desligados.title).isNotEqualTo(semSom.title)
        assertThat(desligados.text).isNotEqualTo(semSom.text)
        assertThat(desligados.button).isNotEqualTo(semSom.button)
    }

    @Test
    fun oCartaoDizOQueFazerEmTextoSimples() {
        val desligados = reminderAlertCard(ReminderAlerts.OFF)!!
        val semSom = reminderAlertCard(ReminderAlerts.QUIET)!!

        // O texto diz o que está acontecendo e o que fazer, sem jargão de Android.
        assertThat(desligados.text).contains("lembrete")
        assertThat(desligados.text).contains("Permita os avisos")
        assertThat(semSom.text).contains("lembrete")
        assertThat(semSom.text).contains("com som")
    }

    @Test
    fun semPermissaoPedeOPedidoEnquantoOSistemaMostraODialogo() {
        assertThat(alertFix(ReminderAlerts.OFF, canAskAgain = true))
            .isEqualTo(AlertFix.ASK_NOTIFICATIONS)
    }

    @Test
    fun semPermissaoESemDialogoVaiAosAjustesDoAplicativo() {
        assertThat(alertFix(ReminderAlerts.OFF, canAskAgain = false))
            .isEqualTo(AlertFix.OPEN_APP_SETTINGS)
    }

    /**
     * O canal rebaixado não se conserta com o pedido de permissão: a permissão está dada e o
     * que falta é o som. Mesmo podendo pedir de novo, o caminho é a tela do canal.
     */
    @Test
    fun canalRebaixadoVaiAosAjustesDoCanalMesmoPodendoPedirDeNovo() {
        assertThat(alertFix(ReminderAlerts.QUIET, canAskAgain = true))
            .isEqualTo(AlertFix.OPEN_CHANNEL_SETTINGS)
        assertThat(alertFix(ReminderAlerts.QUIET, canAskAgain = false))
            .isEqualTo(AlertFix.OPEN_CHANNEL_SETTINGS)
    }
}

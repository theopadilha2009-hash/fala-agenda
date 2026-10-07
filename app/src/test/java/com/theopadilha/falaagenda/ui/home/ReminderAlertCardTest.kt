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
        assertThat(alertFix(ReminderAlerts.OK)).isNull()
    }

    @Test
    fun cadaEstadoTemTituloTextoEBotaoProprios() {
        val desligados = reminderAlertCard(ReminderAlerts.OFF)!!
        val semSom = reminderAlertCard(ReminderAlerts.QUIET)!!
        val aparelhoMudo = reminderAlertCard(ReminderAlerts.APARELHO_MUDO)!!

        val cartoes = listOf(desligados, semSom, aparelhoMudo)
        assertThat(cartoes.map { it.title }.toSet()).hasSize(cartoes.size)
        assertThat(cartoes.map { it.text }.toSet()).hasSize(cartoes.size)
        assertThat(cartoes.map { it.button }.toSet()).hasSize(cartoes.size)
    }

    /**
     * O aparelho mudo tem cartão próprio — o canal está certo, o que não deixa soar é o volume do
     * celular. O texto diz o que vai acontecer se ela não mexer ("aparece na tela, mas não faz
     * barulho") e aponta o botão como o caminho, que é o que ela precisa para o lembrete não
     * virar mais um "não funcionou".
     *
     * O caminho é o botão, e **não** a tecla de volume do lado do celular: fora do toque de um
     * alarme a tecla mexe no volume de mídia, e mandá-la apertar ali não subiria o volume que o
     * lembrete usa. Um texto acionável que aponta o gesto errado é o mesmo cartão sem conserto.
     */
    @Test
    fun oAparelhoMudoDizOndeAumentarOVolume() {
        val aparelhoMudo = reminderAlertCard(ReminderAlerts.APARELHO_MUDO)!!

        assertThat(aparelhoMudo.title).contains("volume")
        assertThat(aparelhoMudo.text).contains("não faz barulho")
        assertThat(aparelhoMudo.text).contains("aumente o volume")
        assertThat(aparelhoMudo.text).doesNotContain("tecla")
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

    /**
     * O aparelho dela nasceu com a permissão de aviso negada (`targetSdk` novo) e nunca houve
     * pedido nenhum: `shouldShowRequestPermissionRationale` volta `false` sem ter perguntado.
     * Decidir por ele ANTES do toque mandava direto aos Ajustes, e o diálogo do sistema nunca
     * aparecia — a permissão ficava negada para sempre, e o cartão "Ligar avisos" não ligava
     * nada. O pedido vem primeiro, sempre: quem não tem mais diálogo avisa no resultado.
     */
    @Test
    fun comOsAvisosDesligadosOPedidoVemPrimeiro() {
        assertThat(alertFix(ReminderAlerts.OFF)).isEqualTo(AlertFix.ASK_NOTIFICATIONS)
    }

    /**
     * O canal rebaixado não se conserta com o pedido de permissão: a permissão está dada e o
     * que falta é o som. Mesmo podendo pedir de novo, o caminho é a tela do canal.
     */
    @Test
    fun canalRebaixadoVaiAosAjustesDoCanal() {
        assertThat(alertFix(ReminderAlerts.QUIET)).isEqualTo(AlertFix.OPEN_CHANNEL_SETTINGS)
    }

    /**
     * O aparelho mudo NÃO se conserta na tela do canal: lá o canal já está certo, alto e com som.
     * O que falta é o volume do celular, e mandá-la de novo aos Ajustes do canal daria um botão
     * que não muda nada — o mesmo defeito do botão que abria uma tela sem nada para ligar.
     */
    @Test
    fun oAparelhoMudoVaiAosAjustesDeSomENaoDoCanal() {
        assertThat(alertFix(ReminderAlerts.APARELHO_MUDO)).isEqualTo(AlertFix.OPEN_SOUND_SETTINGS)
        assertThat(alertFix(ReminderAlerts.APARELHO_MUDO)).isNotEqualTo(AlertFix.OPEN_CHANNEL_SETTINGS)
    }

    /** O pedido resolveu: não há Ajustes a abrir. */
    @Test
    fun pedidoAtendidoNaoAbreNada() {
        assertThat(needsNotificationSettings(granted = true, alerts = ReminderAlerts.OK, canAskAgain = false))
            .isFalse()
    }

    /**
     * Ela recusou AGORA e o sistema ainda mostra o diálogo: o cartão fica, e o próximo toque
     * pergunta de novo. Mandá-la aos Ajustes aqui seria pular o pedido.
     */
    @Test
    fun recusaComDialogoAindaDisponivelMantemOPedido() {
        assertThat(needsNotificationSettings(granted = false, alerts = ReminderAlerts.OFF, canAskAgain = true))
            .isFalse()
    }

    /** Negada de vez: o diálogo não vem mais, e só os Ajustes devolvem os avisos. */
    @Test
    fun negadaDeVezVaiAosAjustesDoAplicativo() {
        assertThat(needsNotificationSettings(granted = false, alerts = ReminderAlerts.OFF, canAskAgain = false))
            .isTrue()
    }

    /**
     * O sistema nem abriu o diálogo porque a permissão já estava dada — o que falta é o canal
     * ou o interruptor geral do aplicativo, e os dois se ligam na mesma tela de avisos. Sem
     * esta saída o toque não fazia nada: o cartão dizia "desligados" e continuava desligado.
     */
    @Test
    fun semDialogoPorqueJaEstavaDadaVaiAosAjustesDoAplicativo() {
        assertThat(needsNotificationSettings(granted = true, alerts = ReminderAlerts.OFF, canAskAgain = true))
            .isTrue()
        assertThat(needsNotificationSettings(granted = true, alerts = ReminderAlerts.OFF, canAskAgain = false))
            .isTrue()
    }
}

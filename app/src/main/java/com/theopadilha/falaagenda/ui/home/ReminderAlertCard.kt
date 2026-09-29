package com.theopadilha.falaagenda.ui.home

import com.theopadilha.falaagenda.reminders.NotificationHelper.ReminderAlerts

/**
 * O texto do cartão de avisos da home. `null` é "não há o que avisar": os avisos estão
 * valendo e o cartão não aparece.
 *
 * O texto vive fora do Composable para poder ser lido por teste — ele é a única coisa que
 * ela vê quando o lembrete não sai, e uma frase que não diz o que fazer deixa o aviso
 * desligado para sempre.
 */
internal data class ReminderAlertCard(
    val title: String,
    val text: String,
    val button: String,
)

internal fun reminderAlertCard(alerts: ReminderAlerts): ReminderAlertCard? = when (alerts) {
    ReminderAlerts.OK -> null
    ReminderAlerts.OFF -> ReminderAlertCard(
        title = "Os avisos estão desligados",
        text = "Assim o lembrete não aparece na hora marcada. Permita os avisos do Fala Agenda.",
        button = "Ligar avisos",
    )
    ReminderAlerts.QUIET -> ReminderAlertCard(
        title = "Os avisos estão sem som",
        text = "Assim o lembrete pode passar despercebido. Deixe os avisos do Fala Agenda com som.",
        button = "Arrumar o som do aviso",
    )
}

/** O que o toque no cartão precisa abrir. */
internal enum class AlertFix {
    /** O sistema ainda pode mostrar o pedido de permissão: é ele que resolve. */
    ASK_NOTIFICATIONS,

    /** O canal foi rebaixado ou desligado: os Ajustes do canal, que é onde o som mora. */
    OPEN_CHANNEL_SETTINGS,
}

/**
 * O caminho que devolve os avisos.
 *
 * Canal rebaixado vai direto aos Ajustes DO CANAL: é lá que está o som, e mandá-la aos
 * Ajustes gerais do aplicativo a deixaria procurando numa tela onde não há nada para ligar.
 *
 * Com os avisos DESLIGADOS o pedido de permissão vem primeiro, sempre, sem adivinhar antes.
 * `shouldShowRequestPermissionRationale` devolve `false` tanto para "negou de vez" quanto para
 * "nunca perguntamos" — e o aparelho dela, com `targetSdk` novo, nasce no segundo caso: a
 * permissão é negada de partida e nunca houve pedido. Adivinhar antes mandava direto aos
 * Ajustes, e o diálogo do sistema nunca aparecia. Quem não tem mais diálogo para mostrar
 * responde na hora, e é [needsNotificationSettings] que decide o resto.
 */
internal fun alertFix(alerts: ReminderAlerts): AlertFix? = when (alerts) {
    ReminderAlerts.OK -> null
    ReminderAlerts.OFF -> AlertFix.ASK_NOTIFICATIONS
    ReminderAlerts.QUIET -> AlertFix.OPEN_CHANNEL_SETTINGS
}

/**
 * O pedido de permissão já voltou (`granted`) e ainda é preciso abrir os Ajustes?
 *
 * Falso quando o pedido resolveu — e também quando ela recusou AGORA com o sistema ainda
 * disposto a mostrar o diálogo: aí o cartão fica e o próximo toque pergunta de novo, e
 * mandá-la aos Ajustes seria pular o pedido.
 *
 * Verdadeiro nos dois desfechos em que o diálogo não veio, que significam a mesma coisa: ela
 * negou de vez ([canAskAgain] falso), ou o sistema nem abriu o diálogo porque a permissão já
 * estava dada — e o que falta, então, é o canal ou o interruptor geral do aplicativo. A tela de
 * avisos do aplicativo lista os dois, que é onde se liga de volta.
 */
internal fun needsNotificationSettings(
    granted: Boolean,
    alerts: ReminderAlerts,
    canAskAgain: Boolean,
): Boolean = when {
    alerts != ReminderAlerts.OFF -> false
    !granted && canAskAgain -> false
    else -> true
}

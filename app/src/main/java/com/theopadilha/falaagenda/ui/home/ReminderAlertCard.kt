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
    /** O sistema ainda mostra o pedido de permissão: é ele que resolve. */
    ASK_NOTIFICATIONS,

    /** O sistema não mostra mais o pedido (negada de vez): só os Ajustes do aplicativo. */
    OPEN_APP_SETTINGS,

    /** O canal foi rebaixado: os Ajustes do canal, que é onde o som mora. */
    OPEN_CHANNEL_SETTINGS,
}

/**
 * O caminho que devolve os avisos. [canAskAgain] diz se o sistema ainda mostra o diálogo de
 * permissão.
 *
 * Canal rebaixado vai direto aos Ajustes DO CANAL: é lá que está o som, e mandá-la aos
 * Ajustes gerais do aplicativo a deixaria procurando numa tela onde não há nada para ligar.
 * Sem permissão o pedido vem primeiro; só quando ele não é mais possível é que os Ajustes
 * passam a ser o caminho — sem isso o toque não fazia nada e não havia como voltar atrás
 * dentro do aplicativo.
 */
internal fun alertFix(alerts: ReminderAlerts, canAskAgain: Boolean): AlertFix? = when (alerts) {
    ReminderAlerts.OK -> null
    ReminderAlerts.OFF ->
        if (canAskAgain) AlertFix.ASK_NOTIFICATIONS else AlertFix.OPEN_APP_SETTINGS
    ReminderAlerts.QUIET -> AlertFix.OPEN_CHANNEL_SETTINGS
}

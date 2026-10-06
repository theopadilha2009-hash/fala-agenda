package com.theopadilha.falaagenda.ui.home

/**
 * O texto do cartão de saúde do alarme. `null` é "o aparelho deixa o lembrete tocar como
 * devia": não há o que avisar.
 *
 * Irmão do [ReminderAlertCard], e pelo mesmo motivo: o texto é a única coisa que ela vê quando
 * o lembrete pode não tocar, e uma frase que não diz o que fazer deixa o aviso mudo para sempre.
 * Aqui ele vive fora do Composable para o teste poder lê-lo.
 */
internal data class AlarmHealthCard(
    val title: String,
    val text: String,
    val button: String,
)

/** O botão do cartão de bateria — é ele que o teste de jargão lê. */
internal const val SAUDE_BATERIA_BOTAO = "Liberar os avisos"

/**
 * A tela do sistema não abriu: sem isto o toque ficava sem resposta e ela continuava sem saber
 * por que o lembrete não toca — o mesmo cuidado das outras telas que o `DeviceIntents` abre.
 */
internal const val SAUDE_BATERIA_FALHA_AO_ABRIR =
    "Não consegui abrir os ajustes de bateria deste celular."

/**
 * O cartão que faltava. A bateria restrita é o problema maior (o sistema pode matar o alarme de
 * vez) e tem precedência sobre o alarme inexato (que só atrasa); os dois quebrados mostram um
 * cartão de cada vez, e é a bateria que fica na frente.
 *
 * A restrição de bateria só é oferecida quando o aparelho tem mesmo essa tela — quem decide é o
 * `DeviceIntents.batterySettingsIntentOrNull`, no toque.
 */
internal fun alarmHealthCard(
    batteryUnrestricted: Boolean,
    canScheduleExact: Boolean,
): AlarmHealthCard? = when {
    !batteryUnrestricted -> AlarmHealthCard(
        title = "O celular pode não avisar",
        text = "A economia de bateria pode impedir o lembrete de tocar. Toque para liberar os avisos do Fala Agenda.",
        button = SAUDE_BATERIA_BOTAO,
    )
    !canScheduleExact -> AlarmHealthCard(
        title = "O aviso pode atrasar",
        text = "Neste celular o aviso pode tocar alguns minutos depois da hora. Toque para deixar no horário certo.",
        button = AVISO_INEXATO_BOTAO,
    )
    else -> null
}

/** O que o toque no cartão de saúde do alarme precisa abrir. */
internal enum class AlarmHealthFix {
    /** A isenção de bateria do próprio pacote (ver `DeviceIntents.batterySettingsIntentOrNull`). */
    BATTERY,

    /** O acesso a alarmes exatos do próprio pacote. */
    EXACT_ALARM,
}

/** Mesma precedência do [alarmHealthCard]: a bateria é o que pode matar o alarme por completo. */
internal fun alarmHealthFix(
    batteryUnrestricted: Boolean,
    canScheduleExact: Boolean,
): AlarmHealthFix? = when {
    !batteryUnrestricted -> AlarmHealthFix.BATTERY
    !canScheduleExact -> AlarmHealthFix.EXACT_ALARM
    else -> null
}

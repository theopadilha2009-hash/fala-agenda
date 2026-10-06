package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O cartão da saúde do alarme é o que faltava na home. A otimização de bateria já era
 * detectada (`DeviceIntents.isBatteryUnrestricted`) e virava rótulo na gaveta — mas só quem
 * abria a gaveta descobria; e o "aviso inexato" só aparecia no instante do salvamento e sumia
 * no toque. Nos dois casos o alarme podia não tocar (ou atrasar para sempre) e ela nunca
 * ficava sabendo.
 *
 * O texto vive fora do composable para este teste poder lê-lo, o mesmo desenho do
 * [ReminderAlertCard]. O que se prende aqui: o cartão aparece sempre que o aparelho pode não
 * avisar, cada problema tem a sua ação, e o texto diz o que acontece em português de gente —
 * sem o nome técnico da tela nem o jargão de sistema que o PR #47 tirou daqui.
 */
class AlarmHealthCardTest {

    @Test
    fun aparelhoEmDiaNaoTemCartaoNemAcao() {
        assertThat(alarmHealthCard(batteryUnrestricted = true, canScheduleExact = true)).isNull()
        assertThat(alarmHealthFix(batteryUnrestricted = true, canScheduleExact = true)).isNull()
    }

    /** A bateria pode matar o alarme por completo: é o problema maior e vem primeiro. */
    @Test
    fun bateriaRestritaMostraOCartaoEAcaoDaBateria() {
        val card = alarmHealthCard(batteryUnrestricted = false, canScheduleExact = true)!!

        assertThat(card.button).isEqualTo(SAUDE_BATERIA_BOTAO)
        assertThat(alarmHealthFix(batteryUnrestricted = false, canScheduleExact = true))
            .isEqualTo(AlarmHealthFix.BATTERY)
    }

    /** A permissão negada só atrasa o aviso — mas o cartão aparece sempre, não só ao salvar. */
    @Test
    fun semExatidaoMostraOCartaoEAcaoDoHorario() {
        val card = alarmHealthCard(batteryUnrestricted = true, canScheduleExact = false)!!

        assertThat(card.button).isEqualTo(AVISO_INEXATO_BOTAO)
        assertThat(alarmHealthFix(batteryUnrestricted = true, canScheduleExact = false))
            .isEqualTo(AlarmHealthFix.EXACT_ALARM)
    }

    /** Os dois quebrados: um cartão de cada vez, e é o pior que fica na frente. */
    @Test
    fun comOsDoisQuebradosABateriaVemPrimeiro() {
        assertThat(alarmHealthFix(batteryUnrestricted = false, canScheduleExact = false))
            .isEqualTo(AlarmHealthFix.BATTERY)
    }

    @Test
    fun cadaProblemaTemTituloTextoEBotaoProprios() {
        val bateria = alarmHealthCard(batteryUnrestricted = false, canScheduleExact = true)!!
        val inexato = alarmHealthCard(batteryUnrestricted = true, canScheduleExact = false)!!

        assertThat(bateria.title).isNotEqualTo(inexato.title)
        assertThat(bateria.text).isNotEqualTo(inexato.text)
        assertThat(bateria.button).isNotEqualTo(inexato.button)
    }

    @Test
    fun oCartaoDizOQueVaiAcontecerEOMandaFazer() {
        val bateria = alarmHealthCard(batteryUnrestricted = false, canScheduleExact = true)!!
        val inexato = alarmHealthCard(batteryUnrestricted = true, canScheduleExact = false)!!

        assertThat(bateria.text).contains("lembrete")
        assertThat(bateria.text).contains("Toque")
        assertThat(inexato.text).contains("aviso")
        assertThat(inexato.text).contains("Toque")
    }

    @Test
    fun oTextoNaoFalaEmJargaoDeSistema() {
        val jargao = listOf(
            "otimização de bateria",
            "SCHEDULE_EXACT_ALARM",
            "alarme exato",
            "Doze",
            "ajustes",
        )
        val cards: List<AlarmHealthCard> = listOf(
            alarmHealthCard(batteryUnrestricted = false, canScheduleExact = true)!!,
            alarmHealthCard(batteryUnrestricted = true, canScheduleExact = false)!!,
        )

        cards.forEach { card ->
            listOf(card.title, card.text, card.button).forEach { frase ->
                jargao.forEach { termo ->
                    assertThat(frase.lowercase()).doesNotContain(termo.lowercase())
                }
            }
        }
    }
}

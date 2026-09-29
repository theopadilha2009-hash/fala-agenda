package com.theopadilha.falaagenda.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlarmIdsTest {
    @Test
    fun acoesDiferentesNuncaColidemNoMesmoId() {
        val id = "series-1:2026-08-20"
        val codes = listOf(
            AlarmIds.requestCode(id, AlarmIds.ACTION_FIRE),
            AlarmIds.requestCode(id, AlarmIds.ACTION_COMPLETE),
            AlarmIds.requestCode(id, AlarmIds.ACTION_SNOOZE),
            AlarmIds.requestCode(id, AlarmIds.ACTION_OPEN),
            AlarmIds.requestCode(id, AlarmIds.NOTIF_REMINDER),
            AlarmIds.requestCode(id, AlarmIds.NOTIF_NOT_APPLIED),
        )
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun milOcorrenciasSemColisaoPorAcao() {
        val codes = (0 until 4_000).map { AlarmIds.requestCode("occ-$it", AlarmIds.ACTION_FIRE) }
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    /**
     * O alarme da virada do dia é do aplicativo inteiro e não de uma ocorrência: ele precisa
     * de lane própria, senão divide o `requestCode` com o disparo de um lembrete — e um
     * cancelaria o outro.
     *
     * O `desconhecido` é o `else` do `when`: sem o ramo da varredura, é nele que ela cai, e
     * comparar só entre as ações nomeadas não acusa nada.
     */
    @Test
    fun viradaDoDiaNaoColideComOCorrenciaDeMesmoId() {
        val id = AlarmIds.DAILY_SWEEP_ID
        val codigos = listOf(
            AlarmIds.requestCode(id, AlarmIds.ACTION_DAILY_SWEEP),
            AlarmIds.requestCode(id, AlarmIds.ACTION_FIRE),
            AlarmIds.requestCode(id, AlarmIds.ACTION_COMPLETE),
            AlarmIds.requestCode(id, AlarmIds.ACTION_SNOOZE),
            AlarmIds.requestCode(id, AlarmIds.ACTION_OPEN),
            AlarmIds.requestCode(id, AlarmIds.NOTIF_REMINDER),
            AlarmIds.requestCode(id, AlarmIds.NOTIF_NOT_APPLIED),
            AlarmIds.requestCode(id, "desconhecido"),
        )
        assertThat(codigos.toSet()).hasSize(codigos.size)
    }

    /**
     * A lane da varredura vale 8, e é isso que a tira do `else`. Sem o ramo no `when` o
     * conjunto de códigos continua com sete elementos distintos — o teste de colisão acima
     * segue verde — e a varredura passa a dividir o id com qualquer ação desconhecida.
     */
    @Test
    fun aVarreduraTemLanePropriaE8() {
        val codigo = AlarmIds.requestCode(AlarmIds.DAILY_SWEEP_ID, AlarmIds.ACTION_DAILY_SWEEP)

        assertThat(codigo ushr 28).isEqualTo(8)
    }
}

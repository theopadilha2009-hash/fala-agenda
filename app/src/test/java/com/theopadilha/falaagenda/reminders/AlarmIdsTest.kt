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
            AlarmIds.requestCode(id, "open"),
            AlarmIds.requestCode(id, "notif"),
        )
        assertThat(codes.toSet()).hasSize(codes.size)
    }

    @Test
    fun milOcorrenciasSemColisaoPorAcao() {
        val codes = (0 until 4_000).map { AlarmIds.requestCode("occ-$it", AlarmIds.ACTION_FIRE) }
        assertThat(codes.toSet()).hasSize(codes.size)
    }
}

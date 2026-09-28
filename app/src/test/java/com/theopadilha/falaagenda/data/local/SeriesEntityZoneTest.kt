package com.theopadilha.falaagenda.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId

/**
 * A coluna `zoneId` guarda o fuso do dia do cadastro. Ler com ela é o defeito que deixava o
 * aviso tocando 08:00 do lugar antigo depois de a pessoa trocar o fuso do celular — deslocado,
 * calado e para sempre. A leitura de produção já converte por cima com o fuso do relógio
 * (`TaskRepository.toTaskSeries`), mas a conversão genérica continuava entregando a coluna:
 * qualquer leitor novo que a chamasse direto ressuscitava o defeito sem nada ficar vermelho.
 * O contrato, daqui para a frente, é este: `toDomain()` nunca devolve o fuso gravado.
 */
class SeriesEntityZoneTest {
    @Test
    fun oFusoGravadoNaLinhaNaoDecideALeitura() {
        val tokyo = linha(zoneId = "Asia/Tokyo").toDomain().zoneId
        val saoPaulo = linha(zoneId = "America/Sao_Paulo").toDomain().zoneId

        assertThat(tokyo).isEqualTo(saoPaulo)
        assertThat(tokyo).isEqualTo(ZoneId.systemDefault())
    }

    /** O resto da linha continua vindo dela: o que sai de cena é só o fuso. */
    @Test
    fun oRestoDaLinhaContinuaIntacto() {
        val serie = linha(zoneId = "Asia/Tokyo").toDomain()

        assertThat(serie.id).isEqualTo("s1")
        assertThat(serie.title).isEqualTo("Tomar remédio")
        assertThat(serie.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(serie.observation).isEmpty()
    }

    private fun linha(zoneId: String) = SeriesEntity(
        id = "s1",
        title = "Tomar remédio",
        zoneId = zoneId,
        localTime = "08:00",
        startLocalDate = "2026-08-20",
        recurrenceKind = "DAILY",
        weekDays = "",
        dayOfMonth = null,
        monthOfYear = null,
        endedAtEpochMs = null,
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
    )
}

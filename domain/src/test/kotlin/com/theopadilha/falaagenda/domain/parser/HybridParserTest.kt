package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HybridParserTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    @Test
    fun naoChamaRemotoQuandoLocalEstaClaro() = runBlocking {
        var called = false
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = object : RemoteDraftParser {
                override suspend fun parse(transcript: String, nowIso: String, timezone: String, locale: String): ParsedTaskDraft {
                    called = true
                    error("não deveria")
                }
            },
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("tomar remédio amanhã às 9h")
        assertThat(called).isFalse()
        assertThat(draft.source).isEqualTo(DraftSource.LOCAL)
        assertThat(draft.transcript).isEqualTo("tomar remédio amanhã às 9h")
    }

    @Test
    fun semRedeMantemRascunhoLocal() = runBlocking {
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = object : RemoteDraftParser {
                override suspend fun parse(transcript: String, nowIso: String, timezone: String, locale: String): ParsedTaskDraft {
                    error("offline")
                }
            },
            network = NetworkStatus { false },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("reunião amanhã às 9h ou às 10h")
        assertThat(draft.transcript).contains("reunião")
        assertThat(draft.notes.joinToString()).contains("rascunho local")
    }
}

package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class HybridParserTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val local = LocalTaskParser(clock)

    /** Ajuda remota que só devolve data/hora para "quinze horas" e marca que foi acionada. */
    private fun remoteStub(onCall: () -> Unit = {}) = object : RemoteDraftParser {
        override suspend fun parse(
            transcript: String,
            nowIso: String,
            timezone: String,
            locale: String,
        ): ParsedTaskDraft {
            onCall()
            return ParsedTaskDraft(
                title = "Consulta",
                localDate = clock.today(),
                localTime = LocalTime.of(15, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = listOf("A ajuda extra completou o horário."),
                source = DraftSource.AI,
            )
        }
    }

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

    /**
     * O defeito estrutural: "quinze horas" o parser local não conhece, então devolve data/hora
     * nulas com ambiguous = false. Antes da correção, isso nunca escalava para a IA.
     */
    @Test
    fun fraseDesconhecidaEscalaParaIa() = runBlocking {
        val localDraft = local.parse("quinze horas")
        assertThat(localDraft.ambiguous).isFalse()
        assertThat(localDraft.localDate).isNull()
        assertThat(localDraft.localTime).isNull()

        var called = false
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoteStub { called = true },
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("quinze horas")
        assertThat(called).isTrue()
        assertThat(draft.source).isEqualTo(DraftSource.AI)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(draft.transcript).isEqualTo("quinze horas")
    }

    @Test
    fun fraseDesconhecidaSemRedeMantemRascunhoLocal() = runBlocking {
        var called = false
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoteStub { called = true },
            network = NetworkStatus { false },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("semana que vem")
        assertThat(called).isFalse()
        assertThat(draft.source).isEqualTo(DraftSource.LOCAL)
        assertThat(draft.notes.joinToString()).contains("rascunho local")
    }

    /** O estado real de produção: Supabase não configurado, então a IA está desligada. */
    @Test
    fun fraseDesconhecidaComIaDesligadaMantemRascunhoLocal() = runBlocking {
        var called = false
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoteStub { called = true },
            network = NetworkStatus { true },
            isAiEnabled = { false },
        )
        val draft = hybrid.parse("no dia 25")
        assertThat(called).isFalse()
        assertThat(draft.source).isEqualTo(DraftSource.LOCAL)
        assertThat(draft.notes.joinToString()).contains("rascunho local")
    }

    @Test
    fun fraseDesconhecidaComRemotoQueFalhaMantemRascunhoLocal() = runBlocking {
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = object : RemoteDraftParser {
                override suspend fun parse(transcript: String, nowIso: String, timezone: String, locale: String): ParsedTaskDraft {
                    error("500")
                }
            },
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("daqui a pouco")
        assertThat(draft.source).isEqualTo(DraftSource.LOCAL)
        assertThat(draft.notes.joinToString()).contains("ajuda extra não respondeu")
    }

    @Test
    fun textoVazioNaoEscala() = runBlocking {
        var called = false
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoteStub { called = true },
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("   ")
        assertThat(called).isFalse()
        assertThat(draft.source).isEqualTo(DraftSource.LOCAL)
    }
}

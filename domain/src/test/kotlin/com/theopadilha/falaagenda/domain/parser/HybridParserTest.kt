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

    /**
     * A metade "falta só a hora" do `||` — o caso mais comum de todos, e o que nenhum dos outros
     * testes cobria: todos usam frases com data e hora nulas. Com `&&` no lugar de `||` esta fala
     * deixa de escalar e a suíte continuaria verde, que é como o buraco passaria.
     */
    @Test
    fun faltaSoOHorarioEscalaParaIa() = runBlocking {
        val localDraft = local.parse("tomar remédio amanhã")
        assertThat(localDraft.localDate).isNotNull()
        assertThat(localDraft.localTime).isNull()

        var called = false
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoteStub { called = true },
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("tomar remédio amanhã")
        assertThat(called).isTrue()
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
    }

    /**
     * O remoto completa, não substitui. Sem o merge, o `localDate` que o local acertou era
     * apagado pelo nulo do remoto — e a tela voltava a pedir uma data que o app já tinha.
     */
    @Test
    fun remotoNaoApagaOCampoQueOLocalAcertou() = runBlocking {
        val remotoParcial = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "",
                localDate = null,
                localTime = LocalTime.of(8, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = listOf("A ajuda extra achou o horário."),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remotoParcial,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val localDraft = local.parse("tomar remédio amanhã")
        val draft = hybrid.parse("tomar remédio amanhã")

        assertThat(draft.localDate).isEqualTo(localDraft.localDate)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo(localDraft.title)
        assertThat(draft.isComplete).isTrue()
        assertThat(draft.missingFields).isEmpty()
    }

    /**
     * A nota que o local escreveu para o campo ausente sai do rascunho quando a IA o preenche:
     * mantida, a tela mostraria "Falta o horário" em vermelho acima do horário preenchido.
     */
    @Test
    fun aNotaDoCampoAusenteSaiQuandoIaPreenche() = runBlocking {
        val localDraft = local.parse("tomar remédio amanhã")
        assertThat(localDraft.notes.joinToString()).contains("Falta o horário")

        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoteStub(),
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("tomar remédio amanhã")

        assertThat(draft.notes.joinToString()).doesNotContain("Falta o horário")
        assertThat(draft.notes.joinToString()).contains("A ajuda extra completou o horário.")
    }
}

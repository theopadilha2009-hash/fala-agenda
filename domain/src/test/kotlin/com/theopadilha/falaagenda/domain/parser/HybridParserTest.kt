package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
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

    /**
     * A nota que o próprio `LocalTaskParser` escreve para o ano que ele não cravou não pode
     * sobreviver à IA ter resolvido a data: a tela a renderiza em vermelho logo acima da data
     * preenchida, a mesma contradição que `notasDomescladas` existe para evitar.
     */
    @Test
    fun aNotaDoAnoQueRolouSaiQuandoIaResolveAData() = runBlocking {
        val localDraft = local.parse("reunião 05/08 às 10h")
        assertThat(localDraft.ambiguous).isTrue()
        assertThat(localDraft.notes.joinToString()).contains("já passou")

        val remoto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Reunião",
                localDate = LocalDate.of(2027, 8, 5),
                localTime = LocalTime.of(10, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = listOf("A ajuda extra resolveu a data."),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoto,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("reunião 05/08 às 10h")

        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 8, 5))
        assertThat(draft.notes.joinToString()).doesNotContain("já passou")
        assertThat(draft.notes.joinToString()).contains("A ajuda extra resolveu a data.")
    }

    /** A mesma contradição para a data que não existe no calendário. */
    @Test
    fun aNotaDaDataQueNaoExisteSaiQuandoIaResolveAData() = runBlocking {
        val localDraft = local.parse("consulta 31/02/2027 às 10h")
        assertThat(localDraft.localDate).isNull()
        assertThat(localDraft.notes.joinToString()).contains("não existe no calendário")

        val remoto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Consulta",
                localDate = LocalDate.of(2027, 2, 28),
                localTime = LocalTime.of(10, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = emptyList(),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoto,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("consulta 31/02/2027 às 10h")

        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 2, 28))
        assertThat(draft.notes.joinToString()).doesNotContain("não existe no calendário")
    }

    /**
     * O mecanismo não pode depender de a lista de notas desmentidas ser mantida à mão: uma nota
     * nova do parser sobre a data sai sozinha quando o rascunho final tem data e hora.
     */
    @Test
    fun notaDeDataDoParserSaiSozinhaQuandoORascunhoFicaCompleto() = runBlocking {
        val localDraft = local.parse("reunião 05/08 às 10h")
        val notaDaData = localDraft.notes.first { it.contains("já passou") }

        val remoto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Reunião",
                localDate = LocalDate.of(2027, 8, 5),
                localTime = LocalTime.of(10, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = emptyList(),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoto,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("reunião 05/08 às 10h")

        assertThat(draft.notes).doesNotContain(notaDaData)
    }

    /**
     * A nota de data só cai quando a **IA** resolveu a data — não quando o rascunho final tem data.
     *
     * "reunião 05/08 de manhã" tem data do local (o palpite de 2027) e hora faltando, então
     * escala; a IA devolve só a hora, e a data do rascunho final continua sendo o palpite do local.
     * Desmentir a nota pelo rascunho final apagava justamente a nota que existe para explicar o
     * 2027, e a tela mostrava "2027" em silêncio — o defeito que este lote veio corrigir.
     */
    @Test
    fun aNotaDaDataFicaQuandoIaTrazSoOHorario() = runBlocking {
        val localDraft = local.parse("reunião 05/08 de manhã")
        assertThat(localDraft.localDate).isEqualTo(LocalDate.of(2027, 8, 5))
        assertThat(localDraft.localTime).isNull()
        assertThat(localDraft.notes.joinToString()).contains("já passou")

        val remoto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Reunião",
                localDate = null,
                localTime = LocalTime.of(9, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = emptyList(),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoto,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("reunião 05/08 de manhã")

        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 8, 5))
        assertThat(draft.notes.joinToString()).contains("já passou")
    }

    /**
     * A nota do instante vencido fala do **resultado**, não de quem trouxe cada metade.
     *
     * `reunião hoje às 3` às 10h: o instante do local (20/08 03:00) já passou e o "às 3" sem
     * período deixa o rascunho ambíguo, então escala; a IA devolve só a hora, `23:00`, e o final
     * vira 20/08 23:00 — futuro. Desmentindo o instante pelas duas metades do remoto, a nota "Essa
     * data e horário já passaram." ficava em vermelho logo acima de um instante futuro, e a caixa
     * rápida confirmava assim (`qc=true`). É a mesma contradição visível que o desmentido existe
     * para evitar.
     */
    @Test
    fun aNotaDoInstanteVencidoSaiQuandoORascunhoFinalEhFuturo() = runBlocking {
        val localDraft = local.parse("reunião hoje às 3")
        assertThat(localDraft.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(localDraft.notes.joinToString()).contains("já passaram")

        val remoto = object : RemoteDraftParser {
            override suspend fun parse(
                transcript: String,
                nowIso: String,
                timezone: String,
                locale: String,
            ): ParsedTaskDraft = ParsedTaskDraft(
                title = "Reunião",
                localDate = null,
                localTime = LocalTime.of(23, 0),
                confidence = 0.9,
                missingFields = emptySet(),
                ambiguous = false,
                transcript = transcript,
                notes = emptyList(),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoto,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("reunião hoje às 3")

        assertThat(draft.localTime).isEqualTo(LocalTime.of(23, 0))
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.notes.joinToString()).doesNotContain("já passaram")
    }

    /**
     * F-2: a nota de **data ilegível** da IA sobrevive ao merge quando ela é verdadeira — o
     * rascunho final fica mesmo sem data.
     *
     * A nota nasce na fronteira (`ParseResponse.toDraft`, quando `local_date` não é um `LocalDate`)
     * e o merge a carrega em `remotoDraft.notes`. O risco medido é o `notasDomescladas`: ele
     * **descarta** as notas do local sobre a data quando a IA traz a data, e a peça já regrediu
     * antes (uma nota nova do parser ficava de fora da lista de desmentidas).
     *
     * O que este teste prende é o par: a nota fica **e** o campo falta. Uma nota de perda ao lado
     * do campo preenchido é a contradição visível que a caixa de confirmação rápida mostraria no
     * caminho de um toque ("Vai avisar 25 de outubro" com "Ficou sem essa parte" em vermelho logo
     * abaixo). Por isso a fala local não traz data: `"às 9h"` dá hora e não dá dia, a IA devolve
     * `local_date = null` (ilegível), e o final fica sem data — que é exatamente o caso em que a
     * frase da IA é verdadeira. Se o merge a suprimisse aqui, ela perderia o aviso de que a ajuda
     * extra errou essa parte sem nada no lugar.
     *
     * O outro teste da nota (`RespostaDaIaForaDaFaixaTest.dataQueNaoDaParaLerViraAusenteEAvisa`)
     * para no cliente: este é o que prova que a frase atravessa o merge até o rascunho final.
     */
    @Test
    fun aNotaDeDataIlegivelFicaQuandoOFinalRealmenteFicouSemData() = runBlocking {
        val notaDaData = "A ajuda extra devolveu uma data que não deu para entender. Ficou sem essa parte."
        val remoto = object : RemoteDraftParser {
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
                notes = listOf(notaDaData),
                source = DraftSource.AI,
            )
        }
        val hybrid = HybridParser(
            local = local,
            clock = clock,
            remote = remoto,
            network = NetworkStatus { true },
            isAiEnabled = { true },
        )
        val draft = hybrid.parse("tomar remédio às 9h")

        // O par que define a nota como verdadeira: o campo faltou de verdade…
        assertThat(draft.localDate).isNull()
        // …e a frase que avisa a perda está lá.
        assertThat(draft.notes).contains(notaDaData)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.notes.joinToString()).doesNotContain("Falta o horário")
    }
}

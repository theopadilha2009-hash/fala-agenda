package com.theopadilha.falaagenda.data.remote

import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.toMonthPtBr
import com.theopadilha.falaagenda.domain.parser.RemoteDraftParser
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class SupabaseConfig(
    val url: String,
    val anonKey: String,
) {
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()
}

class ActivationClient(
    private val config: SupabaseConfig,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun activate(code: String): String {
        if (!config.isConfigured) error("Serviço não configurado")
        val body = json.encodeToString(ActivateBody.serializer(), ActivateBody(code.trim()))
        val request = Request.Builder()
            .url("${config.url.trimEnd('/')}/functions/v1/activate-device")
            .addHeader("apikey", config.anonKey)
            .addHeader("Authorization", "Bearer ${config.anonKey}")
            .post(body.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = runCatching { json.decodeFromString(ErrorBody.serializer(), raw) }.getOrNull()
                error(err?.error ?: "Não foi possível ativar (${response.code})")
            }
            return json.decodeFromString(ActivateResponse.serializer(), raw).token
        }
    }
}

class ParseReminderClient(
    private val config: SupabaseConfig,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RemoteDraftParser {
    override suspend fun parse(
        transcript: String,
        nowIso: String,
        timezone: String,
        locale: String,
    ): ParsedTaskDraft {
        val token = tokenProvider() ?: error("sem token")
        val payload = json.encodeToString(
            ParseBody.serializer(),
            ParseBody(transcript = transcript, now = nowIso, timezone = timezone, locale = locale),
        )
        val request = Request.Builder()
            .url("${config.url.trimEnd('/')}/functions/v1/parse-reminder")
            .addHeader("apikey", config.anonKey)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("parse-reminder ${response.code}")
            return json.decodeFromString(ParseResponse.serializer(), raw).toDraft(transcript)
        }
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * A nota de quando a IA devolve uma faixa que não existe no calendário.
 *
 * O schema do LLM (`supabase/functions/_shared/openai.ts`) declara `day_of_month` e
 * `month_of_year` como `integer` sem `minimum`/`maximum`: o modelo pode trocar a base
 * (0-based por 1-based) ou simplesmente alucinar, e `month_of_year = 13` derrubava a home —
 * a caixa de confirmação rápida calculava a primeira ocorrência na composição e o
 * `YearMonth.of(2026, 13)` estourava. O campo descartado vira `null`, que já é o caminho
 * normal de "não foi dito"; a nota é para ela saber que a ajuda extra errou essa parte.
 *
 * O texto segue o vocabulário das outras notas da IA ("A ajuda extra não respondeu."), em
 * PT-BR e sem culpar ela.
 */
private const val NOTA_FAIXA_INVALIDA =
    "A ajuda extra devolveu uma data fora do calendário. Ficou sem essa parte."

/** A mesma ideia, para os dois campos que a IA devolve como texto: `local_date` e `local_time`. */
private const val NOTA_DATA_ILEGIVEL =
    "A ajuda extra devolveu uma data que não deu para entender. Ficou sem essa parte."

private const val NOTA_HORA_ILEGIVEL =
    "A ajuda extra devolveu um horário que não deu para entender. Ficou sem essa parte."

/** O mês só existe em `1..12`; o dia só existe em `1..31`. Fora disso é dado da IA, não um pedido. */
private fun faixaDaRecorrencia(
    dayOfMonth: Int?,
    monthOfYear: Int?,
): Pair<Int?, Int?> {
    val dia = dayOfMonth?.takeIf { it in 1..31 }
    val mes = monthOfYear?.takeIf { it in 1..12 }
    return dia to mes
}

/**
 * A regra que a IA devolve só é uma recorrência se tiver os campos que ela **descreve** — a
 * pergunta é `RecurrenceRule.isCoherent`, no domínio, e não uma condição escrita de novo aqui.
 *
 * A versão anterior perguntava só pelo `YEARLY` (`dia == null || mes == null`) e o `MONTHLY` ficou
 * de fora: `{"kind":"MONTHLY","day_of_month":null}` é **conformante ao schema**
 * (`supabase/functions/_shared/openai.ts`: `type: ["integer","null"]`, e `minimum`/`maximum` não
 * mordem em `null`), então nem é preciso o modelo alucinar. O `describePtBr()` renderizava
 * `"Todo dia ? do mês"` na caixa "Pode salvar?" com `canQuickConfirm = true` — o texto quebrado no
 * caminho do salvamento com um toque. Copiada, a condição deixou um `kind` inteiro de fora; vinda
 * do domínio, o `kind` novo já nasce coberto.
 *
 * Faltando o campo, a regra cai para `NONE` e a nota explica — a recorrência **deixa de repetir**
 * (ver a nota e o relatório do lote: é decisão de produto, entre "texto quebrado" e "regra errada").
 */
private fun kindCoerente(kind: RecurrenceKind, dia: Int?, mes: Int?): RecurrenceKind =
    if (RecurrenceRule(kind = kind, dayOfMonth = dia, monthOfYear = mes).isCoherent) {
        kind
    } else {
        RecurrenceKind.NONE
    }

/**
 * A nota de quando a IA disse que a regra repete e **não disse o campo que ela precisa** — o
 * `MONTHLY` sem dia e o `YEARLY` sem mês ou sem dia. O campo não veio fora da faixa (aí a nota é a
 * de baixo); ele simplesmente não veio.
 *
 * Sem esta nota a perda da recorrência seria invisível: a caixa mostraria "Única" para uma fala em
 * que ela pediu "todo mês", e ela não teria como saber que a ajuda extra deixou a metade de fora.
 * A frase não culpa ela e não fala em erro de calendário, porque aqui não houve erro — houve
 * omissão.
 */
private fun notaDaRecorrenciaIncompleta(kindLido: RecurrenceKind): String = when (kindLido) {
    RecurrenceKind.MONTHLY ->
        "A ajuda extra disse que repete todo mês, mas não disse o dia. Ficou sem repetir."
    else ->
        "A ajuda extra disse que repete todo ano, mas não disse a data. Ficou sem repetir."
}

/**
 * A nota de quando a data pedida existe na faixa, mas não **naquele mês** — "todo dia 30 de
 * fevereiro", que o `clampToValidDate` rola para 28/02 em silêncio.
 *
 * O clamp é documentado e intencional (`RecurrenceEngine`), então a regra fica como veio; o que
 * faltava era ela saber. Só a regra **anual** avisa: no mensal o dia 31 é um pedido legítimo (ele
 * existe em sete meses) e rolar para o último dia do mês é o comportamento prometido. O 29 de
 * fevereiro fica de fora: existe em algum ano, que é a série anual.
 */
private fun notaDoDiaQueNaoExisteNoMes(dia: Int?, mes: Int?, kind: RecurrenceKind): String? {
    if (kind != RecurrenceKind.YEARLY || dia == null || mes == null) return null
    if (dia == 29 && mes == 2) return null
    if (RecurrenceEngine.dayExistsInMonth(dia, mes)) return null
    return "A ajuda extra pediu o dia $dia de ${mes.toMonthPtBr()}, que não existe. A data rola para o último dia do mês."
}

@Serializable
private data class ActivateBody(val code: String)

@Serializable
private data class ActivateResponse(val token: String)

@Serializable
private data class ErrorBody(val error: String? = null)

@Serializable
private data class ParseBody(
    val transcript: String,
    val now: String,
    val timezone: String,
    val locale: String,
)

/**
 * `internal`, e não `private`: o teste de fronteira precisa decodificar a resposta pelo mesmo
 * `Json.decodeFromString(ParseResponse.serializer()).toDraft(...)` que a produção usa, e o caminho
 * pela rede (24300 casos num `MockWebServer`) custaria minutos. A visibilidade é a única coisa que
 * muda aqui — a conversão e a serialização são as mesmas.
 */
@Serializable
internal data class ParseResponse(
    val title: String = "",
    @SerialName("local_date") val localDate: String? = null,
    @SerialName("local_time") val localTime: String? = null,
    val recurrence: ParseRecurrence = ParseRecurrence(),
    val confidence: Double = 0.5,
    val ambiguous: Boolean = false,
    @SerialName("missing_fields") val missingFields: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    fun toDraft(transcript: String): ParsedTaskDraft {
        val (dia, mes) = faixaDaRecorrencia(recurrence.dayOfMonth, recurrence.monthOfYear)
        val kindLido = runCatching { RecurrenceKind.valueOf(recurrence.kind.uppercase()) }.getOrDefault(RecurrenceKind.NONE)
        val kind = kindCoerente(kindLido, dia, mes)
        // As notas entram aqui, na fronteira, e não no domínio: só a IA produz a faixa inválida,
        // e é aqui que se sabe que o número veio dela. O rascunho local nunca chega com 13.
        val faixaDescartada = dia != recurrence.dayOfMonth || mes != recurrence.monthOfYear
        // O mesmo para a data e a hora: `LocalDate.parse("2026-02-30")` estourava e o `catch` do
        // `HybridParser` jogava fora o rascunho da IA inteiro — a data que ela tinha acertado e a
        // nota do descarte junto. Aqui o valor ilegível vira ausente, que é o "não foi dito" que
        // o app já sabe tratar, e a nota diz qual das duas partes ficou de fora.
        val data = localDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val hora = localTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        val notasDoRecorrencia = buildList {
            if (faixaDescartada) add(NOTA_FAIXA_INVALIDA)
            // A regra rebaixada **sem** campo fora da faixa: a IA disse que repete e não disse o
            // campo. A de cima já explica esse caso (o campo veio, só não existe no calendário), e
            // as duas juntas seriam duas frases para o mesmo descarte.
            if (kind != kindLido && !faixaDescartada) add(notaDaRecorrenciaIncompleta(kindLido))
            if (localDate != null && data == null) add(NOTA_DATA_ILEGIVEL)
            if (localTime != null && hora == null) add(NOTA_HORA_ILEGIVEL)
            notaDoDiaQueNaoExisteNoMes(dia, mes, kind)?.let { add(it) }
        }
        return ParsedTaskDraft(
            title = title,
            localDate = data,
            localTime = hora,
            recurrence = RecurrenceRule(
                kind = kind,
                weekDays = recurrence.weekDays.mapNotNull { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() }.toSet(),
                dayOfMonth = dia,
                monthOfYear = mes,
            ),
            confidence = confidence,
            missingFields = missingFields.mapNotNull {
                runCatching { MissingDraftField.valueOf(it.uppercase()) }.getOrNull()
            }.toSet(),
            ambiguous = ambiguous,
            transcript = transcript,
            notes = notes + notasDoRecorrencia,
            source = DraftSource.AI,
        )
    }
}

@Serializable
internal data class ParseRecurrence(
    val kind: String = "NONE",
    @SerialName("week_days") val weekDays: List<String> = emptyList(),
    @SerialName("day_of_month") val dayOfMonth: Int? = null,
    @SerialName("month_of_year") val monthOfYear: Int? = null,
)

package com.theopadilha.falaagenda.data.remote

import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.parser.RemoteDraftParser
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

@Serializable
private data class ParseResponse(
    val title: String = "",
    @SerialName("local_date") val localDate: String? = null,
    @SerialName("local_time") val localTime: String? = null,
    val recurrence: ParseRecurrence = ParseRecurrence(),
    val confidence: Double = 0.5,
    val ambiguous: Boolean = false,
    @SerialName("missing_fields") val missingFields: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    fun toDraft(transcript: String): ParsedTaskDraft = ParsedTaskDraft(
        title = title,
        localDate = localDate?.let { LocalDate.parse(it) },
        localTime = localTime?.let { LocalTime.parse(it) },
        recurrence = RecurrenceRule(
            kind = runCatching { RecurrenceKind.valueOf(recurrence.kind.uppercase()) }.getOrDefault(RecurrenceKind.NONE),
            weekDays = recurrence.weekDays.mapNotNull { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() }.toSet(),
            dayOfMonth = recurrence.dayOfMonth,
            monthOfYear = recurrence.monthOfYear,
        ),
        confidence = confidence,
        missingFields = missingFields.mapNotNull {
            runCatching { MissingDraftField.valueOf(it.uppercase()) }.getOrNull()
        }.toSet(),
        ambiguous = ambiguous,
        transcript = transcript,
        notes = notes,
        source = DraftSource.AI,
    )
}

@Serializable
private data class ParseRecurrence(
    val kind: String = "NONE",
    @SerialName("week_days") val weekDays: List<String> = emptyList(),
    @SerialName("day_of_month") val dayOfMonth: Int? = null,
    @SerialName("month_of_year") val monthOfYear: Int? = null,
)

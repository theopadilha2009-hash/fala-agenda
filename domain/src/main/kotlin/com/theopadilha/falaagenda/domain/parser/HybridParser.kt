package com.theopadilha.falaagenda.domain.parser

import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.time.AppClock
import java.util.Locale

interface RemoteDraftParser {
    suspend fun parse(
        transcript: String,
        nowIso: String,
        timezone: String,
        locale: String,
    ): ParsedTaskDraft
}

fun interface NetworkStatus {
    fun isOnline(): Boolean
}

class HybridParser(
    private val local: LocalTaskParser,
    private val clock: AppClock,
    private val remote: RemoteDraftParser?,
    private val network: NetworkStatus,
    private val isAiEnabled: () -> Boolean,
    private val locale: Locale = Locale.forLanguageTag("pt-BR"),
) {
    suspend fun parse(transcript: String): ParsedTaskDraft {
        val localDraft = local.parse(transcript)
        if (!localDraft.ambiguous) return localDraft
        if (!isAiEnabled() || remote == null || !network.isOnline()) {
            return localDraft.copy(
                notes = localDraft.notes + "Mantivemos o rascunho local para você corrigir.",
            )
        }
        return try {
            val remoteDraft = remote.parse(
                transcript = transcript,
                nowIso = clock.instant().toString(),
                timezone = clock.zoneId().id,
                locale = locale.toLanguageTag(),
            )
            remoteDraft.copy(
                transcript = transcript,
                notes = (localDraft.notes + remoteDraft.notes).distinct(),
            )
        } catch (_: Exception) {
            localDraft.copy(
                notes = localDraft.notes + "A ajuda extra não respondeu. Você pode corrigir na mão.",
            )
        }
    }
}

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
        if (!deveEscalar(transcript, localDraft)) return localDraft
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

    /**
     * Decide quando vale gastar uma chamada de IA.
     *
     * O gatilho é "falta um campo essencial" — data ou hora ausente no rascunho local — e não
     * "a confiança veio baixa". Confiança é heurística: um número que o parser local estima, e
     * errar para os dois lados custa caro (rebaixado demais escala tudo e a IA deixa de ser
     * exceção; alto demais devolve a frase sem data para o usuário). Campo ausente é observável:
     * ou o rascunho tem data e hora, ou não tem.
     *
     * O caso concreto que isso conserta: "quinze horas", "semana que vem", "no dia 25",
     * "daqui a pouco" e "dois de maio" são frases que o parser local simplesmente não conhece.
     * Ele devolve data/hora nulas, confiança por volta de 0.55 e `ambiguous = false`, porque não
     * viu ambiguidade — só não viu nada. Antes, o único gatilho era `ambiguous`, então essas
     * frases nunca consultavam a IA: a tela mostrava "Falta a data" e a ajuda extra que o app
     * promete nunca era acionada. Agora a ausência de campo essencial escala.
     *
     * Fica de fora o que não precisa de IA: quando o local reconheceu tudo (`ambiguous = false`
     * com data e hora), segue o caminho offline e barato, que é a maioria dos casos; e texto
     * vazio/branco não tem o que a IA completar.
     *
     * O remoto é uma segunda opinião, nunca uma substituição obrigatória: se a IA está desligada,
     * se não há rede ou se a chamada falha, o rascunho local volta intacto. O fallback é o
     * contrato — o remoto não pode piorar o que o local já entregou.
     */
    private fun deveEscalar(transcript: String, localDraft: ParsedTaskDraft): Boolean {
        if (localDraft.ambiguous) return true
        if (transcript.isBlank()) return false
        return localDraft.localDate == null || localDraft.localTime == null
    }
}

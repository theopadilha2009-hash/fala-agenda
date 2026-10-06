package com.theopadilha.falaagenda.domain.parser

import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.time.AppClock
import java.time.LocalDate
import java.time.LocalTime
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
            mergeRemote(localDraft, remoteDraft, transcript)
        } catch (_: Exception) {
            localDraft.copy(
                notes = localDraft.notes + "A ajuda extra não respondeu. Você pode corrigir na mão.",
            )
        }
    }

    /**
     * O remoto **completa** o local; não o substitui.
     *
     * Um `copy` direto do rascunho remoto parecia inofensivo e não era: o prompt do remoto manda
     * devolver `null` para o que ele não soube ler (`supabase/functions/_shared/openai.ts`), então
     * bastava o modelo falhar na leitura de "amanhã" para a data que o local tinha acertado ser
     * apagada — e a tela voltava a pedir "Falta a data" para uma frase que o app já tinha
     * entendido. Era o remoto piorando o local, que é exatamente o que o fallback existe para
     * impedir; a escalação nova só tornou esse caminho alcançável, porque antes uma frase com
     * data e sem hora nem chegava aqui.
     *
     * Campo a campo: o remoto vence quando traz valor, o local fica quando o remoto devolve nulo
     * ou vazio. Recorrência segue a mesma ideia, mas o "vazio" dela é `NONE` — a regra que não
     * repete —, e não um nulo.
     */
    private fun mergeRemote(
        localDraft: ParsedTaskDraft,
        remoteDraft: ParsedTaskDraft,
        transcript: String,
    ): ParsedTaskDraft {
        val title = remoteDraft.title.ifBlank { localDraft.title }
        val localDate = remoteDraft.localDate ?: localDraft.localDate
        val localTime = remoteDraft.localTime ?: localDraft.localTime
        val missing = buildSet {
            if (title.isBlank()) add(MissingDraftField.TITLE)
            if (localDate == null) add(MissingDraftField.DATE)
            if (localTime == null) add(MissingDraftField.TIME)
        }
        return remoteDraft.copy(
            title = title,
            localDate = localDate,
            localTime = localTime,
            recurrence = if (remoteDraft.recurrence.isRecurring) {
                remoteDraft.recurrence
            } else {
                localDraft.recurrence
            },
            amountCents = remoteDraft.amountCents ?: localDraft.amountCents,
            observation = remoteDraft.observation.ifBlank { localDraft.observation },
            missingFields = missing,
            // Preenchido o essencial, o rascunho deixa de ser ambíguo — do contrário a caixa
            // rápida continuaria barrada (`canQuickConfirm`) por uma dúvida que a IA já resolveu.
            ambiguous = remoteDraft.ambiguous && missing.isNotEmpty(),
            transcript = transcript,
            notes = notasDomescladas(localDraft.notes, remoteDraft.notes, localDate, localTime),
        )
    }

    /**
     * As notas do local que a IA acabou de tornar falsas saem do rascunho.
     *
     * Elas são geradas em `LocalTaskParser` para o que **faltou** ("Falta a data", "Falta o
     * horário") e para o instante vencido, e a tela as mostra em vermelho
     * (`ConfirmDraftScreen`). Mantidas depois de a IA preencher o campo, a tela exibiria "Falta o
     * horário" logo acima do horário preenchido — a contradição visível que o app inteiro evita.
     * O casamento é por prefixo porque a nota nasce como texto pronto lá, e não como código.
     */
    private fun notasDomescladas(
        locais: List<String>,
        remotas: List<String>,
        localDate: LocalDate?,
        localTime: LocalTime?,
    ): List<String> {
        val desmentidas = buildSet {
            if (localDate != null) add(NOTA_FALTA_DATA)
            if (localTime != null) add(NOTA_FALTA_HORA)
            if (localDate != null && localTime != null) add(NOTA_INSTANTE_PASSADO)
        }
        return (locais.filterNot { nota -> desmentidas.any { nota.startsWith(it) } } + remotas)
            .distinct()
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

    private companion object {
        // Prefixos das notas que o `LocalTaskParser` escreve para o que faltou e para o instante
        // vencido. Casadas por prefixo porque lá a nota nasce como frase pronta para a tela, e o
        // que estas constantes precisam é só reconhecê-la depois — a redação pode ganhar um
        // complemento sem quebrar o casamento.
        const val NOTA_FALTA_DATA = "Falta a data"
        const val NOTA_FALTA_HORA = "Falta o horário"
        const val NOTA_INSTANTE_PASSADO = "Essa data e horário já passaram"
    }
}

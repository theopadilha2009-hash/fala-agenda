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
        val mergedDate = remoteDraft.localDate ?: localDraft.localDate
        val mergedTime = remoteDraft.localTime ?: localDraft.localTime
        val missing = buildSet {
            if (title.isBlank()) add(MissingDraftField.TITLE)
            if (mergedDate == null) add(MissingDraftField.DATE)
            if (mergedTime == null) add(MissingDraftField.TIME)
        }
        return remoteDraft.copy(
            title = title,
            localDate = mergedDate,
            localTime = mergedTime,
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
            notes = notasDomescladas(
                locais = localDraft.notes,
                remotas = remoteDraft.notes,
                remotoTrouxeData = remoteDraft.localDate != null,
                remotoTrouxeHora = remoteDraft.localTime != null,
            ),
        )
    }

    /**
     * As notas do local que a IA acabou de tornar falsas saem do rascunho.
     *
     * Elas são geradas em `LocalTaskParser` para o que **faltou** ("Falta a data", "Falta o
     * horário") e para o que o parser não cravou (a data que não existe, o ano que rolou, o
     * instante vencido), e a tela as mostra em vermelho (`ConfirmDraftScreen`). Mantidas depois de
     * a IA preencher o campo, a tela exibiria "Falta o horário" logo acima do horário preenchido —
     * a contradição visível que o app inteiro evita.
     *
     * O casamento é por prefixo, e o prefixo mora em [NotasDoRascunho], na origem da nota. Antes
     * esta classe tinha a lista própria de frases completas, e uma nota nova do parser — como as
     * de data que este lote criou — ficava de fora dela sem que nada avisasse: a tela mostrava em
     * vermelho "“05/08” já passou este ano" logo acima da data que a IA tinha acabado de resolver.
     * Com o assunto marcado na origem, a nota nova nasce desmentível.
     *
     * O que desmente é o que **a IA trouxe**, não o rascunho final ter o campo. A diferença importa
     * no caminho mais comum da escalação: "reunião 05/08 de manhã" tem data do local e hora
     * faltando, então escala; a IA devolve só a hora. Com o rascunho final como critério, a data
     * "2027-08-05" — que é o palpite que o **local** deu — desmentia a nota "“05/08” já passou este
     * ano; ficou em 2027", e a tela mostrava 2027 em silêncio, sem a nota que existe justamente
     * para explicar esse 2027. A pergunta certa é "a IA resolveu a data?", não "o rascunho tem
     * data?".
     */
    private fun notasDomescladas(
        locais: List<String>,
        remotas: List<String>,
        remotoTrouxeData: Boolean,
        remotoTrouxeHora: Boolean,
    ): List<String> {
        val desmentidas = buildSet {
            if (remotoTrouxeData) addAll(NotasDoRascunho.SOBRE_A_DATA)
            if (remotoTrouxeHora) addAll(NotasDoRascunho.SOBRE_A_HORA)
            if (remotoTrouxeData && remotoTrouxeHora) addAll(NotasDoRascunho.SOBRE_O_INSTANTE)
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

}

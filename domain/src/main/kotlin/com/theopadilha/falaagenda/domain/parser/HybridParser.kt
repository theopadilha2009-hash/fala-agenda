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
    companion object {
        /**
         * O prefixo da nota que a fronteira da IA escreve quando a regra que ela devolveu **não
         * repete** — "a IA disse que repete e não disse o campo", ou o campo veio fora da faixa.
         *
         * Mora aqui, e não junto das outras notas em [NotasDoRascunho], por um motivo: o
         * [NotasDoRascunho] marca notas do **parser local** para o merge desmentir, e esta nasce do
         * outro lado. Quem sabe se ela é verdade não é quem a escreve — é o merge, que pode
         * restaurar a recorrência do local e desfazer o rebaixamento. A fronteira
         * (`SupabaseFunctions`) compõe o texto a partir **deste** prefixo, então o par
         * escreve/desmente não pode divergir sem que o compilador veja.
         */
        const val NOTA_RECORRENCIA_PERDIDA = "A ajuda extra disse que repete"

        /**
         * O prefixo da nota que a fronteira da IA escreve quando o campo da recorrência veio **fora
         * da faixa** do calendário (`day_of_month = 32`, `month_of_year = 13`).
         *
         * Vale o mesmo raciocínio da [NOTA_RECORRENCIA_PERDIDA], e é por isso que ela mora aqui: a
         * nota afirma que a repetição se perdeu, mas quem decide se a perda houve é o merge — o
         * local pode ter a regra certa e restaurá-la. Quando o desfecho repete, "Ficou sem essa
         * parte" é falso: a parte está lá, e o descarte do campo inválido não custou nada.
         */
        const val NOTA_FAIXA_DESCARTADA = "A ajuda extra devolveu uma data fora do calendário"
    }

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
     *
     * O **título é a exceção**: quem vence é o local, quando ele acertou. Ele já removeu o verbo
     * ("levar a Maria no médico dia 25" → "Levar Maria médico"), e a IA devolvendo "Compromisso"
     * apagava o único pedaço da frase que dizia do que se tratava. Como o título alimenta
     * `isComplete`/`canQuickConfirm`, a troca nem passava pela tela: a caixa rápida salvava
     * "Compromisso" em silêncio. O remoto preenche o título só quando o local não achou nenhum
     * — completar o que falta é o trabalho dele; trocar o que já está certo, não.
     */
    private fun mergeRemote(
        localDraft: ParsedTaskDraft,
        remoteDraft: ParsedTaskDraft,
        transcript: String,
    ): ParsedTaskDraft {
        val tituloLocal = localDraft.title.trim()
        val tituloRemoto = remoteDraft.title.trim()
        val title = if (tituloLocal.isNotBlank()) tituloLocal else tituloRemoto
        // A divergência vira nota: a troca de nome não pode ser invisível como era antes. Sem
        // isso, ela salvaria um cartão com outro nome sem nunca saber que a ajuda extra mexeu.
        val tituloDivergiu = tituloLocal.isNotBlank() && tituloRemoto.isNotBlank() && tituloRemoto != tituloLocal
        val mergedDate = remoteDraft.localDate ?: localDraft.localDate
        val mergedTime = remoteDraft.localTime ?: localDraft.localTime
        val missing = buildSet {
            if (title.isBlank()) add(MissingDraftField.TITLE)
            if (mergedDate == null) add(MissingDraftField.DATE)
            if (mergedTime == null) add(MissingDraftField.TIME)
        }
        val recurrence = if (remoteDraft.recurrence.isRecurring) {
            remoteDraft.recurrence
        } else {
            localDraft.recurrence
        }
        val notes = notasDomescladas(
            locais = localDraft.notes,
            remotas = remoteDraft.notes,
            remotoTrouxeData = remoteDraft.localDate != null,
            remotoTrouxeHora = remoteDraft.localTime != null,
            finalTemData = mergedDate != null,
            finalTemHora = mergedTime != null,
            recorrenciaFinalRepete = recurrence.isRecurring,
        )
        return remoteDraft.copy(
            title = title,
            localDate = mergedDate,
            localTime = mergedTime,
            recurrence = recurrence,
            amountCents = remoteDraft.amountCents ?: localDraft.amountCents,
            observation = remoteDraft.observation.ifBlank { localDraft.observation },
            missingFields = missing,
            // Preenchido o essencial, o rascunho deixa de ser ambíguo — do contrário a caixa
            // rápida continuaria barrada (`canQuickConfirm`) por uma dúvida que a IA já resolveu.
            ambiguous = remoteDraft.ambiguous && missing.isNotEmpty(),
            transcript = transcript,
            notes = if (tituloDivergiu) {
                notes + "A ajuda extra chamou de “$tituloRemoto”. Ficou “$tituloLocal”."
            } else {
                notes
            },
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
     *
     * O instante vencido é a exceção, e por um motivo: as notas de "falta" falam do campo (a IA
     * preencheu a hora?), mas [NotasDoRascunho.INSTANTE_PASSADO] fala do **resultado** — "essa data
     * e horário já passaram". Quem decide se isso é verdade é o instante final, não quem trouxe
     * cada metade. Exigir as duas do remoto deixava a nota ao lado de um instante futuro: com
     * `marcar reunião hoje às 8h e pagar conta` (20/08 08:00, já passado) o remoto devolve só a hora
     * `23:00`, o final vira 20/08 23:00 — futuro — e a nota continuava dizendo "já passaram", com
     * `qc=true`. O remoto só acrescenta campos, então "final completo" já implica que o palpite
     * local não está mais sozinho.
     */
    private fun notasDomescladas(
        locais: List<String>,
        remotas: List<String>,
        remotoTrouxeData: Boolean,
        remotoTrouxeHora: Boolean,
        finalTemData: Boolean,
        finalTemHora: Boolean,
        recorrenciaFinalRepete: Boolean,
    ): List<String> {
        val desmentidas = buildSet {
            if (remotoTrouxeData) addAll(NotasDoRascunho.SOBRE_A_DATA)
            if (remotoTrouxeHora) addAll(NotasDoRascunho.SOBRE_A_HORA)
            if (finalTemData && finalTemHora) addAll(NotasDoRascunho.SOBRE_O_INSTANTE)
        }
        val doLocal = locais.filterNot { nota -> desmentidas.any { nota.startsWith(it) } }
        // A nota de recorrência perdida é **do remoto**, e por isso não passa pelo desmentido por
        // prefixo: quem decide se ela é verdade é o desfecho do merge, não quem a escreveu.
        //
        // A fronteira da IA rebaixa a regra a `NONE` quando falta o campo que ela descreve e escreve
        // a nota junto (`SupabaseFunctions.notaDaRecorrenciaIncompleta`). O `toDraft` está certo: a
        // regra que ele produz não repete. O que a desfaz é o `mergeRemote`, uma camada acima — ele
        // só aceita a recorrência do remoto quando ela `isRecurring`, então a regra rebaixada é
        // descartada e a do **local** volta. Mantida, a nota falava de uma perda que não houve:
        //
        //     fala "todo dia 5 do mês"
        //       local: MONTHLY dia=5 (falta a hora, escala)
        //       IA:    MONTHLY sem dia → fronteira rebaixa a NONE + nota
        //       final: MONTHLY dia=5, 'Todo dia 5 do mês', isComplete=true, qc=true
        //              notes=[...mas não disse o dia. Ficou sem repetir.]
        //
        // A caixa "Pode salvar?" mostrava a regra certa com o aviso de perda logo abaixo, em
        // vermelho, no caminho do salvamento com um toque. A doutrina do `HybridParser` — a
        // contradição visível que o app inteiro evita — vale na direção inversa: não afirmar uma
        // perda que não houve.
        //
        // O critério é o desfecho final (`recurrence.isRecurring`), e não "o local tinha
        // recorrência": quando o local também não reconheceu nada, o final é `NONE` e a nota fica —
        // e aí ela é verdadeira, e sem ela a perda seria silenciosa.
        val doRemoto = if (recorrenciaFinalRepete) {
            // As duas notas falam da mesma coisa — a repetição se perdeu — e caem juntas quando o
            // desfecho mostra que ela não se perdeu. A de faixa entra aqui pelo mesmo motivo: com o
            // campo inválido descartado e o local restaurando a regra, "Ficou sem essa parte" é
            // falso, porque a parte está lá.
            remotas.filterNot {
                it.startsWith(NOTA_RECORRENCIA_PERDIDA) || it.startsWith(NOTA_FAIXA_DESCARTADA)
            }
        } else {
            remotas
        }
        return (doLocal + doRemoto).distinct()
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

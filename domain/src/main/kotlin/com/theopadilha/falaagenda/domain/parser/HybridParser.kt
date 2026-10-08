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
        val notas = notasDomescladas(
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
            // A ambiguidade **local** que a IA não endereçou sobrevive ao merge.
            //
            // A metade de antes (`remoteDraft.ambiguous && missing.isNotEmpty()`) só cobria a dúvida
            // que NASCE da ausência de um campo: "faltava a hora, a IA preencheu, a dúvida acabou".
            // Mas `missing = []` significa "o remoto **preencheu** tudo", não "o remoto **resolveu**
            // o conflito" — são coisas diferentes. O intervalo em horas ("de 8 em 8 horas ... às
            // 8h") deixa o rascunho local ambíguo com data e hora JÁ preenchidas (a primeira dose foi
            // dita), então `missing` vinha vazio e o `&&` apagava a ambiguidade do intervalo sem que
            // a IA tivesse dito nada sobre ele: a caixa verde confirmava em um toque e a série
            // virava tarefa única — o dano que o ramo local acabou de fechar, ainda aberto no
            // caminho com a IA ligada, que é o padrão do app configurado.
            //
            // O juiz é o mesmo que já decide as notas: `notasDomescladas` sabe se o remoto endereçou
            // cada assunto (a IA trazendo a data desmente as notas sobre a data, e só elas), então a
            // ambiguidade sobrevive junto da nota local que a expressa. Um critério para o mesmo
            // fenômeno, em vez de um segundo que divergiria do primeiro.
            ambiguous = (remoteDraft.ambiguous && missing.isNotEmpty()) ||
                (localDraft.ambiguous && notas.locaisQueFicaram.isNotEmpty()),
            transcript = transcript,
            notes = if (tituloDivergiu) {
                notas.finais + "A ajuda extra chamou de “$tituloRemoto”. Ficou “$tituloLocal”."
            } else {
                notas.finais
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
    ): NotasDomescladas {
        val desmentidas = buildSet {
            if (remotoTrouxeData) addAll(NotasDoRascunho.SOBRE_A_DATA)
            if (remotoTrouxeHora) addAll(NotasDoRascunho.SOBRE_A_HORA)
            if (finalTemData && finalTemHora) addAll(NotasDoRascunho.SOBRE_O_INSTANTE)
        }
        val doLocal = locais.filterNot { nota -> desmentidas.any { nota.startsWith(it) } }
        // As notas **do remoto** não passam pelo desmentido por prefixo do local: quem decide se
        // elas são verdade é o desfecho do merge, não quem as escreveu.
        //
        // A de recorrência: a fronteira da IA rebaixa a regra a `NONE` quando falta o campo que ela
        // descreve e escreve a nota junto (`SupabaseFunctions.notaDaRecorrenciaIncompleta`). O
        // `toDraft` está certo: a regra que ele produz não repete. O que a desfaz é o `mergeRemote`,
        // uma camada acima — ele só aceita a recorrência do remoto quando ela `isRecurring`, então a
        // regra rebaixada é descartada e a do **local** volta. Mantida, a nota falava de uma perda
        // que não houve:
        //
        //     fala "todo dia 5 do mês"
        //       local: MONTHLY dia=5 (falta a hora, escala)
        //       IA:    MONTHLY sem dia → fronteira rebaixa a NONE + nota
        //       final: MONTHLY dia=5, 'Todo dia 5 do mês', isComplete=true, qc=true
        //              notes=[...mas não disse o dia. Ficou sem repetir.]
        //
        // As de data/hora são a **mesma classe**, e é por isso que elas descem para cá também. A
        // fronteira descarta o `local_date` que não é data e escreve "Ficou sem essa parte"; o merge
        // restaura a data que o **local** tinha (`mergedDate = remoteDraft.localDate ?:
        // localDraft.localDate`) e a parte não sumiu:
        //
        //     fala "reunião 25/10"
        //       local: data=2026-10-25, hora=null → escala (falta a hora)
        //       IA:    {"local_date":"2026-02-30","local_time":"10:00"} → data descartada + nota
        //       final: data=2026-10-25, hora=10:00, canQuickConfirm=true
        //              notes=[...devolveu uma data que não deu para entender. Ficou sem essa parte.]
        //
        // A caixa "Pode salvar?" mostrava a data certa com o aviso de perda logo abaixo, em
        // vermelho, no caminho do salvamento com um toque. A doutrina do `HybridParser` — a
        // contradição visível que o app inteiro evita — vale na direção inversa: não afirmar uma
        // perda que não houve.
        //
        // São **dois juízes**, e cada nota responde ao seu. O da recorrência pergunta "o desfecho
        // repete?"; quando repete, a perda não houve e as duas notas que a afirmam caem juntas — a
        // de recorrência incompleta e a de faixa (o campo inválido descartado, com o local
        // restaurando a regra, não custou nada: "Ficou sem essa parte" é falso, a parte está lá).
        // Quando o local também não reconheceu nada, o final é `NONE` e as notas ficam — e aí são
        // verdadeiras, e sem elas a perda seria silenciosa.
        //
        // O da data/hora pergunta "o campo **existe** no rascunho?" — o mesmo juiz, com o sinal
        // trocado porque a pergunta é outra: a recorrência pergunta "repete?", a data pergunta
        // "existe?". Ele não depende da recorrência, e por isso é avaliado fora do `if`: uma nota de
        // data ao lado de uma regra que repete continua sendo uma nota falsa se a data está lá.
        //
        // As listas não se atropelam: `IA_SOBRE_A_DATA`/`IA_SOBRE_A_HORA` falam do `local_date`/
        // `local_time`; `NOTA_FAIXA_DESCARTADA` fala do campo da **recorrência** (`day_of_month`,
        // `month_of_year`). Prefixos distintos, assuntos distintos — a de faixa não entra em
        // `IA_SOBRE_A_DATA`.
        val notasQueOFinalDesmente = buildSet {
            if (finalTemData) addAll(NotasDoRascunho.IA_SOBRE_A_DATA)
            if (finalTemHora) addAll(NotasDoRascunho.IA_SOBRE_A_HORA)
        }
        val doRemoto = remotas.filterNot { nota ->
            (recorrenciaFinalRepete &&
                (nota.startsWith(NOTA_RECORRENCIA_PERDIDA) || nota.startsWith(NOTA_FAIXA_DESCARTADA))) ||
                notasQueOFinalDesmente.any { nota.startsWith(it) }
        }
        // As notas do local que sobreviveram são a evidência de que o remoto não endereçou aquele
        // assunto — e é sobre essa evidência que o `mergeRemote` decide se a ambiguidade local
        // sobrevive. Devolver as duas listas juntas mantém o juiz em um lugar só.
        return NotasDomescladas(
            finais = (doLocal + doRemoto).distinct(),
            locaisQueFicaram = doLocal,
        )
    }

    /**
     * O que o merge manda para a tela, e — à parte — quais notas **do local** sobreviveram.
     *
     * As duas saídas andam juntas de propósito: quem sabe se a nota sobreviveu é o mesmo juiz que
     * decide se a ambiguidade que ela expressa sobreviveu, e separar as contas em dois lugares
     * faria o par divergir sem que nada avisasse. A lista `finais` é o que vai para o rascunho; a
     * `locaisQueFicaram` é a evidência de que o remoto não endereçou o assunto.
     */
    private data class NotasDomescladas(
        val finais: List<String>,
        val locaisQueFicaram: List<String>,
    )

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

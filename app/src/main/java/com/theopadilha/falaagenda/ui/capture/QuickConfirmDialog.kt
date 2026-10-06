package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.domain.insight.Money
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * O que a caixa "Pode salvar?" promete, montado a partir do rascunho: o resumo do aviso e,
 * quando a data do rascunho não é a que vai valer, a linha que explica. Nula quando falta
 * data ou horário — aí não há o que prometer, e a caixa não mostra nada.
 *
 * Mora aqui, com a caixa, e chama a mesma peça da tela de confirmação: a data do rascunho
 * entra como piso e quem decide a primeira ocorrência é a regra (ver `DraftSchedule`). O
 * caminho comum já concordava — o parser resolve a data de uma regra com o mesmo
 * `firstOnOrAfter` —, mas o rascunho contraditório ("sábado, dias úteis") prometia sábado
 * aqui e nascia na segunda.
 */
internal fun quickConfirmPromise(
    draft: ParsedTaskDraft,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
): AgendaFormat.DraftPromise? {
    val date = draft.localDate ?: return null
    val time = draft.localTime ?: return null
    // A caixa não edita: o salvar cria, e o contrato é o da criação (a tela de confirmação
    // é quem sabe se está criando ou editando).
    return AgendaFormat.promiseOfChoice(
        chosenDate = date,
        chosenTime = time,
        recurrence = draft.recurrence,
        today = today,
        now = now,
        zone = zone,
    )
}

@Composable
fun QuickConfirmDialog(
    draft: ParsedTaskDraft,
    saving: Boolean,
    onSave: (ParsedTaskDraft) -> Unit,
    onEdit: (ParsedTaskDraft) -> Unit,
    onCancel: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val date = draft.localDate
    val time = draft.localTime
    val promise = quickConfirmPromise(
        draft = draft,
        today = LocalDate.now(),
        now = Instant.now(),
        zone = ZoneId.systemDefault(),
    )
    AlertDialog(
        // Sair da caixa no meio da gravação deixava a gravação órfã de confirmação: ela
        // toca "Salvar", some da caixa por "Mudar" e a tela seguinte salva o mesmo recado
        // de novo, porque `saveDraft` sempre cria uma série nova — duas tarefas, dois
        // alarmes no mesmo horário. Enquanto a gravação dela está em voo, a saída não
        // existe; o desfecho chega e a caixa fecha ou mostra o erro.
        onDismissRequest = { if (!saving) onCancel() },
        title = {
            Text(
                "Pode salvar?",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Text(draft.title, style = MaterialTheme.typography.titleLarge)
                promise?.let {
                    Text(it.recap, style = MaterialTheme.typography.bodyLarge)
                    it.droppedChoice?.let { linha ->
                        Text(linha, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                draft.amountCents?.let {
                    Text(Money.formatReais(it), style = MaterialTheme.typography.bodyLarge)
                }
                if (draft.observation.isNotBlank()) {
                    Text(draft.observation, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Para mudar o texto, a data, o horário ou o valor, toque em Mudar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryButton("Mudar", enabled = !saving) { onEdit(draft) }
            }
        },
        // O primário é o confirmar, não o cancelar: esta caixa existe para ela conferir e
        // salvar o que falou, e "Cancelar" no lugar de destaque a fazia cancelar por reflexo
        // — o recado que ela acabou de falar ia embora num toque distraído. O "Cancelar"
        // desceu para o slot de saída do M3, e o "Salvar" ocupa o de confirmação.
        //
        // Sem `testTag` de propósito: a tag viajava no mesmo argumento que define o slot, então
        // um teste que só a comparasse com o texto passaria com os dois blocos trocados de volta.
        // O que prende a hierarquia é a **posição** — o slot de confirmação do M3 desenha acima
        // do de saída (ver `QuickConfirmDialogTest`), e é o que o teste de lá mede.
        confirmButton = {
            PrimaryButton(
                text = if (saving) "Salvando…" else "Salvar",
                enabled = !saving && date != null && time != null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSave(draft)
                },
            )
        },
        dismissButton = {
            SecondaryButton(
                text = "Cancelar",
                enabled = !saving,
            ) { onCancel() }
        },
    )
}

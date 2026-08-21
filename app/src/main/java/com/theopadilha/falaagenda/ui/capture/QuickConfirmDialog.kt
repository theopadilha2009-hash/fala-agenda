package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    AlertDialog(
        onDismissRequest = onCancel,
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
                if (date != null && time != null) {
                    Text(AgendaFormat.recap(date, time, draft.recurrence), style = MaterialTheme.typography.bodyLarge)
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
                PrimaryButton(
                    text = if (saving) "Salvando…" else "Salvar",
                    enabled = !saving && date != null && time != null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave(draft)
                    },
                )
                SecondaryButton("Mudar") { onEdit(draft) }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.height(56.dp)) {
                Text("Cancelar")
            }
        },
    )
}

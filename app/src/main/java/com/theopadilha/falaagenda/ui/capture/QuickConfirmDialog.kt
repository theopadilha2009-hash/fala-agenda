package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
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
    var amountText by remember {
        mutableStateOf(draft.amountCents?.let { Money.formatReais(it) }?.replace("R$", "")?.trim().orEmpty())
    }
    var amountError by remember { mutableStateOf<String?>(null) }
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(draft.title, style = MaterialTheme.typography.titleMedium)
                if (date != null && time != null) {
                    Text(AgendaFormat.recap(date, time, draft.recurrence))
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        amountError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Valor (opcional)") },
                    placeholder = { Text("Ex.: 80 ou 80,50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } },
                )
                PrimaryButton(
                    text = if (saving) "Salvando…" else "Salvar",
                    enabled = !saving && date != null && time != null,
                    onClick = {
                        val cents = if (amountText.isBlank()) {
                            draft.amountCents
                        } else {
                            Money.parseReais(amountText)
                        }
                        if (amountText.isNotBlank() && cents == null) {
                            amountError = "O valor precisa ser um número, por exemplo 80 ou 80,50."
                            return@PrimaryButton
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSave(draft.copy(amountCents = cents))
                    },
                )
                SecondaryButton("Mudar") { onEdit(draft) }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                Text("Cancelar")
            }
        },
    )
}

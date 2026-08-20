package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDraftScreen(
    initial: ParsedTaskDraft,
    onCancel: () -> Unit,
    onSave: (ParsedTaskDraft) -> Unit,
    saving: Boolean = false,
) {
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(initial.localDate) }
    var time by remember { mutableStateOf(initial.localTime) }
    var kind by remember { mutableStateOf(initial.recurrence.kind) }
    var expanded by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val missing = remember(title, date, time) {
        buildList {
            if (title.isBlank()) add("o que precisa ser feito")
            if (date == null) add("a data")
            if (time == null) add("o horário")
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Confira antes de salvar",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Nada é gravado até você confirmar. Se faltar data ou horário, toque para escolher — não inventamos.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (initial.transcript.isNotBlank()) {
                Text("Você disse: “${initial.transcript}”", style = MaterialTheme.typography.bodyMedium)
            }
            if (initial.ambiguous) {
                Text(
                    "Alguma parte ficou em dúvida. Confira os campos antes de salvar.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            initial.notes.forEach { Text(it, color = MaterialTheme.colorScheme.error) }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("O que precisa ser feito") },
                isError = title.isBlank(),
            )

            PickerRow(
                label = "Data",
                value = date?.let { AgendaFormat.longDate(it) } ?: "Toque para escolher a data",
                missing = date == null,
                description = if (date == null) "Escolher data" else "Data ${AgendaFormat.longDate(date!!)}. Toque para mudar.",
                onClick = { showDate = true },
            )
            PickerRow(
                label = "Horário",
                value = time?.let { AgendaFormat.time(it) } ?: "Toque para escolher o horário",
                missing = time == null,
                description = if (time == null) "Escolher horário" else "Horário ${AgendaFormat.time(time!!)}. Toque para mudar.",
                onClick = { showTime = true },
            )

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = kindLabel(kind),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Repetir") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RecurrenceKind.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(kindLabel(option)) },
                            onClick = {
                                kind = option
                                expanded = false
                            },
                        )
                    }
                }
            }
            if (kind == RecurrenceKind.WEEKLY && initial.recurrence.weekDays.isNotEmpty()) {
                Text(
                    initial.recurrence.describePtBr(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (missing.isNotEmpty()) {
                Text("Falta preencher: ${missing.joinToString(", ")}.", color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Salvando…", style = MaterialTheme.typography.bodyMedium)
            }

            PrimaryButton(
                text = if (saving) "Salvando…" else "Salvar",
                enabled = missing.isEmpty() && !saving,
                onClick = {
                    val chosenDate = date
                    val chosenTime = time
                    if (chosenDate == null || chosenTime == null || title.isBlank()) {
                        error = "Complete os campos em vermelho."
                        return@PrimaryButton
                    }
                    val recurrence = when (kind) {
                        RecurrenceKind.WEEKLY -> initial.recurrence.copy(kind = kind).let {
                            if (it.weekDays.isEmpty()) RecurrenceRule(kind, weekDays = setOf(chosenDate.dayOfWeek)) else it
                        }
                        RecurrenceKind.MONTHLY -> RecurrenceRule(kind, dayOfMonth = chosenDate.dayOfMonth)
                        RecurrenceKind.YEARLY -> RecurrenceRule(
                            kind,
                            dayOfMonth = chosenDate.dayOfMonth,
                            monthOfYear = chosenDate.monthValue,
                        )
                        else -> RecurrenceRule(kind)
                    }
                    onSave(
                        initial.withManual(
                            title = title,
                            localDate = chosenDate,
                            localTime = chosenTime,
                            recurrence = recurrence,
                        ),
                    )
                },
            )
            SecondaryButton("Cancelar", onClick = onCancel)
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Os campos ausentes não foram preenchidos automaticamente.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { date = it.toLocalDateUtc() }
                        showDate = false
                    },
                    modifier = Modifier.height(48.dp),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }, modifier = Modifier.height(48.dp)) {
                    Text("Cancelar")
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = time?.hour ?: 9,
            initialMinute = time?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        time = LocalTime.of(state.hour, state.minute)
                        showTime = false
                    },
                    modifier = Modifier.height(48.dp),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }, modifier = Modifier.height(48.dp)) {
                    Text("Cancelar")
                }
            },
            title = { Text("Horário") },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    missing: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    QuietCard(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun kindLabel(kind: RecurrenceKind): String = when (kind) {
    RecurrenceKind.NONE -> "Só uma vez"
    RecurrenceKind.DAILY -> "Todos os dias"
    RecurrenceKind.WEEKDAYS -> "Dias úteis"
    RecurrenceKind.WEEKLY -> "Semanal"
    RecurrenceKind.MONTHLY -> "Todo mês"
    RecurrenceKind.YEARLY -> "Todo ano"
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

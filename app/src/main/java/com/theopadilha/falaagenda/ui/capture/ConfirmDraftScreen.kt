package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.toPtBrShort
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var weekDays by remember {
        mutableStateOf(
            initial.recurrence.weekDays.ifEmpty {
                initial.localDate?.let { setOf(it.dayOfWeek) } ?: emptySet()
            },
        )
    }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    val missing = remember(title, date, time) {
        buildList {
            if (title.isBlank()) add("o que precisa ser feito")
            if (date == null) add("a data")
            if (time == null) add("o horário")
        }
    }
    val previewRule = remember(kind, date, weekDays) {
        recurrenceFor(kind, date ?: LocalDate.now(), weekDays)
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
            val today = LocalDate.now()
            ChipRow(
                options = listOf(
                    "Hoje" to today,
                    "Amanhã" to today.plusDays(1),
                    "Depois" to today.plusDays(2),
                ),
                selected = date,
                onPick = { date = it },
            )
            PickerRow(
                label = "Horário",
                value = time?.let { AgendaFormat.time(it) } ?: "Toque para escolher o horário",
                missing = time == null,
                description = if (time == null) "Escolher horário" else "Horário ${AgendaFormat.time(time!!)}. Toque para mudar.",
                onClick = { showTime = true },
            )
            ChipRow(
                options = listOf(
                    "8h" to LocalTime.of(8, 0),
                    "12h" to LocalTime.NOON,
                    "18h" to LocalTime.of(18, 0),
                    "20h" to LocalTime.of(20, 0),
                ),
                selected = time,
                onPick = { time = it },
            )

            Text("Repetir", style = MaterialTheme.typography.titleMedium)
            ChipRow(
                options = listOf(
                    "Só uma vez" to RecurrenceKind.NONE,
                    "Todo dia" to RecurrenceKind.DAILY,
                    "Dias úteis" to RecurrenceKind.WEEKDAYS,
                    "Toda semana" to RecurrenceKind.WEEKLY,
                    "Todo mês" to RecurrenceKind.MONTHLY,
                    "Todo ano" to RecurrenceKind.YEARLY,
                ),
                selected = kind,
                onPick = { chosen ->
                    kind = chosen
                    if (chosen == RecurrenceKind.WEEKLY && weekDays.isEmpty()) {
                        weekDays = setOf((date ?: today).dayOfWeek)
                    }
                },
            )
            if (kind == RecurrenceKind.WEEKLY) {
                Text("Quais dias?", style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in weekDays
                        FilterChip(
                            selected = selected,
                            onClick = {
                                weekDays = if (selected) {
                                    if (weekDays.size <= 1) weekDays else weekDays - day
                                } else {
                                    weekDays + day
                                }
                            },
                            label = { Text(day.toPtBrShort()) },
                        )
                    }
                }
            }
            val recapDate = date
            val recapTime = time
            if (recapDate != null && recapTime != null) {
                QuietCard {
                    Text(
                        AgendaFormat.recap(recapDate, recapTime, previewRule),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Text(
                    previewRule.describePtBr(),
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSave(
                        initial.withManual(
                            title = title,
                            localDate = chosenDate,
                            localTime = chosenTime,
                            recurrence = recurrenceFor(kind, chosenDate, weekDays),
                        ),
                    )
                },
            )
            SecondaryButton("Cancelar", onClick = onCancel)
            Text(
                "Os campos ausentes não foram preenchidos automaticamente.",
                style = MaterialTheme.typography.bodyMedium,
            )
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

internal fun recurrenceFor(
    kind: RecurrenceKind,
    date: LocalDate,
    weekDays: Set<DayOfWeek>,
): RecurrenceRule = when (kind) {
    RecurrenceKind.WEEKLY -> RecurrenceRule(
        kind,
        weekDays = weekDays.ifEmpty { setOf(date.dayOfWeek) },
    )
    RecurrenceKind.MONTHLY -> RecurrenceRule(kind, dayOfMonth = date.dayOfMonth)
    RecurrenceKind.YEARLY -> RecurrenceRule(
        kind,
        dayOfMonth = date.dayOfMonth,
        monthOfYear = date.monthValue,
    )
    else -> RecurrenceRule(kind)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<Pair<String, T>>,
    selected: T?,
    onPick: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = selected == value,
                onClick = { onPick(value) },
                label = { Text(label) },
            )
        }
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

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

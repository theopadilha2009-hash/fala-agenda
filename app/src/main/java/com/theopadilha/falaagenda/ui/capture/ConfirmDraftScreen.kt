package com.theopadilha.falaagenda.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import com.theopadilha.falaagenda.ui.theme.OffWhite
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDraftScreen(
    initial: ParsedTaskDraft,
    onCancel: () -> Unit,
    onSave: (ParsedTaskDraft) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var dateText by remember { mutableStateOf(initial.localDate?.format(DATE) ?: "") }
    var timeText by remember { mutableStateOf(initial.localTime?.format(TIME) ?: "") }
    var kind by remember { mutableStateOf(initial.recurrence.kind) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val missing = remember(title, dateText, timeText) {
        buildList {
            if (title.isBlank()) add("o que precisa ser feito")
            if (parseDate(dateText) == null) add("a data")
            if (parseTime(timeText) == null) add("o horário")
        }
    }

    Scaffold(containerColor = OffWhite) { padding ->
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
                "Nada é gravado até você confirmar. Se faltar data ou horário, complete aqui — não inventamos.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (initial.transcript.isNotBlank()) {
                Text("Você disse: “${initial.transcript}”", style = MaterialTheme.typography.bodyMedium)
            }
            initial.notes.forEach { Text(it, color = MaterialTheme.colorScheme.error) }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("O que precisa ser feito") },
                isError = title.isBlank(),
            )
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Data (dd/MM/aaaa)") },
                placeholder = { Text("20/08/2026") },
                isError = parseDate(dateText) == null,
            )
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Horário (HH:mm)") },
                placeholder = { Text("09:30") },
                isError = parseTime(timeText) == null,
            )

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = kindLabel(kind),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Repetir") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor()
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

            if (missing.isNotEmpty()) {
                Text("Falta preencher: ${missing.joinToString(", ")}.", color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            PrimaryButton(
                text = "Salvar",
                enabled = missing.isEmpty(),
                onClick = {
                    val date = parseDate(dateText)
                    val time = parseTime(timeText)
                    if (date == null || time == null || title.isBlank()) {
                        error = "Complete os campos em vermelho."
                        return@PrimaryButton
                    }
                    val recurrence = when (kind) {
                        RecurrenceKind.WEEKLY -> initial.recurrence.copy(kind = kind).let {
                            if (it.weekDays.isEmpty()) RecurrenceRule(kind, weekDays = setOf(date.dayOfWeek)) else it
                        }
                        RecurrenceKind.MONTHLY -> RecurrenceRule(kind, dayOfMonth = date.dayOfMonth)
                        RecurrenceKind.YEARLY -> RecurrenceRule(kind, dayOfMonth = date.dayOfMonth, monthOfYear = date.monthValue)
                        else -> RecurrenceRule(kind)
                    }
                    onSave(
                        initial.withManual(
                            title = title,
                            localDate = date,
                            localTime = time,
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
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale("pt", "BR"))
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DATE)
} catch (_: DateTimeParseException) {
    runCatching { LocalDate.parse(text.trim()) }.getOrNull()
}

private fun parseTime(text: String): LocalTime? = try {
    LocalTime.parse(text.trim(), TIME)
} catch (_: DateTimeParseException) {
    runCatching { LocalTime.parse(text.trim()) }.getOrNull()
}

private fun kindLabel(kind: RecurrenceKind): String = when (kind) {
    RecurrenceKind.NONE -> "Só uma vez"
    RecurrenceKind.DAILY -> "Todos os dias"
    RecurrenceKind.WEEKDAYS -> "Dias úteis"
    RecurrenceKind.WEEKLY -> "Semanal"
    RecurrenceKind.MONTHLY -> "Todo mês"
    RecurrenceKind.YEARLY -> "Todo ano"
}

package com.theopadilha.falaagenda.ui.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.domain.insight.Money
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
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
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfirmDraftScreen(
    initial: ParsedTaskDraft,
    onCancel: () -> Unit,
    onSave: (ParsedTaskDraft) -> Unit,
    saving: Boolean = false,
    editing: Boolean = false,
    occurrenceStatus: OccurrenceStatus? = null,
    isRecurring: Boolean = false,
    onComplete: (() -> Unit)? = null,
    onSnooze: ((Long) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
    onEndSeries: (() -> Unit)? = null,
    saveError: String? = null,
    /**
     * O título da **série** que o [onEndSeries] encerra. Não é o do campo editável: ela pode
     * ter mudado o texto na tela sem salvar, e a confirmação precisa nomear o que de fato
     * perde os avisos — não o que ela está vendo escrito. Nulo na criação, onde não há série.
     */
    seriesTitle: String? = null,
) {
    // Tudo o que a pessoa mexeu aqui tem que atravessar a recriação da tela: girar o
    // aparelho no meio da conferência não pode devolver o recado do parser.
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var date by rememberSaveable(stateSaver = NullableLocalDateSaver) { mutableStateOf(initial.localDate) }
    var time by rememberSaveable(stateSaver = NullableLocalTimeSaver) { mutableStateOf(initial.localTime) }
    var kind by rememberSaveable { mutableStateOf(initial.recurrence.kind) }
    var weekDays by rememberSaveable(stateSaver = WeekDaysSaver) {
        mutableStateOf(
            initial.recurrence.weekDays.ifEmpty {
                initial.localDate?.let { setOf(it.dayOfWeek) } ?: emptySet()
            },
        )
    }
    var amountText by rememberSaveable {
        mutableStateOf(initial.amountCents?.let { formatAmountInput(it) }.orEmpty())
    }
    var observation by rememberSaveable { mutableStateOf(initial.observation) }
    var showDate by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        runCatching { titleFocus.requestFocus() }
    }
    var showTime by remember { mutableStateOf(false) }
    // "Encerrar" cancela todos os avisos futuros da série e não tem volta — não há desfazer
    // no repositório. O toque abre a confirmação; só o "Sim" de lá chama o `onEndSeries`.
    var confirmingEndSeries by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
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
    // A gravação é desta tela e termina no escopo do ViewModel: sair no meio deixava o
    // desfecho sem quem o anunciasse — a tela que o pediu já não existe e ela nunca fica
    // sabendo se salvou (nem vê o erro, que é o que importa quando não salvou). Os botões
    // já saem da mão dela neste estado; o gesto de voltar do sistema precisa da mesma
    // guarda.
    BackHandler(enabled = saving) { }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                if (editing) "Editar tarefa" else "Confira antes de salvar",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (editing) {
                    "Toque em qualquer campo para mudar. Pode escrever o recado do seu jeito."
                } else {
                    "Nada é gravado até você confirmar. Se faltar data ou horário, toque para escolher — não inventamos."
                },
                style = MaterialTheme.typography.bodyLarge,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .focusRequester(titleFocus),
                label = { Text("O que precisa ser feito") },
                placeholder = { Text("Escreva aqui, do seu jeito") },
                minLines = 2,
                maxLines = 5,
                isError = title.isBlank(),
            )
            OutlinedTextField(
                value = observation,
                onValueChange = { observation = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp),
                label = { Text("Observação (opcional)") },
                placeholder = { Text("Ex.: levar a carteirinha") },
                minLines = 2,
                maxLines = 5,
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                label = { Text("Valor (opcional)") },
                placeholder = { Text("Ex.: 80 ou 80,50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("Se for uma coisa paga, o valor entra no resumo do mês.") },
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
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(day.toPtBrShort(), style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
            }
            val recapDate = date
            val recapTime = time
            val recapAmount = amountText.trim().takeIf { it.isNotEmpty() }?.let { Money.parseReais(it) }
            // A promessa sai das contas que o salvar vai usar, não da data do seletor: numa
            // série a criar, quem decide a primeira ocorrência é a regra (ver
            // `DraftSchedule`), e a escolha que já passou sem repetição não vira alarme
            // nenhum. Com a data do seletor, o resumo prometia um dia que o agendamento
            // descarta — a home mostrava outro dia ao lado da promessa.
            val promise = if (recapDate != null && recapTime != null) {
                AgendaFormat.promiseOfChoice(
                    chosenDate = recapDate,
                    chosenTime = recapTime,
                    recurrence = previewRule,
                    today = today,
                    now = Instant.now(),
                    zone = ZoneId.systemDefault(),
                    editing = editing,
                )
            } else {
                null
            }
            if (promise != null) {
                QuietCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            promise.recap,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        recapAmount?.let {
                            Text(Money.formatReais(it), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // A data que ela tocou não é a que vai valer: dizer isso é o que a deixa
                // entender o porquê e corrigir, em vez de o chip "Hoje" sumir sem explicação.
                promise.droppedChoice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // Falha vinda de fora (gravação no banco) usa o mesmo lugar do erro de digitação:
            // a tela não sai daqui sem que a pessoa veja que não salvou.
            (saveError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Salvando…", style = MaterialTheme.typography.bodyMedium)
            }

            PrimaryButton(
                text = when {
                    saving -> "Salvando…"
                    // O botão promete o mesmo que o resumo: o dia do primeiro aviso (ou o
                    // "sem aviso", quando não há alarme para armar).
                    promise != null -> promise.saveLabel
                    else -> "Salvar"
                },
                enabled = missing.isEmpty() && !saving,
                onClick = {
                    val chosenDate = date
                    val chosenTime = time
                    if (chosenDate == null || chosenTime == null || title.isBlank()) {
                        error = "Complete os campos em vermelho."
                        return@PrimaryButton
                    }
                    val cents = if (amountText.isBlank()) {
                        null
                    } else {
                        Money.parseReais(amountText)
                    }
                    if (amountText.isNotBlank() && cents == null) {
                        error = "O valor precisa ser um número, por exemplo 80 ou 80,50."
                        return@PrimaryButton
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSave(
                        initial.withManual(
                            title = title,
                            localDate = chosenDate,
                            localTime = chosenTime,
                            recurrence = recurrenceFor(kind, chosenDate, weekDays),
                            amountCents = cents,
                            observation = observation,
                        ),
                    )
                },
            )
            SecondaryButton("Cancelar", enabled = !saving, onClick = onCancel)
            if (editing) {
                // Com uma gravação desta tela em voo, nenhuma outra ação sai daqui: duas
                // escritas concorrentes sobre a mesma ocorrência (salvar e excluir, adiar
                // duas vezes) se atropelam no banco, e a tela sai no mesmo toque de
                // qualquer uma delas — sem tempo de ver a anterior ter falhado.
                Text("Esta tarefa", style = MaterialTheme.typography.titleMedium)
                if (
                    (occurrenceStatus == OccurrenceStatus.PENDING || occurrenceStatus == OccurrenceStatus.MISSED) &&
                    onComplete != null
                ) {
                    PrimaryButton("Concluir", enabled = !saving) { onComplete() }
                }
                if (occurrenceStatus == OccurrenceStatus.PENDING && onSnooze != null) {
                    Text("Adiar", style = MaterialTheme.typography.bodyLarge)
                    ChipRow(
                        options = listOf("10 min" to 10L, "30 min" to 30L, "1 hora" to 60L),
                        selected = null,
                        enabled = !saving,
                        onPick = onSnooze,
                    )
                }
                if (occurrenceStatus == OccurrenceStatus.MISSED && !isRecurring && onRetry != null) {
                    PrimaryButton("Fazer hoje", enabled = !saving) { onRetry() }
                }
                if (occurrenceStatus == OccurrenceStatus.COMPLETED && !isRecurring && onRepeat != null) {
                    PrimaryButton("Amanhã de novo", enabled = !saving) { onRepeat() }
                }
                if (onDelete != null) {
                    SecondaryButton("Excluir", enabled = !saving) { onDelete() }
                }
                if (isRecurring && onEndSeries != null) {
                    SecondaryButton("Encerrar série", enabled = !saving) { confirmingEndSeries = true }
                }
            }
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
                    // Altura mínima, não altura fixa: com a fonte grande do sistema o
                    // "OK" era cortado ao meio — e este é o único caminho para escolher
                    // a data quando o parser não entendeu a fala.
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }, modifier = Modifier.heightIn(min = 56.dp)) {
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
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Cancelar")
                }
            },
            title = { Text("Horário") },
            text = { TimePicker(state = state) },
        )
    }

    // Encerrar a série é a única ação desta tela que não tem desfazer: o repositório cancela
    // os avisos pendentes e marca a série como encerrada, sem restaurar. Para o remédio de
    // todo dia isso é a coisa mais perigosa que ela pode tocar aqui, então o toque pergunta
    // antes — com a consequência escrita e sem o "Sim" no lugar de destaque.
    //
    // O nome é o da série (`seriesTitle`), não o do campo editável: a série que perde os avisos
    // é a que o root encerra por `item.series.id`, mesmo que ela tenha mudado o texto aqui sem
    // salvar. Sem o título da série — criação — o texto fica sem nome em vez de citar o errado.
    if (confirmingEndSeries && onEndSeries != null) {
        val nomeDaSerie = seriesTitle?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { confirmingEndSeries = false },
            title = { Text("Encerrar esta série?") },
            text = {
                Text(
                    if (nomeDaSerie != null) {
                        "Isso cancela todos os avisos futuros de “$nomeDaSerie”. Os dias que já passaram ficam como estão, e não dá para desfazer."
                    } else {
                        "Isso cancela todos os avisos futuros desta tarefa. Os dias que já passaram ficam como estão, e não dá para desfazer."
                    },
                )
            },
            confirmButton = {
                SecondaryButton("Sim, encerrar", enabled = !saving) {
                    confirmingEndSeries = false
                    onEndSeries()
                }
            },
            dismissButton = {
                PrimaryButton("Manter os avisos", enabled = !saving) { confirmingEndSeries = false }
            },
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
    enabled: Boolean = true,
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
                enabled = enabled,
                onClick = { onPick(value) },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
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
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
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

private const val NO_DATE = Long.MIN_VALUE
private const val NO_TIME = -1

/** `LocalDate`/`LocalTime`/`Set<DayOfWeek>` não cabem no Bundle: vão como epoch, segundo do dia e nomes. */
private val NullableLocalDateSaver = Saver<LocalDate?, Long>(
    save = { it?.toEpochDay() ?: NO_DATE },
    restore = { epochDay -> if (epochDay == NO_DATE) null else LocalDate.ofEpochDay(epochDay) },
)

private val NullableLocalTimeSaver = Saver<LocalTime?, Int>(
    save = { it?.toSecondOfDay() ?: NO_TIME },
    restore = { secondOfDay -> if (secondOfDay == NO_TIME) null else LocalTime.ofSecondOfDay(secondOfDay.toLong()) },
)

private val WeekDaysSaver = Saver<Set<DayOfWeek>, ArrayList<String>>(
    save = { days -> ArrayList(days.sortedBy { it.value }.map { it.name }) },
    restore = { names ->
        names.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }.toSet()
    },
)

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private fun formatAmountInput(cents: Long): String {
    val reais = cents / 100
    val rest = (cents % 100).toInt()
    return if (rest == 0) reais.toString() else "%d,%02d".format(reais, rest)
}

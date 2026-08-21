package com.theopadilha.falaagenda.ui.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.capture.QuickConfirmDialog
import com.theopadilha.falaagenda.ui.components.QuietCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    voice: VoiceCaptureController,
    startSpeak: Boolean = false,
    onStartSpeakConsumed: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onDraftReady: (ParsedTaskDraft) -> Unit,
    onEditItem: (AgendaItem) -> Unit,
    statusMessage: String? = null,
    onStatusConsumed: () -> Unit = {},
    openOccurrenceId: String? = null,
    onOpenOccurrenceConsumed: () -> Unit = {},
) {
    val agenda by viewModel.agenda.collectAsState()
    val voiceUi by voice.ui.collectAsState()
    val inexact by viewModel.inexactWarning.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val completeWithUndo: (AgendaItem) -> Unit = { item ->
        viewModel.complete(item) {
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = "Feito.",
                    actionLabel = "Desfazer",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoComplete()
                }
            }
        }
    }
    var writing by remember { mutableStateOf(false) }
    var writeText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AgendaItem?>(null) }
    var quickDraft by remember { mutableStateOf<ParsedTaskDraft?>(null) }
    var quickMinutes by remember { mutableStateOf<Long?>(null) }
    var quickTitle by remember { mutableStateOf("") }
    val handleDraft: (ParsedTaskDraft) -> Unit = { draft ->
        if (draft.canQuickConfirm(Instant.now(), ZoneId.systemDefault())) {
            quickDraft = draft
        } else {
            onDraftReady(draft)
        }
    }

    DisposableEffect(voice) {
        onDispose { voice.cancel() }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) voice.start() else {
            scope.launch { snackbar.showSnackbar("Preciso do microfone só enquanto você fala.") }
        }
    }

    val onMic: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (voiceUi.state == VoiceState.LISTENING || voiceUi.state == VoiceState.UNDERSTANDING) {
            voice.cancel()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(startSpeak) {
        if (!startSpeak) return@LaunchedEffect
        onStartSpeakConsumed()
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(voiceUi.finalText) {
        val text = voiceUi.finalText?.trim().orEmpty()
        if (text.isEmpty()) return@LaunchedEffect
        voice.consumeFinal()
        val draft = viewModel.parse(text)
        handleDraft(draft)
    }

    LaunchedEffect(inexact) {
        if (inexact) {
            snackbar.showSnackbar("O aviso pode atrasar. Abra os ajustes de alarme se quiser o horário exato.")
        }
    }

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onStatusConsumed()
    }

    LaunchedEffect(openOccurrenceId, agenda) {
        val id = openOccurrenceId ?: return@LaunchedEffect
        val item = agenda.find(id)
        if (item != null) {
            selected = item
            onOpenOccurrenceConsumed()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            MicDock(
                state = voiceUi.state,
                partial = voiceUi.partial,
                error = voiceUi.error,
                onMic = onMic,
                onWrite = { writing = true },
                onQuick = { minutes ->
                    quickMinutes = minutes
                    quickTitle = ""
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val next = (agenda.today + agenda.upcoming).minByOrNull { it.occurrence.scheduledAt }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Fala Agenda",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        AgendaFormat.headline(
                            nowTime = LocalTime.now(),
                            today = LocalDate.now(),
                            nextTitle = next?.series?.title,
                            nextDate = next?.occurrence?.localDate,
                            nextTime = next?.series?.localTime,
                            missedCount = agenda.missed.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Configurações")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (agenda.today.isEmpty() && agenda.upcoming.isEmpty()) {
                    item {
                        ExamplePhrases { phrase ->
                            scope.launch { handleDraft(viewModel.parse(phrase)) }
                        }
                    }
                }
                if (inexact) {
                    item {
                        QuietCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("O Android não deixou o alarme exato. A tarefa foi salva.")
                                TextButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            },
                                        )
                                    }
                                    viewModel.setInexactWarning(false)
                                }) { Text("Abrir ajustes de alarme") }
                            }
                        }
                    }
                }
                section(
                    "Hoje",
                    agenda.today,
                    empty = "Nada para hoje. Toque no microfone embaixo e fale o recado.",
                    showWhenEmpty = true,
                    onClick = { selected = it },
                    onComplete = completeWithUndo,
                    onSnoozeQuick = { item ->
                        viewModel.snooze(item.occurrence.id, 10) { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    },
                )
                section(
                    "Próximas",
                    agenda.upcoming,
                    empty = "Nenhuma próxima tarefa.",
                    showWhenEmpty = false,
                    onClick = { selected = it },
                    onComplete = completeWithUndo,
                    onSnoozeQuick = { item ->
                        viewModel.snooze(item.occurrence.id, 10) { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    },
                )
                section(
                    "Concluídas",
                    agenda.completed,
                    empty = "Nenhuma concluída ainda.",
                    showWhenEmpty = false,
                    onRepeat = { item ->
                        viewModel.repeatTomorrow(item) { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    },
                    onClick = { selected = it },
                )
                section(
                    "Não realizadas",
                    agenda.missed,
                    empty = "Nada ficou para trás.",
                    showWhenEmpty = false,
                    emphasize = true,
                    onRetry = { item ->
                        viewModel.retryMissed(item.occurrence.id) { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    },
                    onClick = { selected = it },
                )
            }
        }
    }

    if (writing) {
        val submitWrite: () -> Unit = {
            val text = writeText.trim()
            if (text.isBlank()) {
                scope.launch {
                    snackbar.showSnackbar("Escreva o recado, por exemplo: tomar o remédio amanhã às 9h")
                }
            } else {
                writing = false
                writeText = ""
                scope.launch { handleDraft(viewModel.parse(text)) }
            }
        }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("Escrever tarefa") },
            text = {
                OutlinedTextField(
                    value = writeText,
                    onValueChange = { writeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex.: tomar remédio amanhã às 9h") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitWrite() }),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = submitWrite,
                    modifier = Modifier.height(48.dp),
                ) { Text("Continuar") }
            },
            dismissButton = {
                TextButton(onClick = { writing = false }, modifier = Modifier.height(48.dp)) {
                    Text("Cancelar")
                }
            },
        )
    }

    quickMinutes?.let { minutes ->
        val label = if (minutes == 60L) "1 hora" else "$minutes min"
        val submitQuick: () -> Unit = {
            val title = quickTitle.trim()
            if (title.isBlank()) {
                scope.launch { snackbar.showSnackbar("Escreva o que precisa lembrar.") }
            } else {
                val mins = minutes
                quickMinutes = null
                quickTitle = ""
                viewModel.quickRemind(title, mins) { message ->
                    scope.launch { snackbar.showSnackbar(message) }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { quickMinutes = null },
            title = { Text("Daqui $label") },
            text = {
                OutlinedTextField(
                    value = quickTitle,
                    onValueChange = { quickTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("O que precisa ser feito") },
                    placeholder = { Text("Ex.: tomar água") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitQuick() }),
                )
            },
            confirmButton = {
                TextButton(onClick = submitQuick, modifier = Modifier.height(48.dp)) {
                    Text("Salvar")
                }
            },
            dismissButton = {
                TextButton(onClick = { quickMinutes = null }, modifier = Modifier.height(48.dp)) {
                    Text("Cancelar")
                }
            },
        )
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.series.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${AgendaFormat.longDate(item.occurrence.localDate)} às ${AgendaFormat.time(item.series.localTime)}")
                    Text(item.series.recurrence.describePtBr())
                    if (item.occurrence.status == OccurrenceStatus.PENDING ||
                        item.occurrence.status == OccurrenceStatus.MISSED
                    ) {
                        TextButton(
                            onClick = {
                                completeWithUndo(item)
                                selected = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) { Text("Concluir") }
                    }
                    if (item.occurrence.status == OccurrenceStatus.PENDING) {
                        Text("Adiar", style = MaterialTheme.typography.bodyMedium)
                        SnoozeChips { minutes ->
                            viewModel.snooze(item.occurrence.id, minutes) { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                            }
                            selected = null
                        }
                    }
                    if (item.occurrence.status == OccurrenceStatus.MISSED &&
                        !item.series.recurrence.isRecurring
                    ) {
                        TextButton(
                            onClick = {
                                val id = item.occurrence.id
                                selected = null
                                viewModel.retryMissed(id) { message ->
                                    scope.launch { snackbar.showSnackbar(message) }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) { Text("Fazer hoje") }
                    }
                    if (item.occurrence.status == OccurrenceStatus.COMPLETED &&
                        !item.series.recurrence.isRecurring
                    ) {
                        TextButton(
                            onClick = {
                                val current = item
                                selected = null
                                viewModel.repeatTomorrow(current) { message ->
                                    scope.launch { snackbar.showSnackbar(message) }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) { Text("Amanhã de novo") }
                    }
                    TextButton(
                        onClick = {
                            val current = item
                            selected = null
                            onEditItem(current)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) { Text("Editar") }
                    TextButton(
                        onClick = {
                            val current = item
                            selected = null
                            viewModel.delete(current) {
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = "Tarefa excluída.",
                                        actionLabel = "Desfazer",
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) { Text("Excluir") }
                    if (item.series.recurrence.isRecurring) {
                        TextButton(
                            onClick = {
                                viewModel.endSeries(item.series.id)
                                selected = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) { Text("Encerrar série") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { selected = null },
                    modifier = Modifier.height(48.dp),
                ) { Text("Fechar") }
            },
        )
    }

    quickDraft?.let { draft ->
        QuickConfirmDialog(
            draft = draft,
            saving = busy,
            onSave = { confirmed ->
                viewModel.saveDraft(confirmed, onDone = { usedInexact ->
                    quickDraft = null
                    val date = confirmed.localDate
                    val time = confirmed.localTime
                    val message = if (date != null && time != null) {
                        AgendaFormat.announce(date, time, LocalDate.now())
                    } else {
                        "Tarefa salva."
                    }
                    scope.launch { snackbar.showSnackbar(message) }
                    viewModel.setInexactWarning(usedInexact)
                })
            },
            onEdit = { current ->
                quickDraft = null
                onDraftReady(current)
            },
            onCancel = { quickDraft = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MicDock(
    state: VoiceState,
    partial: String,
    error: String?,
    onMic: () -> Unit,
    onWrite: () -> Unit,
    onQuick: (Long) -> Unit,
) {
    val label = when (state) {
        VoiceState.LISTENING -> "Ouvindo… pode falar"
        VoiceState.UNDERSTANDING -> "Entendendo…"
        VoiceState.ERROR -> error ?: "Não consegui ouvir"
        VoiceState.IDLE -> "Toque no microfone e fale"
    }
    val action = when (state) {
        VoiceState.LISTENING, VoiceState.UNDERSTANDING -> "Parar de ouvir"
        VoiceState.ERROR -> "Tentar de novo. $label"
        VoiceState.IDLE -> "Falar uma tarefa"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (state == VoiceState.ERROR) {
            Text("Toque de novo, ou escreva o recado.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (partial.isNotBlank() && (state == VoiceState.LISTENING || state == VoiceState.UNDERSTANDING)) {
            Text(partial, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        IconButton(
            onClick = onMic,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .semantics { contentDescription = action },
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
        if (state == VoiceState.IDLE) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(5L to "5 min", 15L to "15 min", 60L to "1 hora").forEach { (minutes, chip) ->
                    FilterChip(
                        selected = false,
                        onClick = { onQuick(minutes) },
                        label = { Text(chip) },
                    )
                }
            }
            TextButton(onClick = onWrite, modifier = Modifier.height(48.dp)) {
                Text("Escrever tarefa")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ExamplePhrases(onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Pode falar assim:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            VOICE_EXAMPLES.forEach { phrase ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(phrase) },
                    label = { Text(phrase) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeChips(onPick: (Long) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        listOf(10L to "10 min", 30L to "30 min", 60L to "1 hora").forEach { (minutes, label) ->
            FilterChip(
                selected = false,
                onClick = { onPick(minutes) },
                label = { Text(label) },
            )
        }
    }
}

private val VOICE_EXAMPLES = listOf(
    "tomar remédio daqui 10 minutos",
    "ligar para o médico na sexta às 14h",
    "pagar a conta amanhã às 8h",
)

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<AgendaItem>,
    empty: String,
    showWhenEmpty: Boolean = true,
    emphasize: Boolean = false,
    onComplete: ((AgendaItem) -> Unit)? = null,
    onRetry: ((AgendaItem) -> Unit)? = null,
    onSnoozeQuick: ((AgendaItem) -> Unit)? = null,
    onRepeat: ((AgendaItem) -> Unit)? = null,
    onClick: (AgendaItem) -> Unit,
) {
    if (items.isEmpty() && !showWhenEmpty) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { heading() },
        )
    }
    if (items.isEmpty()) {
        item {
            Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        items(items, key = { it.occurrence.id }) { item ->
            val today = LocalDate.now()
            val date = AgendaFormat.dateLabel(item.occurrence.localDate, today)
            val time = AgendaFormat.time(item.series.localTime)
            val relative = AgendaFormat.fromNow(item.occurrence.scheduledAt, Instant.now())
            val detail = buildString {
                append(date)
                append(" · ")
                append(time)
                if (relative != null && item.occurrence.status == OccurrenceStatus.PENDING) {
                    append(" · ")
                    append(relative)
                } else {
                    append(" · ")
                    append(item.series.recurrence.describePtBr())
                }
            }
            QuietCard(
                onClick = { onClick(item) },
                modifier = Modifier.semantics {
                    contentDescription = "${item.series.title}, $detail. Toque para ver."
                },
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.series.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (item.occurrence.status == OccurrenceStatus.MISSED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(detail)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (onComplete != null && item.occurrence.status == OccurrenceStatus.PENDING) {
                            TextButton(
                                onClick = { onComplete(item) },
                                modifier = Modifier.height(48.dp),
                            ) { Text("Concluir") }
                        }
                        if (onSnoozeQuick != null && item.occurrence.status == OccurrenceStatus.PENDING) {
                            TextButton(
                                onClick = { onSnoozeQuick(item) },
                                modifier = Modifier.height(48.dp),
                            ) { Text("10 min") }
                        }
                        if (onRetry != null &&
                            item.occurrence.status == OccurrenceStatus.MISSED &&
                            !item.series.recurrence.isRecurring
                        ) {
                            TextButton(
                                onClick = { onRetry(item) },
                                modifier = Modifier.height(48.dp),
                            ) { Text("Fazer hoje") }
                        }
                        if (onRepeat != null &&
                            item.occurrence.status == OccurrenceStatus.COMPLETED &&
                            !item.series.recurrence.isRecurring
                        ) {
                            TextButton(
                                onClick = { onRepeat(item) },
                                modifier = Modifier.height(48.dp),
                            ) { Text("Amanhã") }
                        }
                    }
                }
            }
        }
    }
}

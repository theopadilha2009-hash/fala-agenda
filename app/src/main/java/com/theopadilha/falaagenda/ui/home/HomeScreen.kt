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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    voice: VoiceCaptureController,
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var writing by remember { mutableStateOf(false) }
    var writeText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AgendaItem?>(null) }

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

    LaunchedEffect(voiceUi.finalText) {
        val text = voiceUi.finalText?.trim().orEmpty()
        if (text.isEmpty()) return@LaunchedEffect
        voice.consumeFinal()
        val draft = viewModel.parse(text)
        onDraftReady(draft)
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
                Text(
                    "Fala Agenda",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
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
                item {
                    VoicePanel(voiceUi.state, voiceUi.partial, voiceUi.error) {
                        if (voiceUi.state == VoiceState.LISTENING || voiceUi.state == VoiceState.UNDERSTANDING) {
                            voice.cancel()
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                item {
                    SecondaryButton("Escrever tarefa") { writing = true }
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
                    empty = "Nada para hoje. Toque no microfone para falar um recado.",
                    onClick = { selected = it },
                    onComplete = { viewModel.complete(it.occurrence.id) },
                )
                section(
                    "Próximas",
                    agenda.upcoming,
                    empty = "Nenhuma próxima tarefa.",
                    onClick = { selected = it },
                    onComplete = { viewModel.complete(it.occurrence.id) },
                )
                section("Concluídas", agenda.completed, empty = "Nenhuma concluída ainda.") { selected = it }
                section("Não realizadas", agenda.missed, empty = "Nada ficou para trás.") { selected = it }
            }
        }
    }

    if (writing) {
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("Escrever tarefa") },
            text = {
                OutlinedTextField(
                    value = writeText,
                    onValueChange = { writeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex.: tomar remédio amanhã às 9h") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = writeText.trim()
                        if (text.isBlank()) {
                            scope.launch {
                                snackbar.showSnackbar("Escreva o recado, por exemplo: tomar o remédio amanhã às 9h")
                            }
                            return@TextButton
                        }
                        writing = false
                        writeText = ""
                        scope.launch { onDraftReady(viewModel.parse(text)) }
                    },
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

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.series.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${AgendaFormat.longDate(item.occurrence.localDate)} às ${AgendaFormat.time(item.series.localTime)}")
                    Text(item.series.recurrence.describePtBr())
                    TextButton(
                        onClick = {
                            viewModel.complete(item.occurrence.id)
                            selected = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) { Text("Concluir") }
                    TextButton(
                        onClick = {
                            viewModel.snooze(item.occurrence.id)
                            selected = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) { Text("Adiar 30 min") }
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
}

@Composable
private fun VoicePanel(state: VoiceState, partial: String, error: String?, onMic: () -> Unit) {
    val label = when (state) {
        VoiceState.LISTENING -> "Ouvindo…"
        VoiceState.UNDERSTANDING -> "Entendendo…"
        VoiceState.ERROR -> error ?: "Não consegui ouvir"
        VoiceState.IDLE -> "Toque para falar uma tarefa"
    }
    val action = when (state) {
        VoiceState.LISTENING, VoiceState.UNDERSTANDING -> "Parar de ouvir"
        VoiceState.ERROR -> "Tentar de novo. $label"
        VoiceState.IDLE -> "Falar uma tarefa"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onMic,
            modifier = Modifier
                .size(96.dp)
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
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (state == VoiceState.ERROR) {
            Text("Toque de novo para tentar, ou escreva a tarefa.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (partial.isNotBlank() && (state == VoiceState.LISTENING || state == VoiceState.UNDERSTANDING)) {
            Text(partial, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<AgendaItem>,
    empty: String,
    onComplete: ((AgendaItem) -> Unit)? = null,
    onClick: (AgendaItem) -> Unit,
) {
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
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
            QuietCard(
                onClick = { onClick(item) },
                modifier = Modifier.semantics {
                    contentDescription = "${item.series.title}, $date às $time, ${item.series.recurrence.describePtBr()}. Toque para ver. Concluir fica ao lado."
                },
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.series.title, style = MaterialTheme.typography.titleMedium)
                        Text("$date · $time · ${item.series.recurrence.describePtBr()}")
                    }
                    if (onComplete != null && item.occurrence.status == OccurrenceStatus.PENDING) {
                        TextButton(
                            onClick = { onComplete(item) },
                            modifier = Modifier.height(48.dp),
                        ) { Text("Concluir") }
                    }
                }
            }
        }
    }
}

package com.theopadilha.falaagenda.ui.home

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.domain.insight.Money
import com.theopadilha.falaagenda.domain.insight.MonthInsights
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.platform.DeviceIntents
import com.theopadilha.falaagenda.reminders.NotificationHelper
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.DraftSaver
import com.theopadilha.falaagenda.ui.capture.QuickConfirmDialog
import com.theopadilha.falaagenda.ui.components.PulsingMic
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.month.insightRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    voice: VoiceCaptureController,
    startSpeak: Boolean = false,
    onStartSpeakConsumed: () -> Unit = {},
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMonth: () -> Unit,
    onOpenUpdate: () -> Unit,
    onWrite: () -> Unit,
    onQuick: (Long) -> Unit,
    onDraftReady: (ParsedTaskDraft) -> Unit,
    onEditItem: (AgendaItem) -> Unit,
    statusMessage: String? = null,
    onStatusConsumed: () -> Unit = {},
    openOccurrenceId: String? = null,
    onOpenOccurrenceConsumed: () -> Unit = {},
) {
    val agendaUi by viewModel.agendaUi.collectAsState()
    val agenda = agendaUi.sections
    val voiceUi by voice.ui.collectAsState()
    val speech by viewModel.speech.state.collectAsState()
    val inexact by viewModel.inexactWarning.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val availableUpdate by viewModel.availableUpdate.collectAsState()
    val writeError by viewModel.writeError.collectAsState()
    val saveOutcome by viewModel.draftSaveOutcome.collectAsState()
    val undoableDelete by viewModel.undoableDelete.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var widgetHelp by remember { mutableStateOf(false) }
    // O erro e o rascunho da caixa somem juntos: os dois atravessam o giro (ver o
    // `quickDraft` abaixo).
    var quickSaveError by rememberSaveable { mutableStateOf<String?>(null) }
    // A caixa pediu a gravação. Guardado aqui para esta tela só agir sobre o desfecho da
    // gravação dela — e para o desfecho de uma gravação já consumida não mexer na caixa
    // de um recado novo.
    var quickSavePending by rememberSaveable { mutableStateOf(false) }
    // Binder síncrono: se ficasse na recomposição, rodaria a cada parcial da fala.
    var batteryOk by remember { mutableStateOf(DeviceIntents.isBatteryUnrestricted(context)) }
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    var micRefused by rememberSaveable { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(reminderAlerts(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Voltou dos Ajustes: o cartão some sozinho quando o que faltava foi ligado.
        micGranted = hasMicPermission(context)
        alerts = reminderAlerts(context)
        batteryOk = DeviceIntents.isBatteryUnrestricted(context)
    }

    // Negado nesta sessão: no Android 11+ a rationale continua true depois da primeira
    // recusa, e o pedido seguinte nem abre a caixa. Sem o cartão já na primeira recusa
    // ela ficaria só com um aviso de 4 s e sem caminho para os Ajustes.
    // Quem nunca foi perguntado (micRefused ainda false) não vê cartão nenhum.
    val micBlocked = !micGranted && micRefused

    // A fala já foi ouvida e agora está sendo entendida (com IA o parse leva até 20 s).
    // O estado vem do ViewModel: girar o aparelho no meio não devolve o microfone à mão
    // dela nem joga fora o recado que está sendo entendido.
    val understanding = speech.understanding
    // O menu fecha e o que ela tocou acontece no mesmo toque — sem esperar a animação.
    // Enquanto a ação esperava o `close()` (que é suspenso), girar o aparelho no meio
    // cancelava a corrotina e o destino nunca abria, calado.
    val closeAnd: (() -> Unit) -> Unit = { action ->
        scope.launch { drawerState.close() }
        action()
    }
    // Tela de sistema não existe em todo aparelho: o DeviceIntents.open concentra o
    // runCatching e diz se abriu. O toque nunca fica sem resposta — sem isso o
    // ActivityNotFoundException solto fechava o app na mão dela.
    val openOrReport: (Intent, String) -> Unit = { intent, failure ->
        if (!DeviceIntents.open(context, intent)) {
            scope.launch { snackbar.showSnackbar(failure) }
        }
    }
    // "Enviar o aplicativo" copia o APK instalado inteiro (segundos) antes de abrir o
    // seletor: girar no meio cancelava a cópia e o seletor nunca abria, calado. O pedido
    // fica guardado até o seletor abrir — a tela recriada ainda o encontra.
    var shareApp by rememberSaveable { mutableStateOf(false) }
    // O "não está mais na agenda" do aviso tocado: guardado até sair na tela.
    var missingNotice by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(shareApp) {
        if (!shareApp) return@LaunchedEffect
        val apk = withContext(Dispatchers.IO) {
            runCatching { DeviceIntents.copyInstalledApk(context) }.getOrNull()
        }
        openOrReport(
            DeviceIntents.shareChooser(context, apk),
            "Não consegui abrir o compartilhamento neste celular.",
        )
        shareApp = false
    }
    val completeWithUndo: (AgendaItem) -> Unit = { item ->
        viewModel.complete(item) {
            scope.launch {
                val result = snackbar.say(
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
    // A caixa "Pode salvar?" guarda o rascunho num estado que a rotação recria: o efeito
    // abaixo já tirou o recado da sessão para abrir a caixa (consumeDraft), então um
    // `remember` aqui apagava a fala reconhecida e parseada no giro, sem erro nenhum.
    // É o mesmo `DraftSaver` que a tela de confirmação usa em FalaAgendaRoot.
    var quickDraft by rememberSaveable(stateSaver = DraftSaver) {
        mutableStateOf<ParsedTaskDraft?>(null)
    }
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
        micGranted = granted
        if (granted) {
            micRefused = false
            voice.start(context)
        } else {
            micRefused = true
            scope.launch {
                snackbar.showSnackbar("Preciso do microfone só enquanto você fala. Se não quiser permitir, use Escrever tarefa.")
            }
        }
    }

    val systemSpeech = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            voice.acceptTranscript(text)
        } else {
            voice.cancel()
        }
    }

    val startVoice: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            voice.start(context)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val onMic: () -> Unit = {
        if (!understanding) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (voiceUi.state == VoiceState.PREPARING ||
                voiceUi.state == VoiceState.LISTENING ||
                voiceUi.state == VoiceState.UNDERSTANDING
            ) {
                voice.cancel()
            } else {
                startVoice()
            }
        }
    }

    LaunchedEffect(startSpeak) {
        if (!startSpeak) return@LaunchedEffect
        onStartSpeakConsumed()
        startVoice()
    }

    LaunchedEffect(voiceUi.needSystem) {
        if (!voiceUi.needSystem) return@LaunchedEffect
        voice.consumeSystemRequest()
        runCatching { systemSpeech.launch(voice.systemListenIntent()) }
            .onFailure {
                snackbar.showSnackbar("A fala não está disponível neste aparelho. Use o botão Escrever tarefa.")
                voice.cancel()
            }
    }

    LaunchedEffect(voiceUi.finalText) {
        val text = voiceUi.finalText?.trim().orEmpty()
        if (text.isEmpty()) return@LaunchedEffect
        voice.consumeFinal()
        // O parse vive no ViewModel, fora do escopo da tela: consumir o texto final zera
        // `finalText` e o LaunchedEffect seria cancelado no meio do parse, calado.
        viewModel.speech.understand(text)
    }

    // O recado entendido pode chegar com a home fora da tela (rotação, Ajustes, Mês): fica
    // guardado na sessão até alguém mostrá-lo, em vez de sumir com o rascunho.
    LaunchedEffect(speech.draft) {
        val draft = speech.draft ?: return@LaunchedEffect
        viewModel.speech.consumeDraft()
        handleDraft(draft)
    }

    // O erro da fala mora na sessão até alguém mostrá-lo, e quem o tira de lá é esta tela:
    // o `consumeError` só roda depois que o aviso saiu inteiro. Consumido antes, girar o
    // aparelho com o aviso na tela (que fica segundos suspenso neste ponto) apagava a
    // mensagem para sempre — a fala falhava e ela não ficava sabendo.
    LaunchedEffect(speech.error) {
        val message = speech.error ?: return@LaunchedEffect
        snackbar.say(message)
        viewModel.speech.consumeError()
    }

    LaunchedEffect(inexact) {
        if (inexact) {
            snackbar.showSnackbar("O aviso pode atrasar. Abra os ajustes de alarme se quiser o horário exato.")
        }
    }

    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        if (undoableDelete != null) {
            // Excluir é o único aviso que precisa de volta na mesma frase: o desfazer
            // anda junto do aviso, e o aviso só é dado por consumido quando ele sai da
            // tela. Sem isso, o giro no meio do aviso apagava a frase e deixava o
            // desfazer armado — o próximo aviso qualquer aparecia com um "Desfazer" que
            // ressuscitava uma exclusão já aceita.
            val result = snackbar.say(
                message = message,
                actionLabel = "Desfazer",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else {
                viewModel.forgetUndoDelete()
            }
        } else {
            snackbar.say(message)
        }
        onStatusConsumed()
    }

    // Mesmo desenho dos outros avisos: a falha de gravação fica no ViewModel até a tela
    // mostrá-la inteira. Consumida antes, o giro apagava o único lugar onde ela aparece.
    LaunchedEffect(writeError) {
        val message = writeError ?: return@LaunchedEffect
        snackbar.showSnackbar(message, duration = SnackbarDuration.Long)
        viewModel.consumeWriteError()
    }

    // O desfecho da gravação da caixa "Pode salvar?" mora no ViewModel, já fora do alcance
    // do giro: a tela recriada o encontra esperando, fecha a caixa e anuncia — ou mostra a
    // falha com o rascunho no lugar. O `onDone` de antes escrevia no estado da composição
    // descartada, e o toque seguinte salvava o mesmo recado de novo (tarefa e alarme
    // duplicados).
    LaunchedEffect(saveOutcome) {
        val outcome = saveOutcome ?: return@LaunchedEffect
        if (outcome.origin != DraftSaveOrigin.HOME_QUICK || !quickSavePending) return@LaunchedEffect
        when (outcome) {
            // A caixa fica aberta com o recado: o aviso aparece por cima dela (snackbar
            // atrás de um diálogo o idoso não veria).
            is DraftSaveOutcome.Failed -> {
                quickSaveError = outcome.message
                viewModel.consumeDraftSaveOutcome()
                quickSavePending = false
            }
            is DraftSaveOutcome.Saved -> {
                quickDraft = null
                outcome.usedInexactAlarm?.let(viewModel::setInexactWarning)
                snackbar.say(outcome.message)
                // Consumidos só depois de o aviso sair inteiro, a mesma ordem dos outros
                // recados desta tela: girar no meio não apaga a confirmação — a tela nova
                // ainda encontra o desfecho e o mostra de novo, em vez de fechar a caixa
                // calada.
                viewModel.consumeDraftSaveOutcome()
                quickSavePending = false
            }
        }
    }

    LaunchedEffect(openOccurrenceId, agendaUi) {
        val id = openOccurrenceId ?: return@LaunchedEffect
        val item = agendaUi.sections.find(id)
        if (item != null) {
            onEditItem(item)
            onOpenOccurrenceConsumed()
            return@LaunchedEffect
        }
        // Tocou no aviso e não abriu nada: ou a tarefa foi excluída depois do alarme, ou
        // a agenda ainda não chegou do banco. Só o segundo caso merece espera — e o
        // "carregou" sai do mesmo valor que a busca de cima, então ele não pode ser de
        // uma lista que não passou por aqui.
        if (agendaUi.loaded) {
            // O pedido é dado por consumido aqui, mas o aviso fica guardado até sair
            // inteiro: este efeito depende da agenda, que muda sozinha (o alarme grava, o
            // dia vira) — deixar o pedido de pé faria a mesma frase voltar do começo a
            // cada mudança. Girar com o aviso na tela não o apaga: a tela recriada ainda
            // o encontra esperando.
            onOpenOccurrenceConsumed()
            missingNotice = true
        }
    }

    LaunchedEffect(missingNotice) {
        if (!missingNotice) return@LaunchedEffect
        snackbar.showSnackbar("Esta tarefa não está mais na agenda.")
        missingNotice = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawerSheet(
                themeMode = themeMode,
                batteryOk = batteryOk,
                onMonth = { closeAnd(onOpenMonth) },
                onUpdate = { closeAnd(onOpenUpdate) },
                onShareDay = {
                    closeAnd {
                        val today = LocalDate.now()
                        val text = AgendaFormat.todayShare(
                            agenda.today.map {
                                AgendaFormat.DayShareLine(
                                    title = it.series.title,
                                    time = it.series.localTime,
                                    observation = it.series.observation,
                                    // A pendente que atravessou a meia-noite entra em
                                    // "Hoje": sem a marca, ela era mandada para a família
                                    // como se fosse de hoje.
                                    dayMark = AgendaFormat.shareDayMark(it.occurrence.localDate, today),
                                )
                            },
                        )
                        openOrReport(
                            DeviceIntents.shareText(text, "Enviar o dia"),
                            "Não consegui abrir o compartilhamento neste celular.",
                        )
                    }
                },
                onShare = { closeAnd { shareApp = true } },
                onBattery = {
                    closeAnd {
                        val opened = DeviceIntents.open(context, DeviceIntents.batterySettings(context))
                        scope.launch {
                            snackbar.showSnackbar(
                                if (opened) {
                                    "Se o aviso continuar falhando no Xiaomi/Samsung: Ajustes → Apps → Fala Agenda → bateria sem restrição e autostart."
                                } else {
                                    "Não consegui abrir os ajustes de bateria deste celular."
                                },
                            )
                        }
                    }
                },
                onWidget = { closeAnd { widgetHelp = true } },
                onSettings = { closeAnd(onOpenSettings) },
                onThemeMode = onThemeMode,
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            MicDock(
                state = if (understanding) VoiceState.UNDERSTANDING else voiceUi.state,
                partial = voiceUi.partial,
                error = voiceUi.error,
                // Entendendo o recado o botão sai da mão dela: um toque aqui não pode
                // cancelar a fala que ainda está virando tarefa.
                onMic = if (understanding) null else onMic,
                onWrite = onWrite,
                onQuick = onQuick,
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
                Text(
                    AgendaFormat.headline(
                        nowTime = LocalTime.now(),
                        today = LocalDate.now(),
                        nextTitle = next?.series?.title,
                        nextDate = next?.occurrence?.localDate,
                        nextTime = next?.series?.localTime,
                        missedCount = agenda.missed.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (agenda.today.isEmpty() && agenda.upcoming.isEmpty()) {
                    item {
                        Text(
                            "Pode falar: tomar remédio amanhã às 8h",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (micBlocked) {
                    item {
                        QuietCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("O microfone está bloqueado", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Toque em Abrir Ajustes e permita o microfone para falar o recado. Enquanto isso, use Escrever tarefa.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = {
                                        openOrReport(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                            "Não consegui abrir os ajustes deste celular.",
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 56.dp),
                                ) { Text("Abrir Ajustes", style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
                if (alerts != ReminderAlerts.OK) {
                    item {
                        QuietCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    when (alerts) {
                                        ReminderAlerts.OFF -> "Os avisos estão desligados"
                                        else -> "Os avisos estão sem som"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    when (alerts) {
                                        ReminderAlerts.OFF ->
                                            "Assim o lembrete não aparece na hora marcada. Toque em Abrir ajustes de aviso e ligue os avisos do Fala Agenda."
                                        else ->
                                            "Assim o lembrete pode passar despercebido. Toque em Abrir ajustes de aviso e deixe os avisos com som."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    onClick = {
                                        openOrReport(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                            "Não consegui abrir os ajustes de aviso deste celular.",
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 56.dp),
                                ) { Text("Abrir ajustes de aviso", style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
                availableUpdate?.let { update ->
                    item {
                        QuietCard(onClick = onOpenUpdate) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "Tem versão nova: ${update.remote}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Toque para baixar e instalar neste celular. Não precisa pedir o arquivo de novo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
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
                                        openOrReport(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            },
                                            "Não consegui abrir os ajustes de alarme deste celular.",
                                        )
                                    }
                                    viewModel.setInexactWarning(false)
                                }) { Text("Abrir ajustes de alarme") }
                            }
                        }
                    }
                }
                item {
                    val today = LocalDate.now()
                    val recapMonth = when {
                        today.dayOfMonth <= 3 -> YearMonth.from(today).minusMonths(1)
                        today.dayOfMonth >= today.lengthOfMonth() - 1 -> YearMonth.from(today)
                        else -> null
                    }
                    if (recapMonth != null) {
                        val insight = MonthInsights.of(agenda.insightRows(), recapMonth)
                        if (insight.completed > 0 || insight.missed > 0) {
                            QuietCard(onClick = onOpenMonth) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        "Resumo de ${insight.monthLabel()}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        buildString {
                                            append("${insight.completed} feitas")
                                            if (insight.missed > 0) append(" · ${insight.missed} não realizadas")
                                            if (insight.spentCents > 0) append(" · ${insight.spentLabel()}")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                section(
                    "Hoje",
                    agenda.today,
                    empty = "Nada para hoje. Toque no microfone embaixo e fale o recado.",
                    showWhenEmpty = true,
                    onClick = onEditItem,
                    onComplete = completeWithUndo,
                )
                section(
                    "Próximas",
                    agenda.upcoming,
                    empty = "Nenhuma próxima tarefa.",
                    showWhenEmpty = false,
                    onClick = onEditItem,
                    onComplete = completeWithUndo,
                )
                section(
                    "Concluídas",
                    agenda.completed,
                    empty = "Nenhuma concluída ainda.",
                    showWhenEmpty = false,
                    onClick = onEditItem,
                )
                section(
                    "Não realizadas",
                    agenda.missed,
                    empty = "Nada ficou para trás.",
                    showWhenEmpty = false,
                    emphasize = true,
                    onClick = onEditItem,
                )
            }
        }
    }
    }

    if (widgetHelp) {
        AlertDialog(
            onDismissRequest = { widgetHelp = false },
            title = { Text("Widget da agenda") },
            text = {
                Text("Na tela inicial do celular, segure um espaço vazio, escolha Widgets e acrescente Fala Agenda. Aparece a próxima tarefa e o botão Falar.")
            },
            confirmButton = {
                TextButton(onClick = { widgetHelp = false }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Entendi")
                }
            },
        )
    }

    quickDraft?.let { draft ->
        QuickConfirmDialog(
            draft = draft,
            saving = busy,
            onSave = { confirmed ->
                // Quem consome o desfecho é o efeito lá de cima, que sobrevive ao giro.
                quickSavePending = true
                viewModel.saveDraft(confirmed, DraftSaveOrigin.HOME_QUICK)
            },
            onEdit = { current ->
                quickDraft = null
                onDraftReady(current)
            },
            onCancel = { quickDraft = null },
        )
    }

    quickSaveError?.let { message ->
        AlertDialog(
            onDismissRequest = { quickSaveError = null },
            title = { Text("Não deu para salvar") },
            text = { Text("$message O recado continua aqui: toque em Salvar para tentar de novo.") },
            confirmButton = {
                TextButton(
                    onClick = { quickSaveError = null },
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("Entendi") }
            },
        )
    }
}

/**
 * Aviso novo não fica na fila atrás do antigo: o desfazer vive 10 s na tela e a resposta
 * do toque seguinte chegaria depois desse tempo todo — o mesmo que não chegar.
 */
private suspend fun SnackbarHostState.say(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message = message, actionLabel = actionLabel, duration = duration)
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private enum class ReminderAlerts { OK, OFF, QUIET }

/** Mesma checagem do NotificationHelper; dá para unificar lá quando os dois lados mexerem juntos. */
private fun reminderAlerts(context: Context): ReminderAlerts {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return ReminderAlerts.OFF
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ReminderAlerts.OK
    val manager = context.getSystemService(NotificationManager::class.java) ?: return ReminderAlerts.OK
    val importance = manager.getNotificationChannel(NotificationHelper.CHANNEL_ID)?.importance
        ?: return ReminderAlerts.OK
    return if (importance < NotificationManager.IMPORTANCE_DEFAULT) ReminderAlerts.QUIET else ReminderAlerts.OK
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MicDock(
    state: VoiceState,
    partial: String,
    error: String?,
    onMic: (() -> Unit)?,
    onWrite: () -> Unit,
    onQuick: (Long) -> Unit,
) {
    val label = when (state) {
        VoiceState.PREPARING -> "Espera um instante…"
        VoiceState.LISTENING -> "Pode falar agora"
        VoiceState.UNDERSTANDING -> "Entendendo o recado…"
        VoiceState.ERROR -> error ?: "Não consegui ouvir"
        VoiceState.IDLE -> "Toque no microfone e fale"
    }
    val action = when {
        // Sem clique, o que a leitura de tela anuncia é o que está acontecendo.
        onMic == null -> "Entendendo o recado, espere um instante"
        state == VoiceState.ERROR -> "Tentar de novo. $label"
        state == VoiceState.IDLE -> "Falar uma tarefa"
        else -> "Parar de ouvir"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (state == VoiceState.ERROR) {
            Text("Toque de novo, ou escreva o recado.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (partial.isNotBlank() && (state == VoiceState.LISTENING || state == VoiceState.UNDERSTANDING)) {
            Text(partial, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        PulsingMic(
            state = state,
            contentDescription = action,
            onClick = onMic,
        )
        if (state == VoiceState.IDLE || state == VoiceState.ERROR) {
            // No erro a saída de escrever é obrigatória: se o microfone não vai, é por aqui que ele cria a tarefa.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(5L to "5 min", 15L to "15 min", 60L to "1 hora").forEach { (minutes, chip) ->
                    FilterChip(
                        selected = false,
                        onClick = { onQuick(minutes) },
                        modifier = Modifier.heightIn(min = 56.dp),
                        label = { Text(chip, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            TextButton(onClick = onWrite, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Escrever tarefa", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<AgendaItem>,
    empty: String,
    showWhenEmpty: Boolean = true,
    emphasize: Boolean = false,
    onComplete: ((AgendaItem) -> Unit)? = null,
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
            // A pendente de ontem vive na seção "Hoje": ela diz que está atrasada em vez de
            // um "há N h" que se lê igual ao das tarefas de hoje.
            val late = if (item.occurrence.status == OccurrenceStatus.PENDING) {
                AgendaFormat.lateMark(item.occurrence.localDate, today)
            } else {
                null
            }
            val detail = buildString {
                append(date)
                append(" · ")
                append(time)
                when {
                    late != null -> {
                        append(" · ")
                        append(late)
                    }
                    relative != null && item.occurrence.status == OccurrenceStatus.PENDING -> {
                        append(" · ")
                        append(relative)
                    }
                    else -> {
                        append(" · ")
                        append(item.series.recurrence.describePtBr())
                    }
                }
                item.series.amountCents?.let { cents ->
                    append(" · ")
                    append(Money.formatReais(cents))
                }
            }
            QuietCard(
                onClick = { onClick(item) },
                modifier = Modifier.semantics {
                    contentDescription = "${item.series.title}, $detail. Toque para editar."
                },
            ) {
                Row(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            item.series.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (item.occurrence.status == OccurrenceStatus.MISSED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(detail, style = MaterialTheme.typography.bodyLarge)
                        if (item.series.observation.isNotBlank()) {
                            Text(
                                item.series.observation,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                    if (onComplete != null && item.occurrence.status == OccurrenceStatus.PENDING) {
                        TextButton(
                            onClick = { onComplete(item) },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("Concluir") }
                    }
                }
            }
        }
    }
}

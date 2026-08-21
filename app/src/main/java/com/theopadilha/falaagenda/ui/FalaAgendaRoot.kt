package com.theopadilha.falaagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.capture.ConfirmDraftScreen
import com.theopadilha.falaagenda.ui.capture.WriteTaskScreen
import com.theopadilha.falaagenda.ui.home.HomeScreen
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import com.theopadilha.falaagenda.ui.month.MonthSummaryScreen
import com.theopadilha.falaagenda.ui.onboarding.OnboardingScreen
import com.theopadilha.falaagenda.ui.settings.SettingsScreen
import com.theopadilha.falaagenda.ui.update.UpdateScreen
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.time.LocalDate

@Composable
fun FalaAgendaRoot(
    container: AppContainer,
    openOccurrenceId: String? = null,
    onOpenOccurrenceConsumed: () -> Unit = {},
    startSpeak: Boolean = false,
    onStartSpeakConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    var onboardingReady by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf(false) }
    LaunchedEffect(container) {
        container.settings.onboardingComplete.collect { done ->
            onboardingDone = done
            onboardingReady = true
        }
    }
    var draft by remember { mutableStateOf<ParsedTaskDraft?>(null) }
    var editingItem by remember { mutableStateOf<AgendaItem?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val factory = remember(container) { AppViewModelFactory(container) }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val busy by homeVm.busy.collectAsState()
    val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()

    if (!onboardingReady) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val start = if (onboardingDone) "home" else "onboarding"
    NavHost(
        navController = nav,
        startDestination = start,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        composable("onboarding") {
            OnboardingScreen(
                onFinished = {
                    nav.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                },
                settings = container.settings,
            )
        }
        composable("home") {
            HomeScreen(
                viewModel = homeVm,
                voice = container.voice,
                startSpeak = startSpeak,
                onStartSpeakConsumed = onStartSpeakConsumed,
                onOpenSettings = { nav.navigate("settings") },
                themeMode = themeMode,
                onThemeMode = { mode -> scope.launch { container.settings.setThemeMode(mode) } },
                onOpenMonth = { nav.navigate("month") },
                onOpenUpdate = { nav.navigate("update") },
                onWrite = { nav.navigate("write") },
                onQuick = { minutes -> nav.navigate("quick/$minutes") },
                onDraftReady = {
                    editingItem = null
                    draft = it
                    nav.navigate("confirm") {
                        launchSingleTop = true
                    }
                },
                onEditItem = { item ->
                    editingItem = item
                    draft = ParsedTaskDraft(
                        title = item.series.title,
                        localDate = item.occurrence.localDate,
                        localTime = item.series.localTime,
                        recurrence = item.series.recurrence,
                        confidence = 1.0,
                        missingFields = emptySet(),
                        ambiguous = false,
                        transcript = "",
                        amountCents = item.series.amountCents,
                        observation = item.series.observation,
                    )
                    nav.navigate("confirm") {
                        launchSingleTop = true
                    }
                },
                statusMessage = statusMessage,
                onStatusConsumed = { statusMessage = null },
                openOccurrenceId = openOccurrenceId,
                onOpenOccurrenceConsumed = onOpenOccurrenceConsumed,
            )
        }
        composable("confirm") {
            val current = draft
            LaunchedEffect(current) {
                if (current == null) nav.popBackStack()
            }
            if (current != null) {
                ConfirmDraftScreen(
                    initial = current,
                    saving = busy,
                    editing = editingItem != null,
                    occurrenceStatus = editingItem?.occurrence?.status,
                    isRecurring = editingItem?.series?.recurrence?.isRecurring == true,
                    onComplete = editingItem?.let { item ->
                        {
                            homeVm.complete(item)
                            editingItem = null
                            statusMessage = "Feito."
                            nav.popBackStack()
                        }
                    },
                    onSnooze = editingItem?.let { item ->
                        { minutes ->
                            homeVm.snooze(item.occurrence.id, minutes) { message ->
                                statusMessage = message
                            }
                            editingItem = null
                            nav.popBackStack()
                        }
                    },
                    onDelete = editingItem?.let { item ->
                        {
                            homeVm.delete(item)
                            editingItem = null
                            statusMessage = "Tarefa excluída."
                            nav.popBackStack()
                        }
                    },
                    onRetry = editingItem?.let { item ->
                        {
                            homeVm.retryMissed(item.occurrence.id) { message ->
                                statusMessage = message
                            }
                            editingItem = null
                            nav.popBackStack()
                        }
                    },
                    onRepeat = editingItem?.let { item ->
                        {
                            homeVm.repeatTomorrow(item) { message ->
                                statusMessage = message
                            }
                            editingItem = null
                            nav.popBackStack()
                        }
                    },
                    onEndSeries = editingItem?.let { item ->
                        {
                            homeVm.endSeries(item.series.id)
                            editingItem = null
                            statusMessage = "Série encerrada."
                            nav.popBackStack()
                        }
                    },
                    onCancel = {
                        editingItem = null
                        nav.popBackStack()
                    },
                    onSave = { confirmed ->
                        val editId = editingItem?.occurrence?.id
                        val date = confirmed.localDate
                        val time = confirmed.localTime
                        if (editId != null && date != null && time != null) {
                            homeVm.edit(
                                editId,
                                confirmed.title,
                                date,
                                time,
                                confirmed.recurrence,
                                confirmed.amountCents,
                                confirmed.observation,
                            )
                            editingItem = null
                            statusMessage = AgendaFormat.announce(date, time, LocalDate.now())
                            nav.popBackStack()
                        } else {
                            homeVm.saveDraft(confirmed, onDone = { usedInexact ->
                                val savedDate = confirmed.localDate
                                val savedTime = confirmed.localTime
                                statusMessage = if (savedDate != null && savedTime != null) {
                                    AgendaFormat.announce(savedDate, savedTime, LocalDate.now())
                                } else {
                                    "Tarefa salva."
                                }
                                nav.popBackStack()
                                homeVm.setInexactWarning(usedInexact)
                            })
                        }
                    },
                )
            }
        }
        composable("write") {
            WriteTaskScreen(
                heading = "Escrever tarefa",
                help = "Escreva o recado do seu jeito. Na próxima tela você confere data e horário.",
                placeholder = "Ex.: tomar remédio amanhã às 9h",
                confirmLabel = "Continuar",
                onCancel = { nav.popBackStack() },
                onConfirm = { text ->
                    scope.launch {
                        editingItem = null
                        draft = homeVm.parse(text)
                        nav.navigate("confirm") {
                            popUpTo("write") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable("quick/{minutes}") { entry ->
            val minutes = entry.arguments?.getString("minutes")?.toLongOrNull() ?: 15L
            val label = if (minutes == 60L) "1 hora" else "$minutes min"
            WriteTaskScreen(
                heading = "Daqui $label",
                help = "Escreva o que precisa ser feito. O aviso toca daqui $label.",
                placeholder = "Ex.: tomar água",
                confirmLabel = "Salvar",
                onCancel = { nav.popBackStack() },
                onConfirm = { title ->
                    homeVm.quickRemind(title, minutes) { message ->
                        statusMessage = message
                    }
                    nav.popBackStack()
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
        composable("month") {
            MonthSummaryScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
        composable("update") {
            UpdateScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

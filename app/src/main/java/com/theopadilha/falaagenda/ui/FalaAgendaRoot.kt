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
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.capture.ConfirmDraftScreen
import com.theopadilha.falaagenda.ui.home.HomeScreen
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import com.theopadilha.falaagenda.ui.onboarding.OnboardingScreen
import com.theopadilha.falaagenda.ui.settings.SettingsScreen
import java.time.LocalDate

@Composable
fun FalaAgendaRoot(
    container: AppContainer,
    openOccurrenceId: String? = null,
    onOpenOccurrenceConsumed: () -> Unit = {},
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
    var editingOccurrenceId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val factory = remember(container) { AppViewModelFactory(container) }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val busy by homeVm.busy.collectAsState()

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
                onOpenSettings = { nav.navigate("settings") },
                onDraftReady = {
                    editingOccurrenceId = null
                    draft = it
                    nav.navigate("confirm") {
                        launchSingleTop = true
                    }
                },
                onEditItem = { item ->
                    editingOccurrenceId = item.occurrence.id
                    draft = ParsedTaskDraft(
                        title = item.series.title,
                        localDate = item.occurrence.localDate,
                        localTime = item.series.localTime,
                        recurrence = item.series.recurrence,
                        confidence = 1.0,
                        missingFields = emptySet(),
                        ambiguous = false,
                        transcript = "",
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
                    onCancel = {
                        editingOccurrenceId = null
                        nav.popBackStack()
                    },
                    onSave = { confirmed ->
                        val editId = editingOccurrenceId
                        val date = confirmed.localDate
                        val time = confirmed.localTime
                        if (editId != null && date != null && time != null) {
                            homeVm.edit(editId, confirmed.title, date, time, confirmed.recurrence)
                            editingOccurrenceId = null
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
        composable("settings") {
            SettingsScreen(
                container = container,
                onBack = { nav.popBackStack() },
            )
        }
    }
}

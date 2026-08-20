package com.theopadilha.falaagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@Composable
fun FalaAgendaRoot(container: AppContainer) {
    val nav = rememberNavController()
    val onboardingDone by container.settings.onboardingComplete.collectAsState(initial = false)
    var draft by remember { mutableStateOf<ParsedTaskDraft?>(null) }
    var editingOccurrenceId by remember { mutableStateOf<String?>(null) }
    val factory = remember(container) { AppViewModelFactory(container) }
    val homeVm: HomeViewModel = viewModel(factory = factory)

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
                    nav.navigate("confirm")
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
                    nav.navigate("confirm")
                },
            )
        }
        composable("confirm") {
            val current = draft
            if (current == null) {
                nav.popBackStack()
            } else {
                ConfirmDraftScreen(
                    initial = current,
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
                            nav.popBackStack()
                        } else {
                            homeVm.saveDraft(confirmed, onDone = { usedInexact ->
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

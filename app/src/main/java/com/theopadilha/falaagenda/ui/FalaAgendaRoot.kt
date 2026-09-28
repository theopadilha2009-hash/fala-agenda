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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.QuickRemind
import com.theopadilha.falaagenda.ui.capture.ConfirmDraftScreen
import com.theopadilha.falaagenda.ui.capture.WriteTaskScreen
import com.theopadilha.falaagenda.ui.home.HomeScreen
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import com.theopadilha.falaagenda.ui.month.MonthSummaryScreen
import com.theopadilha.falaagenda.ui.onboarding.OnboardingScreen
import com.theopadilha.falaagenda.ui.settings.SettingsScreen
import com.theopadilha.falaagenda.ui.update.UpdateScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

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
        // Fluxo que estoura (DataStore corrompido, por exemplo) ou que termina sem
        // emitir não pode deixar o app preso no indicador de carregamento.
        container.settings.onboardingComplete
            .catch { }
            .collect { done ->
                onboardingDone = done
                onboardingReady = true
            }
        onboardingReady = true
    }
    // Recado em andamento: girar o aparelho no meio do rascunho não pode jogar fora
    // nem o que foi ditado nem a tarefa que estava sendo editada.
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf<ParsedTaskDraft?>(null) }
    // A tarefa editada é guardada pelo id e reencontrada na agenda: o item inteiro não
    // cabe no Bundle e, relido da agenda, volta sempre com o estado do banco.
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOccurrenceId by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // Erro de gravação mostrado na própria tela de confirmação, que não pode sumir
    // como se tivesse salvado.
    var confirmError by rememberSaveable { mutableStateOf<String?>(null) }
    var writeError by rememberSaveable { mutableStateOf<String?>(null) }
    val factory = remember(container) { AppViewModelFactory(container) }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val busy by homeVm.busy.collectAsState()
    // O alarme pede uma ocorrência por id; a home só avisa que atendeu quando acha o
    // item. Guardamos o pedido aqui e o damos por consumido na hora, senão um id que
    // não existe (tarefa excluída) fica pendurado para sempre no intent.
    LaunchedEffect(openOccurrenceId) {
        val id = openOccurrenceId ?: return@LaunchedEffect
        pendingOccurrenceId = id
        onOpenOccurrenceConsumed()
    }
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
                onThemeMode = { mode ->
                    // O escopo é o da composição: gravação que falha sem tratamento aqui
                    // derrubaria o processo. E o toque não pode passar em silêncio — o
                    // tema continua o antigo e a home diz por quê.
                    scope.launch {
                        try {
                            container.settings.setThemeMode(mode)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            statusMessage = "Não consegui salvar a aparência. Tente de novo."
                        }
                    }
                },
                onOpenMonth = { nav.navigate("month") },
                onOpenUpdate = { nav.navigate("update") },
                onWrite = { nav.navigate("write") },
                onQuick = { minutes -> nav.navigate("quick/$minutes") },
                onDraftReady = {
                    editingItemId = null
                    draft = it
                    nav.navigate("confirm") {
                        launchSingleTop = true
                    }
                },
                onEditItem = { item ->
                    editingItemId = item.occurrence.id
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
                openOccurrenceId = pendingOccurrenceId,
                onOpenOccurrenceConsumed = { pendingOccurrenceId = null },
            )
        }
        composable("confirm") {
            val current = draft
            // Reencontrada na agenda a cada recomposição: sobrevive à recriação da
            // Activity sem guardar o item inteiro no Bundle e sem ficar com cópia velha.
            val agendaUi by homeVm.agendaUi.collectAsState()
            val editingItem = editingItemId?.let { id -> agendaUi.sections.find(id) }
            LaunchedEffect(current) {
                if (current == null) nav.popBackStack() else confirmError = null
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
                            // O recado só aparece depois que a gravação passou: falhou,
                            // o home mostra o erro em vez de um "Feito." que não houve.
                            homeVm.complete(item, onDone = { statusMessage = "Feito." })
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onSnooze = editingItem?.let { item ->
                        { minutes ->
                            homeVm.snooze(item.occurrence.id, minutes) { message ->
                                statusMessage = message
                            }
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onDelete = editingItem?.let { item ->
                        {
                            homeVm.delete(item, onDeleted = { statusMessage = "Tarefa excluída." })
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onRetry = editingItem?.let { item ->
                        {
                            homeVm.retryMissed(item.occurrence.id) { message ->
                                statusMessage = message
                            }
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onRepeat = editingItem?.let { item ->
                        {
                            homeVm.repeatTomorrow(item) { message ->
                                statusMessage = message
                            }
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onEndSeries = editingItem?.let { item ->
                        {
                            homeVm.endSeries(item.series.id, onDone = { statusMessage = "Série encerrada." })
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onCancel = {
                        editingItemId = null
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
                                onDone = {
                                    editingItemId = null
                                    statusMessage = AgendaFormat.announce(date, time, LocalDate.now())
                                    nav.popBackStack()
                                },
                                onError = { message -> confirmError = message },
                            )
                        } else {
                            homeVm.saveDraft(
                                draft = confirmed,
                                onDone = { usedInexact ->
                                    val savedDate = confirmed.localDate
                                    val savedTime = confirmed.localTime
                                    statusMessage = if (savedDate != null && savedTime != null) {
                                        AgendaFormat.announce(savedDate, savedTime, LocalDate.now())
                                    } else {
                                        "Tarefa salva."
                                    }
                                    nav.popBackStack()
                                    homeVm.setInexactWarning(usedInexact)
                                },
                                onError = { message -> confirmError = message },
                            )
                        }
                    },
                    saveError = confirmError,
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
                externalError = writeError,
                onTextChanged = { writeError = null },
                onConfirm = { text ->
                    scope.launch {
                        editingItemId = null
                        // O parser pode falhar em texto esquisito; sem isto a exceção
                        // derruba o processo. Aqui ela vira recado na própria tela, que
                        // fica aberta com o texto que a pessoa escreveu ou ditou.
                        val parsed = runCatching { homeVm.parse(text) }.getOrNull()
                        if (parsed == null) {
                            writeError = "Não consegui entender o recado. Tente de novo."
                            return@launch
                        }
                        writeError = null
                        draft = parsed
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
            var quickError by rememberSaveable { mutableStateOf<String?>(null) }
            WriteTaskScreen(
                heading = "Daqui $label",
                help = "Escreva o que precisa ser feito. O aviso toca daqui $label.",
                placeholder = "Ex.: tomar água",
                confirmLabel = "Salvar",
                onCancel = { nav.popBackStack() },
                externalError = quickError,
                onTextChanged = { quickError = null },
                onConfirm = { title ->
                    // Só sai da tela depois que o banco confirmou, senão uma gravação que
                    // falhou joga a pessoa de volta sem ela saber que o aviso não existe.
                    val quick = QuickRemind.draft(title, minutes, ZonedDateTime.now())
                    homeVm.saveDraft(
                        draft = quick,
                        onDone = { usedInexact ->
                            homeVm.setInexactWarning(usedInexact)
                            // QuickRemind monta data e horário a partir do "daqui N
                            // minutos": os dois vêm sempre, e o título vazio já foi
                            // barrado na tela de escrita.
                            statusMessage = AgendaFormat.announce(
                                quick.localDate!!,
                                quick.localTime!!,
                                LocalDate.now(),
                            )
                            nav.popBackStack()
                        },
                        onError = { message -> quickError = message },
                    )
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

private const val NO_EPOCH_DAY = Long.MIN_VALUE
private const val NO_SECOND_OF_DAY = -1
private const val NO_DAY_OF_MONTH = -1
private const val NO_MONTH_OF_YEAR = -1
private const val NO_AMOUNT = Long.MIN_VALUE

/**
 * O rascunho da confirmação não é `Parcelable` nem `Serializable`, então atravessa a
 * recriação da Activity como a lista de primitivos que o Bundle aceita. Lista vazia
 * quer dizer "nenhum rascunho em andamento".
 *
 * A ordem dos campos em [DraftSaver] é o formato salvo: mexer nela exige mexer em
 * [draftFrom].
 */
private val DraftSaver = Saver<ParsedTaskDraft?, ArrayList<Any?>>(
    save = { draft ->
        if (draft == null) {
            ArrayList()
        } else {
            arrayListOf(
                draft.title,
                draft.localDate?.toEpochDay() ?: NO_EPOCH_DAY,
                draft.localTime?.toSecondOfDay() ?: NO_SECOND_OF_DAY,
                draft.recurrence.kind.name,
                draft.recurrence.weekDays.map { it.name },
                draft.recurrence.dayOfMonth ?: NO_DAY_OF_MONTH,
                draft.recurrence.monthOfYear ?: NO_MONTH_OF_YEAR,
                draft.confidence,
                draft.missingFields.map { it.name },
                draft.ambiguous,
                draft.transcript,
                draft.notes,
                draft.source.name,
                draft.amountCents ?: NO_AMOUNT,
                draft.observation,
            )
        }
    },
    // Bundle salvo por uma versão antiga do app não pode derrubar a abertura.
    restore = { values ->
        values.takeIf { it.isNotEmpty() }?.let { runCatching { draftFrom(it) }.getOrNull() }
    },
)

@Suppress("UNCHECKED_CAST")
private fun draftFrom(values: List<Any?>): ParsedTaskDraft {
    val epochDay = values[1] as Long
    val secondOfDay = values[2] as Int
    val dayOfMonth = values[5] as Int
    val monthOfYear = values[6] as Int
    val amount = values[13] as Long
    return ParsedTaskDraft(
        title = values[0] as String,
        localDate = if (epochDay == NO_EPOCH_DAY) null else LocalDate.ofEpochDay(epochDay),
        localTime = if (secondOfDay == NO_SECOND_OF_DAY) null else LocalTime.ofSecondOfDay(secondOfDay.toLong()),
        recurrence = RecurrenceRule(
            kind = RecurrenceKind.valueOf(values[3] as String),
            weekDays = (values[4] as List<String>).map { DayOfWeek.valueOf(it) }.toSet(),
            dayOfMonth = if (dayOfMonth == NO_DAY_OF_MONTH) null else dayOfMonth,
            monthOfYear = if (monthOfYear == NO_MONTH_OF_YEAR) null else monthOfYear,
        ),
        confidence = values[7] as Double,
        missingFields = (values[8] as List<String>).map { MissingDraftField.valueOf(it) }.toSet(),
        ambiguous = values[9] as Boolean,
        transcript = values[10] as String,
        notes = values[11] as List<String>,
        source = DraftSource.valueOf(values[12] as String),
        amountCents = if (amount == NO_AMOUNT) null else amount,
        observation = values[14] as String,
    )
}

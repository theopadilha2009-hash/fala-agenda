package com.theopadilha.falaagenda.ui

import android.util.Log
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
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.QuickRemind
import com.theopadilha.falaagenda.ui.capture.ConfirmDraftScreen
import com.theopadilha.falaagenda.ui.capture.WriteStep
import com.theopadilha.falaagenda.ui.capture.WriteTaskScreen
import com.theopadilha.falaagenda.ui.capture.writeStepFor
import com.theopadilha.falaagenda.ui.home.DraftSaveOrigin
import com.theopadilha.falaagenda.ui.home.DraftSaveOutcome
import com.theopadilha.falaagenda.ui.home.HomeScreen
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import com.theopadilha.falaagenda.ui.home.NO_SAVE_REQUEST
import com.theopadilha.falaagenda.ui.month.MonthSummaryScreen
import com.theopadilha.falaagenda.ui.onboarding.OnboardingScreen
import com.theopadilha.falaagenda.ui.settings.SettingsScreen
import com.theopadilha.falaagenda.ui.update.UpdateScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

private const val TAG = "FalaAgendaRoot"

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
    // O pedido do alarme atravessa o giro: a home só o dá por consumido depois de mostrar
    // o que ele pede, e um `remember` aqui o apagava no meio do aviso — o toque no alarme
    // ficava sem resposta. O recado da última ação não precisa disto: ele mora no
    // `HomeViewModel` (ver `StatusMessage`), que a rotação não alcança.
    var pendingOccurrenceId by rememberSaveable { mutableStateOf<String?>(null) }
    // Erro de gravação mostrado na própria tela de confirmação, que não pode sumir
    // como se tivesse salvado.
    var confirmError by rememberSaveable { mutableStateOf<String?>(null) }
    var writeError by rememberSaveable { mutableStateOf<String?>(null) }
    val factory = remember(container) { AppViewModelFactory(container) }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val agendaUi by homeVm.agendaUi.collectAsState()
    // O alarme pede uma ocorrência por id; quem a atende é o efeito abaixo, que a procura
    // na agenda. O pedido é dado por consumido na hora: um id que não existe (tarefa
    // excluída) não pode ficar pendurado para sempre no intent.
    LaunchedEffect(openOccurrenceId) {
        val id = openOccurrenceId ?: return@LaunchedEffect
        pendingOccurrenceId = id
        onOpenOccurrenceConsumed()
    }
    val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    // A aparência escolhida no menu da home: a escolha fica guardada (o giro não a apaga)
    // até a gravação terminar. No escopo da composição, girar o aparelho entre o toque e a
    // gravação cancelava o DataStore no meio — ou antes de ele começar — e a aparência
    // voltava à antiga, sem aviso nenhum.
    var pendingTheme by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingTheme) {
        val name = pendingTheme ?: return@LaunchedEffect
        // Nome que não é de um tema conhecido (Bundle de outra versão) não derruba a
        // abertura: vale o que já está gravado.
        val mode = runCatching { ThemeMode.valueOf(name) }.getOrNull()
        if (mode != null) {
            try {
                container.settings.setThemeMode(mode)
            } catch (cancellation: CancellationException) {
                // Girar não é falha: a escolha continua guardada e a tela recriada a grava.
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Não consegui salvar a aparência.", error)
                homeVm.publishStatus("Não consegui salvar a aparência. Tente de novo.")
            }
        }
        pendingTheme = null
    }

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

    // Abrir uma tarefa da agenda é o mesmo caminho para o toque no cartão e para o toque
    // no aviso: a tela de confirmação, com o rascunho dela.
    val openForEdit: (AgendaItem) -> Unit = { item ->
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
    }
    // O pedido do aviso é atendido aqui, e não dentro da home: o `NavHost` só compõe o
    // destino atual, e com ela na confirmação, escrevendo uma tarefa, no "Daqui N min",
    // no resumo do mês ou nos Ajustes o toque no aviso não fazia nada — o pedido ficava
    // esperando e só valia quando ela voltava para a home, fora de contexto.
    LaunchedEffect(pendingOccurrenceId, agendaUi) {
        val id = pendingOccurrenceId ?: return@LaunchedEffect
        val item = agendaUi.sections.find(id)
        if (item != null) {
            pendingOccurrenceId = null
            openForEdit(item)
            return@LaunchedEffect
        }
        // Tocou no aviso e não abriu nada: ou a tarefa foi excluída depois do alarme, ou a
        // agenda ainda não chegou do banco. Só o segundo caso merece espera — e o
        // "carregou" sai do mesmo valor que a busca de cima, então ele não pode ser de uma
        // lista que não passou por aqui. O recado sai pela home, que é onde ele aparece, e
        // fica guardado até ela mostrá-lo.
        if (agendaUi.loaded) {
            pendingOccurrenceId = null
            homeVm.publishStatus("Esta tarefa não está mais na agenda.")
        }
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
                // A gravação é do efeito acima; aqui só se registra a escolha. Falha de
                // gravação não pode passar em silêncio: o tema continua o antigo e a home
                // diz por quê.
                onThemeMode = { mode -> pendingTheme = mode.name },
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
                onEditItem = openForEdit,
            )
        }
        composable("confirm") {
            val current = draft
            // Reencontrada na agenda a cada recomposição (o valor vem do escopo de cima):
            // sobrevive à recriação da Activity sem guardar o item inteiro no Bundle e sem
            // ficar com cópia velha.
            val editingItem = editingItemId?.let { id -> agendaUi.sections.find(id) }
            // A agenda ainda não respondeu: sem ela não dá para saber se este rascunho é a
            // edição de uma tarefa ou uma tarefa nova. Decidir agora era o que transformava
            // "Editar tarefa" em "Confira antes de salvar" e mandava a gravação para o
            // caminho de criar — uma segunda série com o mesmo título e o mesmo horário, e
            // um segundo alarme. A tela espera o banco em vez de decidir.
            val awaitingEditingItem = editingItemId != null && !agendaUi.loaded
            val saveOutcome by homeVm.draftSaveOutcome.collectAsState()
            // Os pedidos de gravação que ainda não foram mostrados a ninguém (ver
            // `pendingDraftSaves`): a gravação *desta* tela, e não o `busy` do ViewModel, que
            // também fica verdadeiro para a escrita de outra tela.
            val pendingSaves by homeVm.pendingDraftSaves.collectAsState()
            // O id do pedido desta tela — dado pelo ViewModel na hora do pedido, e guardado
            // aqui porque a tela recriada pelo giro precisa reconhecer o desfecho como sendo
            // o *dela*: o booleano de antes voltava do Bundle valendo verdadeiro e a tela
            // nova consumia o desfecho de uma gravação anterior. `NO_SAVE_REQUEST` é
            // "nenhum pedido em voo".
            var saveRequest by rememberSaveable { mutableStateOf(NO_SAVE_REQUEST) }
            val saving = saveRequest in pendingSaves
            LaunchedEffect(current) {
                if (current == null) nav.popBackStack() else confirmError = null
            }
            // A escrita pode terminar depois do giro, e aí o desfecho não pode ir para o
            // `onDone` de uma composição descartada (a tela ficava presa, sem navegar e
            // sem aviso). Quem pediu a gravação consome o desfecho e é ele que navega — e só
            // o desfecho do pedido *dela*.
            LaunchedEffect(saveOutcome) {
                val outcome = saveOutcome ?: return@LaunchedEffect
                if (outcome.origin != DraftSaveOrigin.CONFIRM || outcome.requestId != saveRequest) {
                    return@LaunchedEffect
                }
                homeVm.consumeDraftSaveOutcome()
                saveRequest = NO_SAVE_REQUEST
                when (outcome) {
                    is DraftSaveOutcome.Failed -> confirmError = outcome.message
                    is DraftSaveOutcome.Saved -> {
                        editingItemId = null
                        outcome.usedInexactAlarm?.let(homeVm::setInexactWarning)
                        homeVm.publishStatus(outcome.message)
                        nav.popBackStack()
                    }
                }
            }
            if (current != null && awaitingEditingItem) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            if (current != null && !awaitingEditingItem) {
                ConfirmDraftScreen(
                    initial = current,
                    // A gravação *desta* tela — e não o `busy` do ViewModel, que também
                    // fica verdadeiro para a escrita de outra tela: com ele, a tela presa
                    // aqui mostrava "Salvando…" por causa de uma gravação que não era dela.
                    saving = saving,
                    editing = editingItem != null,
                    occurrenceStatus = editingItem?.occurrence?.status,
                    isRecurring = editingItem?.series?.recurrence?.isRecurring == true,
                    onComplete = editingItem?.let { item ->
                        {
                            // O recado só aparece depois que a gravação passou: falhou,
                            // o home mostra o erro em vez de um "Feito." que não houve.
                            homeVm.complete(item)
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onSnooze = editingItem?.let { item ->
                        { minutes ->
                            homeVm.snooze(item.occurrence.id, minutes)
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onDelete = editingItem?.let { item ->
                        {
                            homeVm.delete(item)
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onRetry = editingItem?.let { item ->
                        {
                            homeVm.retryMissed(item.occurrence.id)
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onRepeat = editingItem?.let { item ->
                        {
                            saveRequest = homeVm.repeatTomorrow(item)
                        }
                    },
                    onEndSeries = editingItem?.let { item ->
                        {
                            homeVm.endSeries(item.series.id)
                            editingItemId = null
                            nav.popBackStack()
                        }
                    },
                    onCancel = {
                        editingItemId = null
                        nav.popBackStack()
                    },
                    onSave = { confirmed ->
                        // Um pedido por vez, como o botão desabilitado já garante: dois
                        // toques no mesmo quadro viram duas gravações (e `saveDraft` sempre
                        // cria uma série nova).
                        if (!saving) {
                            val editId = editingItem?.occurrence?.id
                            val date = confirmed.localDate
                            val time = confirmed.localTime
                            // Só registra o pedido, com a identidade que o ViewModel dá a
                            // ele: o desfecho chega pelo ViewModel, que atravessa o giro.
                            saveRequest = if (editId != null && date != null && time != null) {
                                homeVm.edit(
                                    editId,
                                    confirmed.title,
                                    date,
                                    time,
                                    confirmed.recurrence,
                                    confirmed.amountCents,
                                    confirmed.observation,
                                )
                            } else {
                                homeVm.saveDraft(confirmed, DraftSaveOrigin.CONFIRM)
                            }
                        }
                    },
                    saveError = confirmError,
                )
            }
        }
        composable("write") {
            val speech by homeVm.speech.state.collectAsState()
            // O parse vive no ViewModel, fora do escopo da tela — o mesmo desenho da fala
            // na home. O `runCatching` de antes engolia o CancellationException como
            // `null` e o giro do aparelho no meio dos ~20 s deixava o texto no campo sem
            // rascunho, sem erro e sem mudança de tela: ela escreveu, apertou e o app
            // parece ter ignorado.
            val step = writeStepFor(speech)
            // O desfecho fica guardado na sessão até alguém mostrá-lo, então a tela
            // recriada ainda o encontra esperando.
            LaunchedEffect(step) {
                when (step) {
                    is WriteStep.Ready -> {
                        homeVm.speech.consumeDraft()
                        editingItemId = null
                        writeError = null
                        draft = step.draft
                        nav.navigate("confirm") {
                            popUpTo("write") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    is WriteStep.Failed -> {
                        homeVm.speech.consumeError()
                        writeError = step.message
                    }
                    WriteStep.Waiting -> Unit
                }
            }
            WriteTaskScreen(
                heading = "Escrever tarefa",
                help = "Escreva o recado do seu jeito. Na próxima tela você confere data e horário.",
                placeholder = "Ex.: tomar remédio amanhã às 9h",
                confirmLabel = "Continuar",
                understanding = speech.understanding,
                onCancel = { nav.popBackStack() },
                externalError = writeError,
                onTextChanged = { writeError = null },
                onConfirm = { text -> homeVm.speech.understand(text) },
            )
        }
        composable("quick/{minutes}") { entry ->
            val minutes = entry.arguments?.getString("minutes")?.toLongOrNull() ?: 15L
            val label = if (minutes == 60L) "1 hora" else "$minutes min"
            var quickError by rememberSaveable { mutableStateOf<String?>(null) }
            val saveOutcome by homeVm.draftSaveOutcome.collectAsState()
            // Os pedidos de gravação ainda não mostrados a ninguém: a gravação *desta* tela.
            val pendingSaves by homeVm.pendingDraftSaves.collectAsState()
            // O id do pedido desta tela: sem ele, o desfecho de uma gravação antiga (desta
            // ou de outra tela) mexeria na tela nova — e a tela recriada pelo giro precisa
            // reconhecer o pedido dela. `NO_SAVE_REQUEST` é "nenhum pedido em voo"; um
            // pedido que o ViewModel não conhece (o processo morreu no meio) não a prende.
            var saveRequest by rememberSaveable { mutableStateOf(NO_SAVE_REQUEST) }
            val saving = saveRequest in pendingSaves
            // Só sai da tela depois que o banco confirmou, senão uma gravação que falhou
            // joga a pessoa de volta sem ela saber que o aviso não existe. O desfecho sai
            // do ViewModel: girar o aparelho no meio da gravação não deixa a tela presa.
            LaunchedEffect(saveOutcome) {
                val outcome = saveOutcome ?: return@LaunchedEffect
                if (outcome.origin != DraftSaveOrigin.QUICK_REMIND || outcome.requestId != saveRequest) {
                    return@LaunchedEffect
                }
                homeVm.consumeDraftSaveOutcome()
                saveRequest = NO_SAVE_REQUEST
                when (outcome) {
                    is DraftSaveOutcome.Failed -> quickError = outcome.message
                    is DraftSaveOutcome.Saved -> {
                        outcome.usedInexactAlarm?.let(homeVm::setInexactWarning)
                        homeVm.publishStatus(outcome.message)
                        nav.popBackStack()
                    }
                }
            }
            WriteTaskScreen(
                heading = "Daqui $label",
                help = "Escreva o que precisa ser feito. O aviso toca daqui $label.",
                placeholder = "Ex.: tomar água",
                confirmLabel = "Salvar",
                // A gravação leva o tempo do alarme e do banco: sem "Salvando…" o segundo
                // toque era engolido em silêncio — o "toque sem resposta" que esta tela
                // corrige no "Entendendo o recado…".
                saving = saving,
                onCancel = { nav.popBackStack() },
                externalError = quickError,
                onTextChanged = { quickError = null },
                onConfirm = { title ->
                    // A tela de escrita não recebe o `busy` que desabilita o botão nas
                    // outras duas telas, e a gravação leva o tempo do alarme e do banco sem
                    // nenhum "Salvando…": sem esta guarda, dois toques seguidos criavam
                    // duas tarefas e dois alarmes.
                    if (!saving) {
                        // QuickRemind monta data e horário a partir do "daqui N minutos":
                        // os dois vêm sempre, e o título vazio já foi barrado na tela.
                        saveRequest = homeVm.saveDraft(
                            draft = QuickRemind.draft(title, minutes, ZonedDateTime.now()),
                            origin = DraftSaveOrigin.QUICK_REMIND,
                        )
                    }
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
 * Vale para os dois lugares em que um rascunho espera por ela: a tela de confirmação
 * (o `draft` daqui) e a caixa "Pode salvar?" da home, que já tirou o recado da sessão
 * quando o gira — sem o mesmo tratamento ali, o giro apagava a fala reconhecida.
 *
 * A ordem dos campos em [DraftSaver] é o formato salvo: mexer nela exige mexer em
 * [draftFrom].
 */
internal val DraftSaver = Saver<ParsedTaskDraft?, ArrayList<Any?>>(
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

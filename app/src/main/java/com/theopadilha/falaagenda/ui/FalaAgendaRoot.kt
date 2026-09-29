package com.theopadilha.falaagenda.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import com.theopadilha.falaagenda.ui.home.AgendaUi
import com.theopadilha.falaagenda.ui.home.DraftSaveOrigin
import com.theopadilha.falaagenda.ui.home.DraftSaveOutcome
import com.theopadilha.falaagenda.ui.home.HomeScreen
import com.theopadilha.falaagenda.ui.home.HomeViewModel
import com.theopadilha.falaagenda.ui.home.NO_SAVE_REQUEST
import com.theopadilha.falaagenda.ui.home.SpeechSession
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
        when (val pedido = agendaNotice(agendaUi, id)) {
            is AgendaNotice.Open -> {
                pendingOccurrenceId = null
                openForEdit(pedido.item)
            }
            // A leitura respondeu e a tarefa não está nela: saiu da agenda de verdade.
            AgendaNotice.Gone -> {
                pendingOccurrenceId = null
                homeVm.publishStatus("Esta tarefa não está mais na agenda.")
            }
            // Sem leitura (ainda não chegou do banco, ou falhou): nada de concluir por
            // ausência. Anunciar "Esta tarefa não está mais na agenda" quando a agenda não foi
            // lida é a mentira que este efeito não pode contar. E o toque no aviso não pode
            // ficar no silêncio de antes: ela tocou, o alarme do remédio tocou, e nada
            // respondeu — o id ficava pendente pelo resto da vida do processo. Na falha o id
            // continua guardado (a releitura pode trazer a tarefa e a tela abre sozinha) e ela
            // fica sabendo por quê. O recado sai pela home, que é onde ele aparece, e fica
            // guardado até ela mostrá-lo.
            AgendaNotice.Unreadable -> if (agendaUi.failed) {
                homeVm.publishStatus("Não consegui abrir a tarefa agora: não deu para ler a sua agenda.")
                homeVm.retryAgendaRead()
            }
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
                // As duas saídas que a home oferece enquanto ela espera o entendimento
                // ("Escrever tarefa" e os atalhos do "Daqui N min") são a decisão dela de
                // abandonar a espera: o parse daquela fala é descartado aqui, no toque, e
                // não volta para trocar a tela por baixo dela. Sem isto, ela digitava a
                // tarefa (ou salvava um "Daqui 5 min") e o recado falado a levava para a
                // confirmação com o texto digitado já jogado fora.
                onWrite = {
                    homeVm.speech.discard()
                    nav.navigate("write")
                },
                onQuick = { minutes ->
                    homeVm.speech.discard()
                    nav.navigate("quick/$minutes")
                },
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
            // A agenda respondeu, e respondeu que não conseguiu ler: a tarefa editada não
            // está aqui porque a leitura falhou, e não porque ela não existe mais. Seguir
            // para o `ConfirmDraftScreen` com `editing = false` era o que transformava
            // "Editar tarefa" em tarefa nova — segunda série com o mesmo título e o mesmo
            // horário, e um segundo alarme. Aqui a tela diz o que aconteceu e oferece o
            // "Tentar de novo" e a saída; o `popBackStack` dela devolve para o destino de
            // baixo, que é de onde o aviso a trouxe se ela estava no mês ou nos Ajustes.
            // A decisão é de [editingUnavailable], que tem teste próprio: trocar um `&&` por
            // um `||` aqui volta ao segundo alarme sem acender nada.
            val editingBlocked = editingUnavailable(agendaUi, editingItemId, editingItem)
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
            // Sem a agenda não dá para saber o que a tarefa editada tem hoje no banco, e o
            // "Salvar" daqui criaria uma série nova: a tela sai do caminho de edição e diz
            // por quê.
            if (current != null && editingBlocked) {
                AgendaReadFailure(
                    onRetry = homeVm::retryAgendaRead,
                    onBack = {
                        editingItemId = null
                        nav.popBackStack()
                    },
                )
            }
            // A tela é reiniciada quando o rascunho ou a ocorrência mudam. O aviso é atendido
            // aqui, que compõe em qualquer destino: com ela já na confirmação, o
            // `launchSingleTop` do `openForEdit` reaproveita a mesma entrada do `NavHost` e
            // o `ConfirmDraftScreen` guarda os campos em `rememberSaveable` — sem a chave, os
            // campos continuavam os do rascunho anterior ("Comprar pão") enquanto as ações
            // já eram as da ocorrência do aviso ("Tomar remédio"): o "Salvar" renomeava o
            // remédio com o texto dela, e "Concluir"/"Excluir" agiam no remédio enquanto ela
            // lia outra coisa. Com a chave, o que está na tela é o que as ações vão alterar.
            // A entrada continua sendo reaproveitada (não empilha tela a cada abertura).
            if (current != null && !awaitingEditingItem && !editingBlocked) {
                key(current, editingItemId) {
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
            // O voltar do sistema não passa pelo "Cancelar" da tela: o `BackHandler` de lá
            // guarda a gravação, e aqui `saving` é falso. Sem esta guarda ele popava a rota
            // direto pelo `NavHost` e o parse daquela fala continuava em voo — o mesmo
            // estrago do botão, pela outra porta. É a guarda de fora: o `BackHandler` da
            // tela, quando habilitado ("Daqui N min", que grava), registra depois e vence.
            // Sem nada em voo não há o que descartar, e o `enabled` falso devolve o voltar
            // de sempre ao `NavHost`.
            BackHandler(enabled = speech.understanding) {
                cancelWrite(nav, homeVm.speech)
            }
            WriteTaskScreen(
                heading = "Escrever tarefa",
                help = "Escreva o recado do seu jeito. Na próxima tela você confere data e horário.",
                placeholder = "Ex.: tomar remédio amanhã às 9h",
                confirmLabel = "Continuar",
                understanding = speech.understanding,
                onCancel = { cancelWrite(nav, homeVm.speech) },
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

/**
 * A leitura da agenda falhou com a tela de edição aberta — a morte do processo no meio da
 * edição é o caso: o Bundle devolve o id da tarefa, a primeira leitura do banco estoura, e
 * sem a lista não há como saber o que a tarefa tem hoje. É a tela que não oferece o
 * "Salvar": o caminho de criação criaria uma série nova no lugar de editar a antiga.
 *
 * Só o "Voltar" era um beco sem saída mais bonito: a leitura não volta sozinha, e sem o
 * [onRetry] a única saída era matar o app. [onBack] desce um destino no `NavHost` — o mês ou
 * os Ajustes, se foi de lá que o aviso a trouxe.
 */
@Composable
private fun AgendaReadFailure(onRetry: () -> Unit, onBack: () -> Unit) {
    // O toque que não conseguiu ler de novo precisa de resposta aqui também: a releitura que
    // falha sai igual ao que já está na tela (`AgendaUi` é data class, e o `StateFlow`
    // conflaciona valores iguais), e sem isto o toque dela não produziria sinal nenhum — ela
    // toca outra vez, achando que não foi atendida. O recado da home (snackbar) não vale
    // nesta tela, que é outra composição. Se a releitura conseguir, esta tela sai e o texto
    // vai junto.
    var retried by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Não consegui abrir a tarefa",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Não deu para ler a sua agenda agora, então não dá para abrir esta tarefa. Nada foi mudado.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (retried) {
                Text(
                    "Ainda não consegui ler a sua agenda.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // O primário é a tentativa de novo; o "Voltar" é a saída.
            PrimaryButton(
                "Tentar de novo",
                onClick = {
                    retried = true
                    onRetry()
                },
            )
            SecondaryButton("Voltar", onClick = onBack)
        }
    }
}

/**
 * A leitura da agenda falhou com a tela de edição aberta e a tarefa editada não está na
 * lista.
 *
 * É um predicado de três valores, e confundir dois deles é o segundo alarme: sem o item não
 * dá para saber o que a tarefa tem hoje, e seguir para o `ConfirmDraftScreen` com
 * `editing = false` cria uma série nova com o mesmo título e o mesmo horário. A leitura que
 * respondeu "não consegui ler" não é a mesma coisa que a tarefa ter saído da agenda — só a
 * primeira bloqueia a edição. O item presente na última lista boa vale, mesmo com a
 * releitura falhando: a bandeira diz "esta é a última lista que consegui ler", e é ela que a
 * tela de confirmação lê.
 */
internal fun editingUnavailable(
    agendaUi: AgendaUi,
    editingItemId: String?,
    editingItem: AgendaItem?,
): Boolean = editingItemId != null && agendaUi.failed && editingItem == null

/**
 * O que o toque no aviso pede da agenda lida.
 *
 * São três desfechos, e confundir dois deles é o defeito que este tipo separa: a tarefa não
 * estar na lista **lida** é uma coisa, e a lista não ter sido lida é outra — a segunda não
 * autoriza dizer que a tarefa saiu da agenda.
 */
internal sealed interface AgendaNotice {
    /** A tarefa está na lista: é ela que a tela de confirmação abre. */
    data class Open(val item: AgendaItem) : AgendaNotice

    /** A agenda foi lida e a tarefa não está nela: saiu da agenda de verdade. */
    data object Gone : AgendaNotice

    /** A agenda não foi lida (ainda não chegou, ou a leitura falhou): nada se conclui. */
    data object Unreadable : AgendaNotice
}

/**
 * Decide o [AgendaNotice] da ocorrência pedida pelo aviso.
 *
 * O `loaded` e o `failed` saem do mesmo valor que a busca, e não de um fluxo paralelo: sem
 * isso o "não está mais na agenda" podia falar de uma lista que nunca passou por aqui. Na
 * dúvida — leitura que falhou, leitura que ainda não chegou — o pedido continua pendente, e é
 * o `failed` que diz ao chamador que ele já pode contar para ela o que aconteceu.
 */
internal fun agendaNotice(agendaUi: AgendaUi, occurrenceId: String): AgendaNotice {
    agendaUi.sections.find(occurrenceId)?.let { return AgendaNotice.Open(it) }
    return if (agendaUi.loaded && !agendaUi.failed) AgendaNotice.Gone else AgendaNotice.Unreadable
}

/**
 * O gesto de sair da tela de escrever — o "Cancelar" da rota `"write"`.
 *
 * Sair da tela é abandonar o entendimento: sem o descarte, o parse daquela fala voltava
 * depois e publicava o rascunho com ela já na home. Ao abrir "Escrever tarefa" de novo, a
 * rota encontrava `WriteStep.Ready` e ia para a confirmação do recado que ela abandonou,
 * com o texto antigo na tela e sem ela ter digitado nada; a falha do parse abandonado
 * reaparecia do mesmo jeito, como "Não consegui entender o recado" numa tela recém-aberta.
 * A home já faz o mesmo nas duas saídas dela (ver `SpeechSession.discard`).
 */
internal fun cancelWrite(nav: NavController, speech: SpeechSession) {
    speech.discard()
    nav.popBackStack()
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
 *
 * O rascunho que volta é load-bearing: o `key(current, editingItemId)` da confirmação decide
 * reiniciar a tela pelo *hash composto* das chaves — hash em que o rascunho entra pelo
 * `hashCode`, e não por uma comparação `equals` campo a campo. Campo novo em `ParsedTaskDraft`
 * sem o [draftFrom] correspondente muda o rascunho restaurado e, com ele, o hash da chave: a
 * confirmação reinicia e a digitação dela se perde, em silêncio.
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

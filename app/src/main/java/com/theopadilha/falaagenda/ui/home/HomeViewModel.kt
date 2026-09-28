package com.theopadilha.falaagenda.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.platform.UpdateCheck
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * A lista, a resposta para "isto já veio do banco?" e a resposta para "e o que ele
 * respondeu?" no mesmo valor.
 *
 * Eram duas assinaturas independentes de `observeAgenda()`, e a resposta de um fluxo valia
 * para a lista do outro: com o app em outra tela, os valores retidos dos dois `stateIn`
 * diziam "carregou" enquanto a lista ainda era a inicial vazia, e o toque no aviso do
 * remédio respondia "Esta tarefa não está mais na agenda" — descartando o id. Quem busca a
 * ocorrência por id lê os três campos da mesma emissão: [loaded] nunca fala de uma lista
 * que não é [sections], e [failed] diz *o que* a resposta foi (ver [agendaUiFrom]).
 */
data class AgendaUi(
    val sections: AgendaSections,
    /** O fluxo respondeu: chegou uma leitura ou uma falha. Não diz *o que* respondeu. */
    val loaded: Boolean,
    /**
     * O que respondeu foi "não consegui ler a agenda". A falha antes de qualquer emissão
     * publica a lista vazia, e uma agenda lida e vazia é igual a ela em conteúdo: sem esta
     * bandeira, a tela de edição trata a falha como tarefa que não existe mais — "Editar
     * tarefa" vira criação (segunda série com o mesmo título e o mesmo horário, segundo
     * alarme) e o aviso anuncia que a tarefa saiu da agenda quando a agenda não foi lida.
     * A falha que chega **depois** de uma lista boa não emite nada, e aí esta bandeira não
     * vale: o que está na tela é a última lista lida, que é o que a tela de edição lê.
     */
    val failed: Boolean,
)

/** Antes da primeira emissão: nada de concluir por ausência. */
internal val initialAgendaUi = AgendaUi(
    sections = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
    loaded = false,
    failed = false,
)

/**
 * A agenda em estado utilizável, mesmo quando a leitura falha.
 *
 * A tela de confirmação decide por `loaded` se o rascunho é a edição de uma tarefa ou uma
 * tarefa nova (`FalaAgendaRoot.awaitingEditingItem`): um fluxo que estoura sem emitir deixava
 * o `loaded` falso para sempre, e a tela ficava só com o indicador de carregamento — sem
 * botão, sem saída a não ser o Back do sistema. Ler o banco é uma tarefa que pode falhar, e
 * falhar tem que virar estado utilizável, nunca uma espera sem fim.
 *
 * O que respondeu sai em `failed`: uma falha antes de qualquer emissão não pode sair como "li
 * a agenda e ela está vazia" — as duas listas são iguais, e quem trata a falha como agenda
 * vazia lê a ocorrência editada como inexistente ("Editar tarefa" vira tarefa nova, com uma
 * segunda série e um segundo alarme) e anuncia "Esta tarefa não está mais na agenda" para uma
 * agenda que nunca foi lida.
 *
 * A falha que chega **depois** de a agenda já ter vindo não emite nada de propósito: o
 * `stateIn` guarda o último valor bom, e é essa lista que a tela de confirmação lê para saber
 * que a tarefa editada existe. Trocá-la por uma agenda vazia transformaria "Editar tarefa" em
 * "tarefa nova" — segunda série com o mesmo título e o mesmo horário, e um segundo alarme.
 * Por isso `failed` só diz respeito à coleta que ainda não entregou lista nenhuma.
 */
internal fun agendaUiFrom(source: Flow<AgendaSections>): Flow<AgendaUi> = flow {
    // `emitiu` mora na coleta, e não na cadeia: o `stateIn` reinicia a coleta do mesmo fluxo
    // toda vez que a última assinatura sai e outra volta, e uma bandeira de fora da coleta
    // fazia "primeira emissão" querer dizer "primeira desde que o processo abriu" — a segunda
    // leitura caindo não virava falha nenhuma, e a tela ficava com a lista da leitura anterior
    // como se essa leitura tivesse funcionado.
    var emitiu = false
    emitAll(
        source
            .map { sections ->
                emitiu = true
                AgendaUi(sections = sections, loaded = true, failed = false)
            }
            .catch { error ->
                Log.w(TAG, "Não consegui ler a agenda.", error)
                if (!emitiu) emit(initialAgendaUi.copy(loaded = true, failed = true))
            },
    )
}

/**
 * De onde partiu a gravação de um rascunho. O desfecho sai por [HomeViewModel.draftSaveOutcome]
 * e quem o consome é a tela que pediu a gravação — a home não age sobre o desfecho da
 * confirmação, e vice-versa.
 */
enum class DraftSaveOrigin {
    /** A caixa "Pode salvar?" da home. */
    HOME_QUICK,

    /** A tela de confirmação: salvar o recado, salvar a mudança ou repetir amanhã. */
    CONFIRM,

    /** A tela "Daqui N min". */
    QUICK_REMIND,
}

/** Nenhum pedido de gravação em voo nesta tela: o id de um pedido nunca é negativo. */
internal const val NO_SAVE_REQUEST = -1L

/**
 * Como terminou a gravação de um rascunho.
 *
 * O desfecho mora no ViewModel, e não no `onDone` da composição que pediu a gravação,
 * porque a escrita pode terminar depois de o aparelho girar: o `onDone` escrevia num
 * estado já descartado, a caixa "Pode salvar?" continuava cheia e sem confirmação
 * nenhuma, e o toque seguinte salvava o mesmo recado de novo — duas tarefas, dois
 * alarmes. A falha tinha o mesmo destino, calada.
 */
sealed interface DraftSaveOutcome {
    /**
     * Identidade do evento. Dois desfechos iguais em sequência — o mesmo recado salvo
     * dentro do mesmo minuto, que dá a mesma frase — são coisas diferentes, e o
     * `StateFlow` não emite valor igual ao atual: sem esta identidade o segundo desfecho
     * não chegava a ninguém, a tela ficava esperando e salvava de novo a cada toque.
     * Ninguém decide nada por ele.
     */
    val seq: Long

    /**
     * O pedido que produziu este desfecho, com a identidade que [HomeViewModel.saveDraft]
     * e companhia devolveram a quem pediu. A tela compara com o id que ela guardou ao
     * pedir: o desfecho de um pedido nunca é dado como o de outro — nem de outra tela, nem
     * de uma gravação anterior desta mesma tela, que é o que acontecia quando quem casava
     * era só a origem mais um booleano da composição.
     */
    val requestId: Long

    val origin: DraftSaveOrigin

    /**
     * Gravou: [message] é o que a tela anuncia. [usedInexactAlarm] é o aviso de alarme
     * inexato a deixar no estado da home; é nulo quando a ação não tem opinião sobre ele
     * (salvar uma mudança não agenda alarme novo).
     */
    data class Saved(
        override val seq: Long,
        override val requestId: Long,
        override val origin: DraftSaveOrigin,
        val message: String,
        val usedInexactAlarm: Boolean? = null,
    ) : DraftSaveOutcome

    /** Não gravou: [message] é o que a tela mostra, com o rascunho ainda no lugar. */
    data class Failed(
        override val seq: Long,
        override val requestId: Long,
        override val origin: DraftSaveOrigin,
        val message: String,
    ) : DraftSaveOutcome
}

/**
 * O recado da última ação — "Feito.", "Tarefa excluída.", "Vai avisar amanhã às 8h." —,
 * esperando a home mostrá-lo.
 *
 * Mora aqui, e não no `onDone` da composição que pediu a ação, pelo mesmo motivo do
 * [DraftSaveOutcome]: o `write` roda no escopo do ViewModel e atravessa o giro do aparelho,
 * enquanto o `onDone` escrevia num `MutableState` já descartado. A tarefa era concluída,
 * excluída ou adiada e o aviso caía num estado morto — ela fez a coisa e não ficou sabendo
 * se valeu. A falha tinha o mesmo destino, calada.
 */
data class StatusMessage(
    /**
     * Identidade do evento: concluir duas tarefas seguidas dá a mesma frase duas vezes, e o
     * `StateFlow` não emite valor igual ao atual. Sem isto o segundo "Feito." não chegava a
     * ninguém e a tela ficava com o desfazer armado do primeiro — da tarefa errada.
     * Ninguém decide nada por ele.
     */
    val seq: Long,
    val text: String,
    /** O que o botão "Desfazer" desfaz; nulo quando o recado não tem volta. */
    val undo: Undo? = null,
) {
    /**
     * O item que o desfazer devolve anda **dentro do recado**. Ele era guardado à parte
     * (`lastCompleted`/`lastDeleted`) e o desfazer apontava para o último item tocado, não
     * para o item do recado que a pessoa está vendo: com dois itens concluídos em sequência,
     * o "Desfazer" do primeiro desfazia o segundo.
     */
    sealed interface Undo {
        val item: AgendaItem

        data class Complete(override val item: AgendaItem) : Undo

        data class Delete(override val item: AgendaItem) : Undo
    }
}

private const val TAG = "FalaAgendaHome"

class HomeViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val agendaUi: StateFlow<AgendaUi> = agendaUiFrom(container.tasks.observeAgenda()).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        initialAgendaUi,
    )

    /**
     * O parse da fala roda no escopo do ViewModel: sobrevive à rotação e a sair da home, e
     * o rascunho fica guardado até a tela mostrá-lo.
     */
    val speech = SpeechSession(scope = viewModelScope, parse = { parse(it) })

    private val _inexactWarning = MutableStateFlow(false)
    val inexactWarning: StateFlow<Boolean> = _inexactWarning

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _availableUpdate = MutableStateFlow<UpdateCheck?>(null)
    val availableUpdate: StateFlow<UpdateCheck?> = _availableUpdate

    /** Recado que o ViewModel não conseguiu entregar: a tela mostra e descarta. */
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError

    /**
     * O desfecho da última gravação de rascunho, esperando a tela que a pediu. Fica aqui
     * — e não no `onDone` da composição — porque a escrita atravessa o giro do aparelho:
     * quem volta encontra o desfecho e age, uma vez só.
     */
    private val _draftSaveOutcome = MutableStateFlow<DraftSaveOutcome?>(null)
    val draftSaveOutcome: StateFlow<DraftSaveOutcome?> = _draftSaveOutcome

    /** Ver [DraftSaveOutcome.seq]: é o que faz dois desfechos iguais serem dois eventos. */
    private var outcomeSeq = 0L

    /** O contador dos pedidos de gravação. Ver [DraftSaveOutcome.requestId]. */
    private var saveRequestSeq = 0L

    /**
     * Os pedidos de gravação de rascunho cujo desfecho ainda não saiu na tela de quem os
     * pediu. É o que a tela lê para saber se a gravação *dela* está em voo — botões fora da
     * mão dela e a saída bloqueada —, e é o que o `savePending` da composição não podia
     * responder sozinho: o pedido dele fica guardado no Bundle, então girar não o perde (é
     * o que se quer), mas a morte do processo o devolvia sem gravação nenhuma do outro lado
     * e a tela ficava presa em "Salvando…", sem saída, para um aviso que não existia mais.
     */
    private val _pendingDraftSaves = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDraftSaves: StateFlow<Set<Long>> = _pendingDraftSaves

    /**
     * O recado da última ação, esperando a home. Ver [StatusMessage]: a escrita atravessa o
     * giro, então quem anuncia é quem volta, não a tela que pediu.
     */
    private val _statusMessage = MutableStateFlow<StatusMessage?>(null)
    val statusMessage: StateFlow<StatusMessage?> = _statusMessage

    private var statusSeq = 0L

    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.updater.check() }.getOrNull()
            }
            _availableUpdate.value = result?.takeIf { it.newer }
        }
    }

    fun setInexactWarning(value: Boolean) {
        _inexactWarning.value = value
    }

    fun consumeWriteError() {
        _writeError.value = null
    }

    /**
     * A tela agiu sobre o desfecho: ele não volta numa próxima composição, e o pedido deixa
     * de estar em voo (ver [pendingDraftSaves]) — é só depois de o desfecho sair na tela que
     * os botões voltam para a mão dela.
     */
    fun consumeDraftSaveOutcome() {
        _draftSaveOutcome.value?.let { _pendingDraftSaves.value -= it.requestId }
        _draftSaveOutcome.value = null
    }

    /**
     * Publica o recado de uma ação que terminou. Quem o mostra é a home — e só depois de
     * ele sair inteiro na tela, senão o giro o apagaria para sempre.
     */
    fun publishStatus(text: String, undo: StatusMessage.Undo? = null) {
        _statusMessage.value = StatusMessage(++statusSeq, text, undo)
    }

    /** O recado saiu da tela inteiro: ele não volta numa próxima composição. */
    fun consumeStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Toda escrita passa por aqui: sem try/catch uma exceção de IO derrubava o processo
     * e o usuário não sabia se a tarefa foi salva. Falha sem [onError] cai em [writeError],
     * que a tela mostra — falhar calado não é uma opção.
     */
    private fun <T> write(
        action: String,
        onError: ((String) -> Unit)? = null,
        onSuccess: (T) -> Unit = {},
        block: suspend () -> T,
    ) = viewModelScope.launch {
        _busy.value = true
        try {
            val result = withContext(Dispatchers.IO) { block() }
            onSuccess(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            fail(action, error, onError)
        } finally {
            // Sem isto o botão Salvar segue ativo durante a gravação e dois toques
            // disparam dois editOccurrence concorrentes.
            _busy.value = false
        }
    }

    private fun fail(action: String, error: Exception, onError: ((String) -> Unit)?) {
        val message = "$action Tente de novo."
        Log.w(TAG, action, error)
        if (onError != null) onError(message) else _writeError.value = message
    }

    /**
     * A gravação do rascunho não pertence à tela: o resultado sai por [draftSaveOutcome],
     * que sobrevive ao giro, e quem pediu a gravação o consome.
     *
     * Devolve a identidade do pedido, criada agora — e não na conclusão: é com ela que a
     * tela reconhece o desfecho como sendo o do pedido *dela* (ver
     * [DraftSaveOutcome.requestId]).
     */
    fun saveDraft(draft: ParsedTaskDraft, origin: DraftSaveOrigin): Long {
        val requestId = newDraftSaveRequest()
        write(
            action = "Não consegui salvar o recado.",
            onError = { message -> publishFailed(requestId, origin, message) },
            onSuccess = { result -> publishSaved(requestId, origin, announceOf(draft), result.usedInexactAlarm) },
        ) { container.tasks.saveDraft(draft) }
        return requestId
    }

    /** Ver [pendingDraftSaves]: o pedido entra em voo aqui e só sai quando a tela o mostra. */
    private fun newDraftSaveRequest(): Long {
        val requestId = ++saveRequestSeq
        _pendingDraftSaves.value += requestId
        return requestId
    }

    private fun publishSaved(
        requestId: Long,
        origin: DraftSaveOrigin,
        message: String,
        usedInexactAlarm: Boolean? = null,
    ) {
        _draftSaveOutcome.value = DraftSaveOutcome.Saved(++outcomeSeq, requestId, origin, message, usedInexactAlarm)
    }

    private fun publishFailed(requestId: Long, origin: DraftSaveOrigin, message: String) {
        _draftSaveOutcome.value = DraftSaveOutcome.Failed(++outcomeSeq, requestId, origin, message)
    }

    /** O que se anuncia ao salvar: o horário em que o aviso vai tocar. */
    private fun announceOf(draft: ParsedTaskDraft): String {
        val date = draft.localDate
        val time = draft.localTime
        return if (date != null && time != null) {
            AgendaFormat.announce(date, time, LocalDate.now())
        } else {
            "Tarefa salva."
        }
    }

    fun complete(item: AgendaItem) = write(
        action = "Não consegui marcar como feito.",
        // O item vai dentro do recado: é ele que o desfazer devolve, e não "o último que
        // foi tocado" — que já pode ser outro.
        onSuccess = { publishStatus("Feito.", StatusMessage.Undo.Complete(item)) },
    ) { container.tasks.complete(item.occurrence.id) }

    fun undoComplete(item: AgendaItem) = write("Não consegui desfazer.") { container.tasks.uncomplete(item) }

    fun delete(item: AgendaItem) = write(
        action = "Não consegui excluir.",
        onSuccess = { publishStatus("Tarefa excluída.", StatusMessage.Undo.Delete(item)) },
    ) { container.tasks.deleteOccurrence(item.occurrence.id) }

    fun undoDelete(item: AgendaItem) = write("Não consegui desfazer.") { container.tasks.restore(item) }

    fun endSeries(seriesId: String) = write(
        action = "Não consegui encerrar a série.",
        onSuccess = { publishStatus("Série encerrada.") },
    ) { container.tasks.endSeries(seriesId) }

    fun snooze(id: String, minutes: Long = 30) = write(
        action = "Não consegui adiar.",
        onSuccess = {
            val at = java.time.ZonedDateTime.now().plusMinutes(minutes)
            publishStatus(
                AgendaFormat.announce(
                    at.toLocalDate(),
                    at.toLocalTime().withSecond(0).withNano(0),
                    LocalDate.now(),
                ),
            )
        },
    ) { container.tasks.snooze(id, minutes) }

    fun retryMissed(id: String) = write(
        action = "Não consegui remarcar.",
        onSuccess = { result ->
            publishStatus(
                if (result == null) {
                    "Não deu para remarcar esta tarefa."
                } else {
                    val whenLabel = AgendaFormat.dateLabel(result.date, LocalDate.now()).lowercase()
                    "Vai avisar $whenLabel às ${AgendaFormat.time(result.time)}."
                },
            )
        },
    ) { container.tasks.retryMissed(id) }

    fun edit(
        id: String,
        title: String,
        date: LocalDate,
        time: LocalTime,
        recurrence: RecurrenceRule,
        amountCents: Long? = null,
        observation: String = "",
    ): Long {
        val requestId = newDraftSaveRequest()
        write(
            action = "Não consegui salvar a mudança.",
            onError = { message -> publishFailed(requestId, DraftSaveOrigin.CONFIRM, message) },
            // Salvar uma mudança não agenda alarme novo: o aviso de alarme inexato fica onde está.
            onSuccess = {
                publishSaved(requestId, DraftSaveOrigin.CONFIRM, AgendaFormat.announce(date, time, LocalDate.now()))
            },
        ) {
            container.tasks.editOccurrence(id, title, date, time, recurrence, amountCents, observation)
        }
        return requestId
    }

    /** Repetir amanhã é uma gravação de rascunho: devolve a identidade do pedido dela. */
    fun repeatTomorrow(item: AgendaItem): Long {
        val tomorrow = LocalDate.now().plusDays(1)
        val draft = ParsedTaskDraft(
            title = item.series.title,
            localDate = tomorrow,
            localTime = item.series.localTime,
            recurrence = RecurrenceRule(),
            confidence = 1.0,
            missingFields = emptySet(),
            ambiguous = false,
            transcript = "",
            source = DraftSource.MANUAL,
            amountCents = item.series.amountCents,
            observation = item.series.observation,
        )
        // O anúncio sai do próprio rascunho: "amanhã às 8h" é a data e a hora dele.
        return saveDraft(draft, DraftSaveOrigin.CONFIRM)
    }

    suspend fun parse(text: String): ParsedTaskDraft =
        withContext(Dispatchers.IO) { container.hybridParser.parse(text) }
}

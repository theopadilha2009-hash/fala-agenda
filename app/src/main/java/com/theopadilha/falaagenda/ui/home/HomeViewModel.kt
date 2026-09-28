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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/**
 * A lista e a resposta para "isto já veio do banco?" no mesmo valor.
 *
 * Eram duas assinaturas independentes de `observeAgenda()`, e a resposta de um fluxo valia
 * para a lista do outro: com o app em outra tela, os valores retidos dos dois `stateIn`
 * diziam "carregou" enquanto a lista ainda era a inicial vazia, e o toque no aviso do
 * remédio respondia "Esta tarefa não está mais na agenda" — descartando o id. Quem busca a
 * ocorrência por id lê os dois campos da mesma emissão: [loaded] nunca fala de uma lista
 * que não é [sections].
 */
data class AgendaUi(
    val sections: AgendaSections,
    val loaded: Boolean,
)

/** Antes da primeira emissão: nada de concluir por ausência. */
internal val initialAgendaUi = AgendaUi(
    sections = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
    loaded = false,
)

internal fun agendaUiFrom(source: Flow<AgendaSections>): Flow<AgendaUi> =
    source.map { AgendaUi(sections = it, loaded = true) }

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

    val origin: DraftSaveOrigin

    /**
     * Gravou: [message] é o que a tela anuncia. [usedInexactAlarm] é o aviso de alarme
     * inexato a deixar no estado da home; é nulo quando a ação não tem opinião sobre ele
     * (salvar uma mudança não agenda alarme novo).
     */
    data class Saved(
        override val seq: Long,
        override val origin: DraftSaveOrigin,
        val message: String,
        val usedInexactAlarm: Boolean? = null,
    ) : DraftSaveOutcome

    /** Não gravou: [message] é o que a tela mostra, com o rascunho ainda no lugar. */
    data class Failed(
        override val seq: Long,
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
    enum class Undo { COMPLETE, DELETE }
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

    /** A tela agiu sobre o desfecho: ele não volta numa próxima composição. */
    fun consumeDraftSaveOutcome() {
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
     */
    fun saveDraft(draft: ParsedTaskDraft, origin: DraftSaveOrigin) = write(
        action = "Não consegui salvar o recado.",
        onError = { message -> publishFailed(origin, message) },
        onSuccess = { result -> publishSaved(origin, announceOf(draft), result.usedInexactAlarm) },
    ) { container.tasks.saveDraft(draft) }

    private fun publishSaved(origin: DraftSaveOrigin, message: String, usedInexactAlarm: Boolean? = null) {
        _draftSaveOutcome.value = DraftSaveOutcome.Saved(++outcomeSeq, origin, message, usedInexactAlarm)
    }

    private fun publishFailed(origin: DraftSaveOrigin, message: String) {
        _draftSaveOutcome.value = DraftSaveOutcome.Failed(++outcomeSeq, origin, message)
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
        onSuccess = {
            lastCompleted = item
            publishStatus("Feito.", StatusMessage.Undo.COMPLETE)
        },
    ) { container.tasks.complete(item.occurrence.id) }

    fun undoComplete() {
        val item = lastCompleted ?: return
        lastCompleted = null
        write("Não consegui desfazer.") { container.tasks.uncomplete(item) }
    }

    /** O aviso saiu da tela sem desfazer: a conclusão deixou de estar ao alcance. */
    fun forgetUndoComplete() {
        lastCompleted = null
    }

    private var lastCompleted: AgendaItem? = null
    private var lastDeleted: AgendaItem? = null

    fun delete(item: AgendaItem) = write(
        action = "Não consegui excluir.",
        onSuccess = {
            lastDeleted = item
            publishStatus("Tarefa excluída.", StatusMessage.Undo.DELETE)
        },
    ) { container.tasks.deleteOccurrence(item.occurrence.id) }

    fun undoDelete() {
        val item = lastDeleted ?: return
        lastDeleted = null
        write("Não consegui desfazer.") { container.tasks.restore(item) }
    }

    /** O aviso saiu da tela sem desfazer: a exclusão deixou de estar ao alcance. */
    fun forgetUndoDelete() {
        lastDeleted = null
    }

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
    ) = write(
        action = "Não consegui salvar a mudança.",
        onError = { message -> publishFailed(DraftSaveOrigin.CONFIRM, message) },
        // Salvar uma mudança não agenda alarme novo: o aviso de alarme inexato fica onde está.
        onSuccess = { publishSaved(DraftSaveOrigin.CONFIRM, AgendaFormat.announce(date, time, LocalDate.now())) },
    ) {
        container.tasks.editOccurrence(id, title, date, time, recurrence, amountCents, observation)
    }

    fun repeatTomorrow(item: AgendaItem) {
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
        saveDraft(draft, DraftSaveOrigin.CONFIRM)
    }

    suspend fun parse(text: String): ParsedTaskDraft =
        withContext(Dispatchers.IO) { container.hybridParser.parse(text) }
}

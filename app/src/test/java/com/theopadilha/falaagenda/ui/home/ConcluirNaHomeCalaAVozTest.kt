package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.SaveResult
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.reminders.AvisoFalado
import com.theopadilha.falaagenda.reminders.SintetizadorDeVoz
import com.theopadilha.falaagenda.reminders.VozDoLembrete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * Concluir pela home cala a voz do lembrete que está falando.
 *
 * O toque nos botões da notificação já calava a voz (`ReminderActionReceiver`), mas o caminho
 * mais provável não: ela está na cozinha, ouve "Está na hora. Tomar remédio", abre o aplicativo e
 * toca em "Concluir" na lista. O `scheduler.cancel` do repositório apaga o alarme e a notificação
 * — a voz não, que segue dizendo a frase até as duas repetições acabarem, com a tarefa já
 * concluída. Para ela isso é indistinguível de "o áudio nunca funciona": o áudio funciona na hora
 * errada, dizendo para ela tomar um remédio que ela acabou de tomar.
 *
 * O motor de voz não existe no Robolectric, então quem está no registro é um [AvisoFalado] de
 * verdade com um sintetizador de mentira: o que este teste prende é o **efeito** — o motor cala e
 * solta —, e não que uma linha foi escrita.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class ConcluirNaHomeCalaAVozTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel

    private class VozDeMentira : SintetizadorDeVoz {
        var paradas = 0
        var soltadas = 0
        private var esperando: ((Boolean) -> Unit)? = null

        override fun quandoPronto(bloco: (Boolean) -> Unit) {
            esperando = bloco
        }

        fun ficouPronto() = esperando?.invoke(true)

        override fun falar(texto: String, id: String, aoTerminar: (String) -> Unit) = true

        override fun parar() {
            paradas++
        }

        override fun soltar() {
            soltadas++
        }
    }

    private val voz = VozDeMentira()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        container = AppContainer(context)
        viewModel = HomeViewModel(container)
    }

    @After
    fun tearDown() {
        VozDoLembrete.registrar(null)
        Dispatchers.resetMain()
    }

    /** Põe no registro a voz que está falando agora, como o serviço faz ao subir. */
    private fun vozFalando(occurrenceId: String) {
        val aviso = AvisoFalado(voz, agendar = { _, _ -> }) { }
        VozDoLembrete.registrar(aviso)
        aviso.falar(occurrenceId, "Está na hora. Tomar remédio.")
        voz.ficouPronto()
    }

    private fun salvo(): SaveResult = runBlocking { container.tasks.saveDraft(recado()) }

    private fun recado() = ParsedTaskDraft(
        title = "Tomar remédio",
        localDate = LocalDate.now().plusDays(1),
        localTime = LocalTime.of(8, 0),
        recurrence = RecurrenceRule(RecurrenceKind.NONE),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = "Tomar remédio",
    )

    private fun itemDe(salvo: SaveResult) =
        AgendaItem(occurrence = salvo.occurrence, series = salvo.series)

    private fun esperaAGravacaoTerminar() {
        runBlocking { withTimeout(15_000L) { viewModel.busy.first { !it } } }
    }

    /**
     * O botão "Concluir" da home. A voz do lembrete que está tocando cala na hora — não quando a
     * segunda repetição acabar.
     */
    @Test
    fun concluirNaHomeCalaAVozDoLembrete() {
        val salvo = salvo()
        vozFalando(salvo.occurrence.id)
        assertThat(voz.paradas).isEqualTo(0)

        viewModel.complete(itemDe(salvo))
        esperaAGravacaoTerminar()

        assertThat(voz.paradas).isAtLeast(1)
        assertThat(voz.soltadas).isAtLeast(1)
    }

    /**
     * O comando de voz "conclui" e o "Adiar" da lista são o mesmo caminho: os dois passam pelo
     * ViewModel. Adiar é o gesto em que ela quer a voz calada e o aviso mais tarde — continuar
     * ouvindo "está na hora" depois de adiar é o aviso brigando com ela.
     */
    @Test
    fun adiarNaHomeCalaAVozDoLembrete() {
        val salvo = salvo()
        vozFalando(salvo.occurrence.id)
        assertThat(voz.paradas).isEqualTo(0)

        viewModel.snooze(salvo.occurrence.id, 30)
        esperaAGravacaoTerminar()

        assertThat(voz.paradas).isAtLeast(1)
        assertThat(voz.soltadas).isAtLeast(1)
    }

    /**
     * A guarda que impede o conserto de virar estrago: concluir **outra** tarefa não pode calar o
     * lembrete que está falando. Sem ela, concluir qualquer item da lista — de qualquer dia, de
     * qualquer série — cortaria a voz do remédio das 08:00.
     */
    @Test
    fun concluirOutraTarefaNaoCalaAVozQueEstaFalando() {
        val falando = salvo()
        val outra = runBlocking {
            container.tasks.saveDraft(recado().copy(title = "Pagar a conta de luz"))
        }
        vozFalando(falando.occurrence.id)

        viewModel.complete(itemDe(outra))
        esperaAGravacaoTerminar()

        assertThat(voz.paradas).isEqualTo(0)
        assertThat(voz.soltadas).isEqualTo(0)
    }
}

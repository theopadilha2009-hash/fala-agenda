package com.theopadilha.falaagenda.ui

import android.app.Application
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.capture.WriteStep
import com.theopadilha.falaagenda.ui.capture.writeStepFor
import com.theopadilha.falaagenda.ui.home.SpeechSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * O "Cancelar" da tela de escrever era só `popBackStack()`: a tela saía e o entendimento
 * daquela fala continuava em voo. Com o parse de volta, a sessão publicava o rascunho com
 * ela já na home — e ao abrir "Escrever tarefa" de novo a rota encontrava
 * [WriteStep.Ready] e caía na confirmação do recado abandonado, com o texto antigo na
 * tela, sem ela ter digitado nada. A falha do parse abandonado reaparecia do mesmo jeito,
 * como "Não consegui entender o recado" numa tela recém-aberta.
 *
 * O root não tem teste de renderização (o `FalaAgendaRoot` precisa do `HomeViewModel`, do
 * Room e do `NavHost`), então o que se prova aqui é o gesto de sair que a rota chama — o
 * mesmo [cancelWrite] do `onCancel` — contra uma [SpeechSession] de verdade e o que a rota
 * voltaria a encontrar na próxima composição.
 *
 * As duas portas de saída da tela chamam este mesmo gesto: o "Cancelar" e o voltar do
 * sistema (o `BackHandler` da rota `"write"`, habilitado enquanto há parse em voo). O
 * `BackHandler` em si não é alcançável aqui — montar a rota exige o `HomeViewModel`, o Room
 * e o `NavHost`, e não há costura para prender em voo o parse que vem do `AppContainer`;
 * sem isso o defeito não se reproduz. O que está coberto é o gesto que os dois chamam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class CancelWriteTest {
    /** O escopo que no aplicativo é o do ViewModel (não o da tela). Aqui, sem
     *  confinamento, cada passo acontece na hora e o teste não depende de relógio. */
    private fun escopoSemConfinamento() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun recado(titulo: String = "Tomar remédio") = ParsedTaskDraft(
        title = titulo,
        localDate = LocalDate.of(2026, 9, 28),
        localTime = LocalTime.of(9, 0),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
        source = DraftSource.AI,
    )

    private fun nav() = NavHostController(ApplicationProvider.getApplicationContext())

    @Test
    fun oCancelarDaEscritaAbandonaOParseEmVoo() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { gate.await(); recado() }

        session.understand("comprar pão amanhã às 10:00")
        assertThat(session.state.value.understanding).isTrue()

        // O toque em "Cancelar" durante os ~20 s do parse com IA.
        cancelWrite(nav(), session)
        gate.complete(Unit)

        // O que a rota "write" encontra na próxima composição: nada que a mande para a
        // confirmação. Com o rascunho publicado, isto era `WriteStep.Ready`.
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)
    }

    /** A falha do recado abandonado é tão dela quanto o rascunho: não aparece na escrita. */
    @Test
    fun aFalhaDoParseAbandonadoNaoReapareceNaEscritaNova() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) {
            gate.await()
            throw IllegalStateException("sem rede")
        }

        session.understand("comprar pão amanhã às 10:00")
        cancelWrite(nav(), session)
        gate.complete(Unit)

        assertThat(session.state.value.error).isNull()
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)
    }
}

package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A resposta da pergunta não nega o dia que a MESMA tela está desmentindo — de qualquer data.
 *
 * O #116 prendeu o missed de HOJE: "o que tenho hoje?" ouvia "Não tem nada marcado para hoje"
 * com o remédio de hoje na seção "Não consegui avisar". A resposta continuou negando o dia com
 * o remédio de ONTEM logo abaixo, porque o recorte dela continuou sendo o dia perguntado — e
 * a seção de baixo (`missedSections`) não tem janela nenhuma. Ela não tem nada pendente hoje,
 * pergunta, ouve que está em dia, e a dose que o celular não avisou ontem some da cabeça dela.
 *
 * A mesma doutrina, estendida sem janela: a resposta pode dizer que o dia perguntado está vazio
 * (é verdade), mas a não realizada visível na tela não pode ficar de fora — com o app como
 * sujeito quando a falha é dele, e o dia real da ocorrência no rótulo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class FalaRespostaForaDoDiaTest {

    /** Dona do `Dispatchers.Main` e do escopo do `HomeViewModel`. Ver [FalaComandoTest]. */
    @get:Rule
    val escopo = TestViewModelScopeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var container: AppContainer
    private lateinit var viewModel: HomeViewModel
    private val hoje: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        container = AppContainer(context)
        viewModel = escopo.rastrear(HomeViewModel(container))
    }

    /**
     * O estado do defeito: uma não realizada de ONTEM que o aviso nunca alcançou
     * (`lastReminderAt` nulo, a seção "Não consegui avisar"), e nenhuma pendente. Ela pergunta
     * "o que tenho hoje?" e a resposta negava o dia inteiro, com o remédio de ontem visível
     * logo abaixo dela na tela.
     */
    @Test
    fun oQueTenhoHojeNaoNegaONaoAvisadoDeOntem() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(1))
        semear(serie, listOf(ocorrenciaNaoRealizada(serie, hoje.minusDays(1), ultimoAviso = null)))

        // O estado como a tela o mostra: nada pendente, e a seção "Não consegui avisar" com o
        // remédio de ontem. Sem este par a asserção de baixo não prova nada.
        assertThat(agenda().today + agenda().upcoming).isEmpty()
        assertThat(missedSections(agenda().missed).first().items.map { it.series.title })
            .containsExactly("Tomar remédio")

        viewModel.understandSpeech("o que tenho hoje?")

        val resposta = proximoRecado()
        assertThat(resposta).contains("Não tem nada marcado para hoje")
        assertThat(resposta).contains("não consegui avisar ontem")
        assertThat(resposta).contains("Tomar remédio")
    }

    /**
     * A não realizada de ontem em que o aviso SAIU é outra coisa: a falta é dela, e a resposta
     * não pode acusar o aplicativo — o mesmo `missedReason` que separa as seções da tela.
     */
    @Test
    fun oQueTenhoHojeNaoAcusaOAppDoNaoFeitoDeOntem() {
        val serie = serie("s-rem", "Tomar remédio", hoje.minusDays(1))
        semear(
            serie,
            listOf(
                ocorrenciaNaoRealizada(
                    serie,
                    hoje.minusDays(1),
                    ultimoAviso = quando(serie, hoje.minusDays(1)).plusSeconds(600),
                ),
            ),
        )

        viewModel.understandSpeech("o que tenho hoje?")

        val resposta = proximoRecado()
        assertThat(resposta).contains("Não tem nada marcado para hoje")
        assertThat(resposta).doesNotContain("Não consegui avisar")
        assertThat(resposta).contains("Ficou para trás ontem")
        assertThat(resposta).contains("Tomar remédio")
    }

    // --- helpers (os mesmos de [FalaComandoTest]) ------------------------------------

    private fun agenda() = runBlocking { container.tasks.snapshotAgenda() }

    /**
     * Semeia a agenda pelo banco, e não pelo `saveDraft`: o estado que estes testes medem é o de
     * um aparelho que já viveu — a dose que o aviso não alcançou ontem. O `missedAt` entra
     * explícito porque é ele que ordena a seção.
     */
    private fun semear(serie: TaskSeries, ocorrencias: List<TaskOccurrence>) = runBlocking {
        container.db.seriesDao().upsert(serie.toEntity())
        ocorrencias.forEach { container.db.occurrenceDao().upsert(it.toEntity()) }
    }

    private fun serie(
        id: String,
        titulo: String,
        inicio: LocalDate,
    ) = TaskSeries(
        id = id,
        title = titulo,
        zoneId = ZoneId.systemDefault(),
        localTime = HORARIO,
        startLocalDate = inicio,
        recurrence = RecurrenceRule(RecurrenceKind.NONE),
        createdAt = CRIACAO,
        updatedAt = CRIACAO,
    )

    private fun ocorrenciaNaoRealizada(
        serie: TaskSeries,
        data: LocalDate,
        missedAt: Instant = quando(serie, data).plusSeconds(3_600),
        ultimoAviso: Instant? = null,
    ): TaskOccurrence {
        val quando = quando(serie, data)
        return TaskOccurrence(
            id = OccurrenceIds.of(serie.id, data),
            seriesId = serie.id,
            localDate = data,
            scheduledAt = quando,
            status = OccurrenceStatus.MISSED,
            missedAt = missedAt,
            lastReminderAt = ultimoAviso,
        )
    }

    private fun quando(serie: TaskSeries, data: LocalDate): Instant =
        LocalDateTime.of(data, serie.localTime).atZone(serie.zoneId).toInstant()

    private fun proximoRecado(): String =
        runBlocking {
            withTimeout(TEMPO_LIMITE) {
                viewModel.statusMessage.filterNotNull().first().text
            }
        }

    private companion object {
        const val TEMPO_LIMITE = 15_000L
        val HORARIO: LocalTime = LocalTime.of(8, 30)
        val CRIACAO: Instant = Instant.parse("2026-08-01T10:00:00Z")
    }
}

package com.theopadilha.falaagenda.ui.home

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmIds
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A hora que o cartão **renderiza** é a hora do aviso — medida na tela, não na peça que a calcula.
 *
 * [CartaoMostraOHorarioDoAvisoTest] prova a decisão por `AgendaFormat.occurrenceTime`, mas prova
 * chamando a peça: ele não passa pelo `Text` do cartão. O furo disso é concreto — trocar o
 * call-site de volta para `item.series.localTime` deixa aquela classe inteira verde, e o cartão
 * volta a anunciar a hora da série para ela. Este caso fecha o buraco compondo a home de verdade
 * (`HomeScreen` + `AppContainer` + Room real + `HomeViewModel`) e perguntando pelo texto que ela
 * lê.
 *
 * O caso é o **adiamento**, e não um caso qualquer: é o mais direto dos três desenhos em que a
 * hora da série e a do aviso divergem (a série continua 08:00 e o alarme passa para 08:30), sem
 * deslocar o dia. A asserção é do par que importa: o cartão diz 08:30 **e** o `AlarmManager` tem
 * 08:30 armado — mais "nenhum texto na tela diz 08:00", que é a metade que morre quando o
 * call-site volta a ler a série.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
    // A tela realista: é nela que o cartão de hoje é composto (o `LazyColumn` só compõe o que
    // está à vista) e que a manchete e o cartão aparecem juntos, que é o que se compara aqui.
    qualifiers = "w360dp-h800dp-xhdpi",
)
class CartaoRenderizadoMostraOHorarioDoAvisoTest {

    private val escopo = TestViewModelScopeRule()
    private val compose = createComposeRule()

    /** A regra do escopo é a mais externa: ver o KDoc de [TestViewModelScopeRule]. */
    @get:Rule
    val regras: RuleChain = RuleChain.outerRule(escopo).around(compose)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private lateinit var container: AppContainer

    /**
     * O fuso do sistema, e não um fixo: o `today` do cartão sai de `LocalDate.now()` (o
     * composable não conhece o relógio do repositório) e o das seções sai do `AppClock` — os
     * dois precisam concordar, senão a ocorrência de hoje cai em "Amanhã" na virada da
     * meia-noite e o teste mede outra coisa.
     */
    private val zone: ZoneId = ZoneId.systemDefault()
    private val hoje: LocalDate = LocalDate.now(zone)
    private val oito: Instant = hoje.atTime(8, 0).atZone(zone).toInstant()
    private val oitoEMeia: Instant = hoje.atTime(8, 30).atZone(zone).toInstant()

    private val seriesId = "s-rem"
    private val occurrenceId = OccurrenceIds.of(seriesId, hoje)

    @Before
    fun setUp() {
        // O relógio é o da série, e o mesmo do repositório que a home usa: `sectionsOf` decide
        // por ele em que seção a ocorrência cai.
        container = AppContainer(context, clock = FixedAppClock(oito, zone))
    }

    /** Série "Remédio" às 08:00 e a dose de hoje já com o aviso das 08:00 gravado. */
    private fun semearDoseDeHoje() = runBlocking {
        container.db.seriesDao().upsert(
            TaskSeries(
                id = seriesId,
                title = "Tomar remédio",
                zoneId = zone,
                localTime = LocalTime.of(8, 0),
                startLocalDate = hoje,
                recurrence = RecurrenceRule(RecurrenceKind.DAILY),
                createdAt = oito,
                updatedAt = oito,
            ).toEntity(),
        )
        container.db.occurrenceDao().upsert(
            TaskOccurrence(
                id = occurrenceId,
                seriesId = seriesId,
                localDate = hoje,
                scheduledAt = oito,
                status = OccurrenceStatus.PENDING,
                nextReminderAt = oito,
            ).toEntity(),
        )
    }

    /** O instante que o `AlarmManager` tem armado para a ocorrência, lido do shadow. */
    private fun alarmeArmado(): Instant? {
        val requestCode = AlarmIds.requestCode(occurrenceId, AlarmIds.ACTION_FIRE)
        return shadowOf(alarmManager).scheduledAlarms
            .firstOrNull { shadowOf(it.operation).requestCode == requestCode }
            ?.let { Instant.ofEpochMilli(it.triggerAtTime) }
    }

    private fun comporHome() {
        val viewModel = escopo.rastrear(HomeViewModel(container))
        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen(
                        viewModel = viewModel,
                        voice = VoiceCaptureController(context),
                        themeMode = ThemeMode.SYSTEM,
                        onThemeMode = {},
                        onOpenSettings = {},
                        onOpenMonth = {},
                        onOpenUpdate = {},
                        onWrite = {},
                        onQuick = {},
                        onDraftReady = {},
                        onEditItem = {},
                    )
                }
            }
        }
    }

    /**
     * Cada `Text` que a tela renderiza, um por string.
     *
     * A árvore mesclada devolve o cartão como um nó só — o `Text` do detalhe vem dentro da lista
     * de textos dele, junto do título —, então o que se compara aqui é a string individual, e não
     * a concatenação do nó.
     */
    private fun textosNaTela(): List<String> =
        compose.onAllNodesWithText("", substring = true).fetchSemanticsNodes().flatMap { no ->
            if (!no.config.contains(SemanticsProperties.Text)) {
                emptyList()
            } else {
                no.config[SemanticsProperties.Text].map { it.text }
            }
        }

    private fun esperarOTexto(descricao: String, condicao: () -> Boolean) {
        repeat(100) {
            if (condicao()) return
            Thread.sleep(100)
        }
        throw AssertionError("Esperei $descricao e não aconteceu. Na tela: ${textosNaTela()}")
    }

    /**
     * O adiamento de 30 min: a série continua às 08:00 e o alarme passa para as 08:30.
     *
     * A dose é adiada pelo repositório de verdade (o mesmo `snooze` do botão da tela), e depois a
     * home é composta. O que se lê é o `Text` do cartão.
     */
    @Test
    fun oCartaoRenderizadoDizAHoraDoAdiamento() {
        semearDoseDeHoje()
        runBlocking { container.tasks.snooze(occurrenceId, minutes = 30) }
        assertThat(alarmeArmado()).isEqualTo(oitoEMeia)

        comporHome()
        esperarOTexto("o cartão da dose chegar à tela") {
            textosNaTela().any { it.contains("Hoje · 08:30") }
        }

        // O que ela lê no cartão é a hora do aviso...
        val cartao = textosNaTela().filter { it.startsWith("Hoje · ") }
        assertThat(cartao).hasSize(1)
        assertThat(cartao.single()).contains("Hoje · 08:30")
        // ...e a hora da série não aparece em lugar nenhum da tela. É esta linha que morre quando
        // o call-site do cartão volta a ler `item.series.localTime`.
        assertThat(textosNaTela().filter { it.contains("08:00") }).isEmpty()
        // O alarme continua sendo a verdade contra a qual o rótulo foi medido.
        assertThat(alarmeArmado()).isEqualTo(oitoEMeia)
    }
}

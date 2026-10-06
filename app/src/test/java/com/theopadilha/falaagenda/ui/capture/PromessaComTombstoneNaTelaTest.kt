package com.theopadilha.falaagenda.ui.capture

import android.app.Application
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A tela de confirmação **renderizada** prometendo a próxima data **viva** quando a próxima da
 * regra tem tombstone.
 *
 * Este é o defeito que o #49 deixou no `main`: `occurrencesForChoice` já devolvia `armed = null`
 * para uma data excluída (nenhum alarme nela), mas a peça que a tela usa não conhecia os
 * tombstones — então a tela prometia a data excluída e o alarme tocava noutra (ou em nenhuma).
 * É o defeito de origem deste aplicativo, "a tela diz que vai avisar e o alarme não foi armado",
 * pelo caminho que o #49 criou.
 *
 * O caso é montado com a **data escolhida ontem** de propósito: assim o instante da escolha está
 * sempre no passado (qualquer hora de ontem já passou), o desvio da regra acontece em toda
 * execução, e o teste não depende do relógio de parede — o defeito do `ConfirmDraftScreenTest`
 * que roda de manhã verde e de tarde vermelho.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class PromessaComTombstoneNaTelaTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val hoje: LocalDate = LocalDate.now()
    private val hora = LocalTime.of(9, 0)

    /**
     * Série "todo dia", a escolha é de ontem às 09:00 (vencida), e amanhã — a primeira data da
     * regra depois de hoje — tem tombstone. O primeiro aviso é depois de amanhã, e é essa data
     * que a tela tem de mostrar.
     */
    @Test
    fun aTelaPrometeAProximaDataVivaQuandoAProximaDaRegraTemTombstone() {
        val escolhida = hoje.minusDays(1)
        val comTombstone = hoje.plusDays(1)
        val viva = hoje.plusDays(2)
        val regra = RecurrenceRule(RecurrenceKind.DAILY)

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                ConfirmDraftScreen(
                    initial = rascunho("Remédio", escolhida, hora, regra),
                    onCancel = {},
                    onSave = {},
                    editing = true,
                    isRecurring = true,
                    // O que o `FalaAgendaRoot` passa a entregar: as datas que ela excluiu.
                    skippedDates = setOf(comTombstone),
                )
            }
        }

        // A promessa é a data viva...
        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextContains(AgendaFormat.longDate(viva), substring = true)
        // ...e a data excluída não aparece no resumo: prometer o tombstone é a mentira que este
        // teste prende. O `substring = true` é o que faz esta asserção prender: o nó do resumo é
        // a frase inteira ("Vai avisar Quarta-feira, 7 de outubro de 2026 às 09:00. Todos os
        // dias."), e uma comparação exata não acharia nada nem com a data excluída de volta —
        // passaria sempre. O alvo é o texto renderizado, não a peça.
        compose.onNodeWithText(AgendaFormat.longDate(comTombstone), substring = true)
            .assertDoesNotExist()
    }

    /** Sem tombstone, a tela promete a primeira data da regra — o caminho de sempre, intacto. */
    @Test
    fun semTombstoneAPromessaSeguemSendoAProximaDataDaRegra() {
        val escolhida = hoje.minusDays(1)
        val proxima = hoje.plusDays(1)
        val regra = RecurrenceRule(RecurrenceKind.DAILY)

        compose.setContent {
            FalaAgendaTheme(darkTheme = false) {
                ConfirmDraftScreen(
                    initial = rascunho("Remédio", escolhida, hora, regra),
                    onCancel = {},
                    onSave = {},
                    editing = true,
                    isRecurring = true,
                )
            }
        }

        compose.onNodeWithText("Vai avisar", substring = true)
            .assertTextContains(AgendaFormat.longDate(proxima), substring = true)
    }

    private fun rascunho(
        titulo: String,
        data: LocalDate,
        hora: LocalTime,
        regra: RecurrenceRule,
    ) = ParsedTaskDraft(
        title = titulo,
        localDate = data,
        localTime = hora,
        recurrence = regra,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = "",
    )
}

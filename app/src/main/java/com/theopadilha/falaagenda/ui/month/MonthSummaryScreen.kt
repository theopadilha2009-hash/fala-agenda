package com.theopadilha.falaagenda.ui.month

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.insight.InsightRow
import com.theopadilha.falaagenda.domain.insight.MonthInsight
import com.theopadilha.falaagenda.domain.insight.MonthInsights
import com.theopadilha.falaagenda.domain.insight.Money
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.home.MissedReason
import com.theopadilha.falaagenda.ui.home.missedReason
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthSummaryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    initialMonth: YearMonth = YearMonth.now(),
) {
    val factory = remember(container) { MonthSummaryViewModel.factory(container) }
    val viewModel: MonthSummaryViewModel = viewModel(factory = factory)
    val agendaUi by viewModel.agenda.collectAsState()
    val agenda = agendaUi.sections
    var month by remember { mutableStateOf(initialMonth) }
    val rows = remember(agenda) { agenda.insightRows() }
    val insight = remember(rows, month) { MonthInsights.of(rows, month) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Resumo do mês") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { month = month.minusMonths(1) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Mês anterior")
                }
                Text(
                    insight.monthLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(
                    onClick = { month = month.plusMonths(1) },
                    modifier = Modifier.size(48.dp),
                    enabled = month.isBefore(YearMonth.now()),
                ) {
                    // Sem mês seguinte, o tint apagado era o único aviso — e é só cor.
                    // Quem não vê o cinza ouve o motivo em vez de tocar e não receber nada.
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = if (month.isBefore(YearMonth.now())) {
                            "Próximo mês"
                        } else {
                            "Próximo mês, indisponível: você está no mês atual"
                        },
                    )
                }
            }

            // A leitura da agenda falhou: sem isto a tela mostrava um mês zerado — "0 feitas",
            // "nada neste mês" — para uma agenda que ela não conseguiu ler. O zero é uma
            // afirmação falsa: ela pode ter tarefas. A releitura da `agendaUiFrom` volta
            // sozinha, e o resumo reaparece quando o banco responder.
            if (agendaUi.failed) {
                QuietCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Não consegui ler a sua agenda",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Pode ser que falte tarefa neste resumo, ou que ele esteja desatualizado. Nada foi perdido.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                QuietCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${insight.completed} feitas", style = MaterialTheme.typography.titleMedium)
                        // Só o que ela deixou de fazer é "não realizadas". A tarefa que o
                        // aplicativo não avisou sai separada, no mesmo vocabulário da home —
                        // ela não pode ler "não realizadas" sobre um remédio que o app nunca
                        // lembrou. Sem falha do app a linha é a de sempre, e o rótulo some.
                        Text("${insight.naoRealizadas} não realizadas", style = MaterialTheme.typography.bodyMedium)
                        if (insight.naoAvisadas > 0) {
                            Text(insight.naoAvisadasLabel(), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (insight.spentCents > 0) {
                            Text(
                                "Gasto marcado: ${insight.spentLabel()}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                Text("O que mais você fez", style = MaterialTheme.typography.titleMedium)
                if (insight.frequent.isEmpty()) {
                    Text(
                        emptyFrequentMessage(month, YearMonth.now()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    insight.frequent.forEach { row ->
                        QuietCard {
                            Row(
                                Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (row.times == 1) "1 vez" else "${row.times} vezes",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    "Se a tarefa foi paga, coloque o valor na hora de salvar. No fim do mês a soma aparece aqui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (insight.spentCents > 0) {
                    Text(
                        Money.formatReais(insight.spentCents),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/**
 * "ainda" só cabe no mês corrente: quem navega para um mês passado sem concluídas lia
 * "Nada neste mês ainda", como se o mês não tivesse acontecido.
 */
fun emptyFrequentMessage(month: YearMonth, today: YearMonth = YearMonth.now()): String =
    if (month.isBefore(today)) {
        "Nada foi concluído neste mês."
    } else {
        "Nada neste mês ainda. Quando concluir tarefas, elas aparecem aqui."
    }

fun AgendaSections.insightRows(): List<InsightRow> =
    (today + upcoming + completed + missed).map {
        InsightRow(
            title = it.series.title,
            date = it.occurrence.localDate,
            status = it.occurrence.status,
            amountCents = it.series.amountCents,
            // O mesmo `missedReason` que separa as seções da home — uma conta só, dois usos:
            // sem isto a camada de insights não teria como saber se o aviso chegou a sair, e
            // voltaria a cobrar dela a falta que foi do aplicativo.
            naoAvisada = it.occurrence.status == OccurrenceStatus.MISSED &&
                missedReason(it) == MissedReason.NOT_WARNED,
        )
    }

/**
 * A linha curta do cartão de recap da home. "não realizadas" continua nomeando só o que ela
 * deixou de fazer; a falha do aplicativo entra separada, com o mesmo texto da home ("Não
 * consegui avisar"). A remédio que o app nunca lembrou não vira falta dela no fechamento do
 * mês. Com `naoAvisadas == 0` o rótulo da falha é vazio e a linha é exatamente a de antes.
 */
fun monthRecapLine(insight: MonthInsight): String = buildString {
    append("${insight.completed} feitas")
    if (insight.naoRealizadas > 0) append(" · ${insight.naoRealizadas} não realizadas")
    if (insight.naoAvisadas > 0) append(" · ${insight.naoAvisadasLabel()}")
    if (insight.spentCents > 0) append(" · ${insight.spentLabel()}")
}

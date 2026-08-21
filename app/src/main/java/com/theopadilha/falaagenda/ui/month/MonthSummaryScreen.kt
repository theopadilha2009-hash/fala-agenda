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
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.insight.InsightRow
import com.theopadilha.falaagenda.domain.insight.MonthInsights
import com.theopadilha.falaagenda.domain.insight.Money
import com.theopadilha.falaagenda.ui.components.QuietCard
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthSummaryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    initialMonth: YearMonth = YearMonth.now(),
) {
    val agenda by container.tasks.observeAgenda().collectAsState(
        initial = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
    )
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
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Próximo mês")
                }
            }

            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${insight.completed} feitas", style = MaterialTheme.typography.titleMedium)
                    Text("${insight.missed} não realizadas", style = MaterialTheme.typography.bodyMedium)
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
                    "Nada neste mês ainda. Quando concluir tarefas, elas aparecem aqui.",
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

fun AgendaSections.insightRows(): List<InsightRow> =
    (today + upcoming + completed + missed).map {
        InsightRow(
            title = it.series.title,
            date = it.occurrence.localDate,
            status = it.occurrence.status,
            amountCents = it.series.amountCents,
        )
    }

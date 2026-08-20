package com.theopadilha.falaagenda.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.BuildConfig
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val quiet by container.settings.quietHours.collectAsState(
        initial = QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0)),
    )
    var picking by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val token = container.tokenStore.token()
    val configured = container.supabase.isConfigured
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
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
            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Horário de silêncio", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Nesse período as repetições pausam. O primeiro lembrete no horário que você escolheu ainda toca. As repetições voltam no fim do silêncio.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    QuietCard(
                        onClick = { picking = "start" },
                        modifier = Modifier.semantics {
                            contentDescription = "Início do silêncio ${AgendaFormat.time(quiet.start)}. Toque para mudar."
                        },
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Text("Começa", style = MaterialTheme.typography.labelLarge)
                            Text(AgendaFormat.time(quiet.start), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    QuietCard(
                        onClick = { picking = "end" },
                        modifier = Modifier.semantics {
                            contentDescription = "Fim do silêncio ${AgendaFormat.time(quiet.end)}. Toque para mudar."
                        },
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Text("Termina", style = MaterialTheme.typography.labelLarge)
                            Text(AgendaFormat.time(quiet.end), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ajuda extra (opcional)", style = MaterialTheme.typography.titleMedium)
                    val status = when {
                        !configured -> "Sem conexão com o serviço. O aplicativo funciona só neste aparelho."
                        token != null -> "Ativada neste aparelho. Frases ambíguas podem usar a ajuda extra, sem enviar áudio."
                        else -> "Desativada. Tudo continua no aparelho."
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (configured && token == null) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("Código de ativação") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PrimaryButton("Ativar") {
                            scope.launch {
                                message = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val tokenValue = container.activation.activate(code)
                                        container.tokenStore.setToken(tokenValue)
                                        "Ativado neste aparelho."
                                    }.getOrElse { it.message ?: "Não foi possível ativar." }
                                }
                            }
                        }
                    }
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text(
                "Fala Agenda ${BuildConfig.VERSION_NAME} · tarefas só neste aparelho.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    val which = picking
    if (which != null) {
        val current = if (which == "start") quiet.start else quiet.end
        val state = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosen = LocalTime.of(state.hour, state.minute)
                        val updated = if (which == "start") {
                            QuietHours(chosen, quiet.end)
                        } else {
                            QuietHours(quiet.start, chosen)
                        }
                        scope.launch { container.settings.setQuietHours(updated) }
                        message = "Horário de silêncio atualizado."
                        picking = null
                    },
                    modifier = Modifier.height(48.dp),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }, modifier = Modifier.height(48.dp)) {
                    Text("Cancelar")
                }
            },
            title = { Text(if (which == "start") "Começa" else "Termina") },
            text = { TimePicker(state = state) },
        )
    }
}

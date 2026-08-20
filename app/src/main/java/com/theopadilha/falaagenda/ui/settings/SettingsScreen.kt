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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.theme.OffWhite
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
    var startText by remember(quiet) { mutableStateOf(quiet.start.toString().take(5)) }
    var endText by remember(quiet) { mutableStateOf(quiet.end.toString().take(5)) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val token = container.tokenStore.token()
    val configured = container.supabase.isConfigured
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = OffWhite,
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OffWhite),
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
                        "Das 22h às 8h as repetições pausam. O primeiro lembrete no horário que você escolheu ainda toca. As repetições voltam às 8h.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Início (HH:mm)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("Fim (HH:mm)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton("Salvar horário de silêncio") {
                        val start = runCatching { LocalTime.parse(startText) }.getOrNull()
                        val end = runCatching { LocalTime.parse(endText) }.getOrNull()
                        if (start != null && end != null) {
                            scope.launch { container.settings.setQuietHours(QuietHours(start, end)) }
                            message = "Horário de silêncio atualizado."
                        } else {
                            message = "Use o formato 22:00."
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
                "Fala Agenda 0.1.0 · tarefas só neste aparelho.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

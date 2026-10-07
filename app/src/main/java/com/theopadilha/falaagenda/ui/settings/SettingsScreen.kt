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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.speech.OfflineVoiceStatus
import com.theopadilha.falaagenda.BuildConfig
import com.theopadilha.falaagenda.ui.AgendaFormat
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val quiet by container.settings.quietHours.collectAsState(
        initial = QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0)),
    )
    val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    // Sobrevivem à rotação: girar com o seletor de horário aberto não fecha mais o diálogo
    // (e não perde o horário já mexido, que o `TimePickerState` salva junto) nem limpa o
    // código de ativação meio digitado.
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    val token = container.tokenStore.token()
    val configured = container.supabase.isConfigured
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val message by vm.message.collectAsState()
    val voice = vm.voiceOffline.collectAsState()

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
                    Text("Aparência", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "O aplicativo claro usa fundo creme. Você pode travar claro, escuro ou seguir o celular.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        // Sem isto, com a fonte grande do sistema as linhas quebradas ficam coladas.
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            ThemeMode.SYSTEM to "Celular",
                            ThemeMode.LIGHT to "Claro",
                            ThemeMode.DARK to "Escuro",
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Horário de silêncio", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // "no horário marcado" não é redundância: `ReminderPolicy.firstReminder`
                        // devolve `fireAt = occurrenceScheduledAt` sem passar pelo
                        // `shiftOutOfQuietHours`, então o primeiro aviso realmente toca na hora
                        // dela, e não no fim do silêncio. A frase que dizia isso saiu na
                        // primeira versão deste texto, e a dúvida que ela matava ("isso vai me
                        // acordar às 8h em vez das 23h?") voltou com ela.
                        "Neste período, os avisos já dados não repetem. O primeiro aviso de cada tarefa ainda toca no horário marcado.",
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

            // Depois do silêncio, e não no topo: a tela rola, mas o que fica acima da
            // dobra é o que ela vê sem arrastar — e o ajuste que ela veio mudar é o do
            // silêncio. Este cartão é leitura, não ajuste.
            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Voz do celular", style = MaterialTheme.typography.titleMedium)
                    Text(voiceOfflineMessage(voice.value), style = MaterialTheme.typography.bodyMedium)
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
                            vm.activate(code)
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
                        picking = null
                        vm.setQuietHours(which, LocalTime.of(state.hour, state.minute))
                    },
                    // Altura mínima, não altura fixa: com a fonte grande do sistema o
                    // "OK" e o "Cancelar" eram cortados ao meio.
                    modifier = Modifier.heightIn(min = 56.dp),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Cancelar")
                }
            },
            title = { Text(if (which == "start") "Começa" else "Termina") },
            text = { TimePicker(state = state) },
        )
    }
}

/**
 * O que a tela de ajustes diz sobre a voz do celular.
 *
 * Mora fora do composable, como `voiceErrorMessage`, para o teste poder prendê-lo sem
 * montar a tela.
 *
 * A regra que governa cada frase: **o app nunca faz ela achar que fez algo errado**. A
 * reclamação dela era "o áudio nunca funciona", e o caminho da voz offline não dizia
 * nada em lugar nenhum — o modelo de 31 MB podia estar baixando, ter falhado, ou nunca
 * ter sido tentado, e a tela era igual nos três casos. Agora ela sabe, sem levar culpa
 * por nenhum deles: a falha não tem "erro" nem "falhou", e a saída é a mesma de sempre —
 * usar o microfone, que é o que de fato tenta de novo (uma tentativa por pedido de voz).
 *
 * Sem jargão pelo mesmo motivo que "reconhecimento do aparelho" saiu de
 * `voiceErrorMessage`: quem lê não sabe o que é modelo, motor ou download, e um termo
 * desses só serviria para ela achar que o problema é dela.
 */
internal fun voiceOfflineMessage(status: OfflineVoiceStatus): String = when (status) {
    OfflineVoiceStatus.NaoInstalado ->
        "A voz do seu celular ainda não foi preparada. Toque no microfone para preparar."
    // O estado que ela mais vai ver: são 31 MB, e o download pode durar. Dizer que pode
    // usar o microfone normalmente é o que evita ela achar, no meio do caminho, que
    // quebrou alguma coisa.
    OfflineVoiceStatus.Instalando ->
        "Estamos preparando a voz do celular. Pode usar o microfone normalmente."
    OfflineVoiceStatus.Pronto -> "A voz do seu celular está pronta."
    // "Tente de novo" seria um botão que não existe: a segunda tentativa é o próximo
    // toque no microfone, e é isso que a frase diz. O motivo real fica no estado e no
    // log, para quem atende o telefone diagnosticar à distância.
    is OfflineVoiceStatus.Falhou ->
        "Não deu para preparar a voz do celular. Use o microfone normalmente; na próxima vez tentamos de novo."
}

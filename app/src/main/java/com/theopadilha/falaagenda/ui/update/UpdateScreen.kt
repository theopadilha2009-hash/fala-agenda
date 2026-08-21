package com.theopadilha.falaagenda.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.BuildConfig
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.platform.AppUpdater
import com.theopadilha.falaagenda.platform.DeviceIntents
import com.theopadilha.falaagenda.platform.UpdateCheck
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<UpdateCheck?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }

    fun lookUp() {
        checking = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.updater.check() }
            }
            checking = false
            result.onSuccess { info = it }.onFailure {
                error = it.message ?: "Não consegui procurar atualização."
            }
        }
    }

    LaunchedEffect(container) { lookUp() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Atualizar") },
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
            Text(
                "Versão neste aparelho: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (AppUpdater.isDebugInstall()) {
                Text(
                    "Esta é a instalação de teste. A atualização oficial substitui só o aplicativo enviado (sem .debug).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QuietCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            checking -> "Procurando versão nova…"
                            error != null -> error!!
                            else -> info?.message ?: "Toque para procurar."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (checking || downloading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            PrimaryButton(
                text = when {
                    checking -> "Procurando…"
                    downloading -> "Baixando…"
                    info?.newer == true && apk == null -> "Baixar e instalar"
                    apk != null -> "Instalar agora"
                    else -> "Procurar de novo"
                },
                enabled = !checking && !downloading,
                onClick = {
                    val current = info
                    val file = apk
                    when {
                        current?.newer == true && current.apkUrl != null && file == null -> {
                            downloading = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { container.updater.download(current.apkUrl) }
                                }
                                downloading = false
                                result.onSuccess { apk = it }.onFailure {
                                    error = it.message ?: "Não deu para baixar."
                                }
                            }
                        }
                        file != null -> {
                            if (!DeviceIntents.canInstallPackages(context)) {
                                context.startActivity(DeviceIntents.unknownSources(context))
                            } else {
                                context.startActivity(DeviceIntents.installApk(context, file))
                            }
                        }
                        else -> lookUp()
                    }
                },
            )
            SecondaryButton("Voltar") { onBack() }
            Text(
                "A versão nova vem do mesmo lugar em que o aplicativo é publicado. O arquivo não passa pela loja.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

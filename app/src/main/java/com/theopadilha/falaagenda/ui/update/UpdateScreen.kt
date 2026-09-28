package com.theopadilha.falaagenda.ui.update

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.BuildConfig
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.platform.AppUpdater
import com.theopadilha.falaagenda.platform.DeviceIntents
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

// O download não pode morrer quando ela sai da tela nem a cada giro do aparelho: o
// escopo é do processo, não da composição.
private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Sem aplicativo que responda ao intent, [DeviceIntents.open] devolve `false` em vez de
 * deixar o ActivityNotFoundException fechar o aplicativo na cara dela. O susto vira um
 * recado na própria tela.
 */
private fun Context.openOrReport(intent: Intent, session: UpdateSession, message: String) {
    if (!DeviceIntents.open(this, intent)) session.report(message)
}

private object UpdateSessions {
    @Volatile
    private var session: UpdateSession? = null

    fun of(container: AppContainer): UpdateSession =
        session ?: synchronized(this) {
            session ?: UpdateSession(
                scope = updateScope,
                lookUp = { withContext(Dispatchers.IO) { container.updater.check() } },
                fetch = { url, sha ->
                    withContext(Dispatchers.IO) { container.updater.download(url, sha) }
                },
            ).also { session = it }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val session = UpdateSessions.of(container)
    val state by session.state.collectAsState()

    LaunchedEffect(session) { session.start() }

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
                            state.checking -> "Procurando versão nova…"
                            state.message != null -> state.message!!
                            else -> state.info?.message ?: "Toque para procurar."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (state.working) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            PrimaryButton(
                text = when {
                    state.checking -> "Procurando…"
                    state.downloading -> "Baixando…"
                    state.apk != null -> "Instalar agora"
                    state.info?.newer == true -> "Baixar e instalar"
                    else -> "Procurar de novo"
                },
                enabled = !state.working,
                onClick = {
                    val info = state.info
                    if (state.apk != null) {
                        // O arquivo é conferido aqui: se ele sumiu do cache, a tela volta
                        // para "Baixar e instalar" e explica, em vez de mandar ela para um
                        // instalador que só sabe dizer "não foi possível analisar o pacote".
                        when (val step = session.installStep(DeviceIntents.canInstallPackages(context))) {
                            InstallStep.ApkGone -> Unit
                            InstallStep.AllowInstall ->
                                context.openOrReport(
                                    DeviceIntents.unknownSources(context),
                                    session,
                                    "Não consegui abrir as configurações de instalação do aparelho.",
                                )
                            is InstallStep.OpenInstaller ->
                                context.openOrReport(
                                    DeviceIntents.installApk(context, step.apk),
                                    session,
                                    "Não consegui abrir o instalador do aparelho.",
                                )
                        }
                    } else if (info?.newer == true && info.apkUrl != null) {
                        session.downloadNow()
                    } else {
                        session.refresh()
                    }
                },
            )
            if (state.downloading) {
                Text(
                    "Pode sair desta tela: o download continua e você instala depois.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SecondaryButton("Voltar") { onBack() }
            Text(
                "A versão nova vem do mesmo lugar em que o aplicativo é publicado. O arquivo não passa pela loja.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

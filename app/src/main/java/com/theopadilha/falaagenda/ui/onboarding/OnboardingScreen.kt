package com.theopadilha.falaagenda.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.QuietCard
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import com.theopadilha.falaagenda.ui.theme.OffWhite
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    settings: SettingsStore,
) {
    var step by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 2 }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 3 }

    fun finish() {
        scope.launch {
            settings.setOnboardingComplete()
            onFinished()
        }
    }

    val pages = listOf(
        Triple(
            "Bem-vinda ao Fala Agenda",
            "Você fala o que precisa lembrar. Nós anotamos neste aparelho e avisamos na hora. Nada de tarefa vai para a nuvem.",
            "Começar",
        ),
        Triple(
            "Microfone",
            "Usamos o microfone só quando você toca no botão de falar. Assim o aplicativo entende o recado. O áudio não é enviado nem guardado fora do aparelho.",
            "Permitir microfone",
        ),
        Triple(
            "Notificações",
            "Os avisos aparecem mesmo com o aplicativo fechado. Nas notificações você pode concluir ou adiar 30 minutos sem abrir a tela.",
            "Permitir avisos",
        ),
        Triple(
            "Alarmes no horário certo",
            "O Android pede um acesso especial para tocar exatamente no horário combinado. Sem isso, a tarefa ainda é salva, mas o aviso pode atrasar um pouco.",
            "Abrir ajuste de alarme",
        ),
    )

    Scaffold(containerColor = OffWhite) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val page = pages[step]
            Text(page.first, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            QuietCard {
                Text(page.second, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
            }
            PrimaryButton(page.third) {
                when (step) {
                    0 -> step = 1
                    1 -> mic.launch(Manifest.permission.RECORD_AUDIO)
                    2 -> {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            step = 3
                        }
                    }
                    3 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                },
                            )
                        }
                        finish()
                    }
                }
            }
            if (step > 0) {
                SecondaryButton("Agora não") {
                    if (step < 3) step += 1 else finish()
                }
            }
        }
    }
}

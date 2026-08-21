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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.PulsingMic
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    settings: SettingsStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun finish() {
        scope.launch {
            settings.setOnboardingComplete()
            onFinished()
        }
    }

    fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }
        finish()
    }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestExactAlarm()
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Build.VERSION.SDK_INT >= 33) {
            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestExactAlarm()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            PulsingMic(
                state = VoiceState.IDLE,
                contentDescription = "Microfone",
            )
            Text(
                "Fala Agenda",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Fala o recado. Avisa na hora. Fica só neste aparelho.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton("Começar") { mic.launch(Manifest.permission.RECORD_AUDIO) }
            SecondaryButton("Agora não") { finish() }
        }
    }
}

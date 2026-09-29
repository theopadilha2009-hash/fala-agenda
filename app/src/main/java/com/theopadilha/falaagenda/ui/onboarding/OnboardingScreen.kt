package com.theopadilha.falaagenda.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.speech.VoiceState
import com.theopadilha.falaagenda.ui.components.PrimaryButton
import com.theopadilha.falaagenda.ui.components.PulsingMic
import com.theopadilha.falaagenda.ui.components.SecondaryButton
import kotlinx.coroutines.CancellationException

private const val TAG = "FalaAgendaOnboarding"

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    settings: SettingsStore,
) {
    val context = LocalContext.current
    var micRefused by remember { mutableStateOf(false) }
    var notifRefused by remember { mutableStateOf(false) }
    var exactRefused by remember { mutableStateOf(false) }
    // A saída daqui fica guardada num estado que o giro não apaga, e quem a executa é o
    // efeito abaixo. No escopo da composição, girar o aparelho logo depois do toque
    // cancelava a gravação do `onboardingComplete` no meio (ou antes de ela começar, no
    // despacho) e o onboarding voltava a aparecer na abertura seguinte — ela já tinha
    // começado a usar o aplicativo.
    var finishing by rememberSaveable { mutableStateOf(false) }
    // "Agora não" com o pedido de avisos em voo: a resposta do sistema sai desta tela em vez
    // de seguir para o alarme exato. Guardado para o giro não perder a saída no meio.
    var exitAfterNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(finishing) {
        if (!finishing) return@LaunchedEffect
        try {
            settings.setOnboardingComplete()
        } catch (cancellation: CancellationException) {
            // Sair de cena no meio não é falha: a tela recriada encontra a saída guardada
            // e grava de novo.
            throw cancellation
        } catch (error: Exception) {
            // A gravação não pode segurar a saída daqui: ela segue para a home do mesmo
            // jeito, e o onboarding (e o botão) continuam valendo na próxima abertura.
            Log.w(TAG, "Não consegui marcar o onboarding como visto.", error)
        }
        finishing = false
        onFinished()
    }

    fun finish() {
        finishing = true
    }

    // A volta da tela de alarme não traz código de resultado: quem responde é o próprio
    // sistema, na checagem daqui. Antes o toque abria a tela e saía do onboarding na
    // sequência, sem ler nada — ela negava, ia direto para a home sem um aviso e só
    // descobria que o alarme pode atrasar depois de salvar a primeira tarefa. É o mesmo
    // tratamento que o microfone e os avisos já têm logo abaixo.
    val exactAlarm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canScheduleExactAlarms(context)) finish() else exactRefused = true
    }

    fun requestExactAlarm() {
        // Já permitido (no Android 12 ela vem ligada): não há o que pedir, e a saída segue
        // para a home, que é a resposta que ela espera.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms(context)) {
            finish()
            return
        }
        // Aparelho sem essa tela responde com ActivityNotFoundException na thread
        // principal — o app fecharia no primeiro uso dela. Sem a tela, o toque segue
        // para a home (é a resposta que ela espera) e o cartão de alarme exato cobre
        // depois: aqui um recado não seria lido, a tela troca no mesmo toque.
        val opened = runCatching {
            exactAlarm.launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }.isSuccess
        if (!opened) finish()
    }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val sairAgora = exitAfterNotifications
        exitAfterNotifications = false
        if (sairAgora) {
            // Os avisos foram pedidos e ela já tinha dito que não queria decidir mais nada:
            // a resposta do sistema não pode prendê-la aqui.
            finish()
        } else if (granted) {
            requestExactAlarm()
        } else {
            // Negou (ou o sistema nem mostrou o pedido): sem notificação não toca lembrete
            // nenhum. Ela sai daqui sabendo onde reativar, e o botão não a prende.
            notifRefused = true
        }
    }

    /**
     * Pede os avisos. [exitAfter] é o "Agora não", que é sobre o microfone: os avisos são
     * pedidos de qualquer jeito, e ela sai da tela com a resposta que vier.
     *
     * O pedido não pode depender de ela ter aceitado o microfone. Antes ele só acontecia
     * dentro da cadeia do microfone concedido, e negando o microfone — ou tocando "Agora
     * não" — a permissão de aviso nunca era pedida: do Android 13 em diante ela nasce
     * negada, e o lembrete não aparecia nunca, para sempre, sem nada dizer.
     */
    fun requestNotifications(exitAfter: Boolean = false) {
        if (!hasNotificationPermission(context)) {
            exitAfterNotifications = exitAfter
            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (exitAfter) {
            finish()
        } else {
            requestExactAlarm()
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            requestNotifications()
        } else {
            // Negou o microfone: não dá para seguir como se tivesse aceitado.
            micRefused = true
            // O microfone é um recurso a menos; os avisos são o aplicativo funcionando. Negar
            // um não pode custar o outro, e as duas permissões são independentes.
            requestNotifications()
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
            if (micRefused) {
                Text(
                    "Você não permitiu o microfone. Sem ele o aplicativo não ouve o recado — mas dá para escrever a tarefa.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Para permitir depois: Ajustes do celular → Aplicativos → Fala Agenda → Permissões.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (notifRefused) {
                Text(
                    "Você não permitiu os avisos. Sem eles o aplicativo não consegue avisar na hora marcada.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Para permitir depois: Ajustes do celular → Aplicativos → Fala Agenda → Notificações.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (exactRefused) {
                Text(
                    "Você não permitiu o alarme exato. O aviso ainda toca, mas pode atrasar alguns minutos.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Para permitir depois: Ajustes do celular → Aplicativos → Fala Agenda → Alarmes e lembretes.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            when {
                // O aviso já saiu na tela: o botão só leva para a home.
                exactRefused -> PrimaryButton("Continuar") { finish() }
                // Os avisos já foram pedidos: o que falta é a tela de alarme exato.
                notifRefused -> PrimaryButton("Continuar") { requestExactAlarm() }
                micRefused -> PrimaryButton("Continuar") { requestNotifications() }
                else -> PrimaryButton("Começar") { mic.launch(Manifest.permission.RECORD_AUDIO) }
            }
            // "Agora não" pula o microfone, não os avisos: sair daqui sem pedir a permissão
            // de aviso é o caminho por onde o lembrete nunca tocou.
            SecondaryButton("Agora não") { requestNotifications(exitAfter = true) }
        }
    }
}

/**
 * O sistema responde "ainda não" enquanto a permissão não é dada; até o Android 11 o
 * alarme exato não depende dela. Mesma checagem que o `ReminderScheduler` faz do lado de
 * lá, para não abrir a tela do sistema quando não há nada a pedir.
 */
private fun canScheduleExactAlarms(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    } else {
        true
    }

/**
 * A permissão de aviso existe a partir do Android 13; antes dela os avisos nascem ligados e
 * não há o que pedir. Perguntar pelo `checkSelfPermission` cobre o caso de ela já ter
 * respondido: pedir de novo quando já está concedida não abre diálogo nenhum.
 */
private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

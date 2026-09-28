package com.theopadilha.falaagenda

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.reminders.AlarmIds
import com.theopadilha.falaagenda.ui.FalaAgendaRoot
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val openOccurrenceId = MutableStateFlow<String?>(null)
    private val startSpeak = MutableStateFlow(false)

    /** O último pedido de intent já atendido. Ver [shouldApplyAgendaIntent]. */
    private var handledIntentKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as FalaAgendaApplication
        // Preferências com problema não podem impedir o app de abrir: sem tema lido,
        // vale o do sistema.
        val mode = runCatching { runBlocking { app.container.settings.themeMode.first() } }
            .getOrDefault(ThemeMode.SYSTEM)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        setTheme(if (dark) R.style.Theme_FalaAgenda_SplashDark else R.style.Theme_FalaAgenda_SplashLight)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val intentKey = agendaIntentKey(intent.occurrenceId(), intent.wantsSpeak())
        // Girar o aparelho — ou trocar a fonte ou o tema, que também recriam a Activity —
        // traz o *mesmo* intent de volta. Aplicá-lo de novo abria o microfone sozinho
        // ("Pode falar agora" sem ela ter pedido) e reabria uma ocorrência já atendida. O
        // pedido só vale quando é novo para esta tela: a criação de verdade, sem estado
        // guardado, e o `onNewIntent` de quem já estava com o app aberto.
        if (shouldApplyAgendaIntent(savedInstanceState?.getString(STATE_HANDLED_INTENT), intentKey)) {
            openOccurrenceId.value = intent.occurrenceId()
            startSpeak.value = intent.wantsSpeak()
        }
        handledIntentKey = intentKey
        setContent {
            val occurrenceId by openOccurrenceId.collectAsState()
            val speak by startSpeak.collectAsState()
            val themeMode by app.container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FalaAgendaTheme(darkTheme = dark) {
                FalaAgendaRoot(
                    container = app.container,
                    openOccurrenceId = occurrenceId,
                    onOpenOccurrenceConsumed = { openOccurrenceId.value = null },
                    startSpeak = speak,
                    onStartSpeakConsumed = { startSpeak.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Aqui o pedido vale sempre, mesmo repetido: dois toques seguidos no "Falar" do
        // widget são dois pedidos de microfone, e não o mesmo intent voltando.
        handledIntentKey = agendaIntentKey(intent.occurrenceId(), intent.wantsSpeak())
        openOccurrenceId.value = intent.occurrenceId()
        startSpeak.value = intent.wantsSpeak()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_HANDLED_INTENT, handledIntentKey)
    }

    override fun onStop() {
        super.onStop()
        // O microfone não continua aberto com o app fora da tela: ao voltar, a sessão estaria morta.
        (application as FalaAgendaApplication).container.voice.cancel()
    }
}

private fun Intent.occurrenceId(): String? = getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID)

private fun Intent.wantsSpeak(): Boolean =
    action == ACTION_SPEAK || getBooleanExtra(EXTRA_SPEAK, false)

private const val STATE_HANDLED_INTENT = "handledIntentKey"

/**
 * A identidade de um pedido de intent: a ocorrência a abrir e o microfone a ligar. É o
 * que distingue um pedido novo do mesmo intent que a recriação da Activity devolve —
 * o `Intent` volta inteiro, então os dois valores são tudo o que ele pede.
 */
internal fun agendaIntentKey(occurrenceId: String?, wantsSpeak: Boolean): String =
    "${occurrenceId.orEmpty()}|$wantsSpeak"

/**
 * O pedido deste intent vale? Não quando é o mesmo que esta tela já atendeu: na rotação
 * (e na troca de fonte ou de tema) a Activity é recriada com o intent que a abriu, e
 * reaplicá-lo abria o microfone sozinho e refazia uma navegação que ela não pediu.
 */
internal fun shouldApplyAgendaIntent(handledIntentKey: String?, incomingIntentKey: String): Boolean =
    handledIntentKey != incomingIntentKey

const val ACTION_SPEAK = "com.theopadilha.falaagenda.SPEAK"
const val EXTRA_SPEAK = "speak"

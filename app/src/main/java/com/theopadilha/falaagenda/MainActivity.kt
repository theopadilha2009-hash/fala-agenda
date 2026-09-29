package com.theopadilha.falaagenda

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
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
        val dark = themeIsDark(mode, systemDark)
        setTheme(if (dark) R.style.Theme_FalaAgenda_SplashDark else R.style.Theme_FalaAgenda_SplashLight)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        applySystemBarStyle(dark)
        val intentKey = agendaIntentKey(intent.occurrenceId(), intent.wantsSpeak())
        // Girar o aparelho — ou trocar a fonte ou o modo escuro do *celular*, que também
        // recriam a Activity — traz o *mesmo* intent de volta. Aplicá-lo de novo abria o
        // microfone sozinho ("Pode falar agora" sem ela ter pedido) e reabria uma ocorrência
        // já atendida. O pedido só vale quando é novo para esta tela: a criação de verdade,
        // sem estado guardado, e o `onNewIntent` de quem já estava com o app aberto.
        // Escolher outro tema em Aparência, esse, não recria nada — é por isso que as
        // barras do sistema são reaplicadas lá dentro do `setContent`.
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
            val dark = themeIsDark(themeMode, systemDark)
            // Escolher outro tema em Aparência não recria a Activity, e o `enableEdgeToEdge`
            // lê o tema uma vez só, na abertura: sem reaplicar aqui, escolher "Claro" com o
            // celular no escuro deixava relógio e bateria brancos sobre o creme até girar o
            // aparelho. Reaplicar o estilo (e não `recreate()`) mexe só nas barras, sem
            // reabrir a tela e sem o flash claro↔escuro que o `setTheme` da abertura evita.
            LaunchedEffect(dark) { applySystemBarStyle(dark) }
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

    /**
     * Os ícones das barras seguem o tema do aplicativo, e não o modo do celular: sem isto o
     * `enableEdgeToEdge` lê `configuration.uiMode` sozinho e, com o celular no escuro e o
     * aplicativo no claro, relógio e bateria ficam brancos sobre o creme. Os scrims são os
     * mesmos que ele usa por padrão — a androidx não os expõe — e só valem abaixo do API 29,
     * onde a barra ainda tem cor de fundo. Chamado na abertura e a cada troca de tema.
     */
    private fun applySystemBarStyle(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAV_LIGHT_SCRIM, NAV_DARK_SCRIM) { dark },
        )
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

// Os mesmos scrims que o `enableEdgeToEdge` usa por padrão nas barras, e que a androidx
// não expõe: abaixo do API 29 a barra ainda tem cor de fundo, e é ela que muda com o tema.
private val NAV_LIGHT_SCRIM = 0xE6FFFFFF.toInt()
private val NAV_DARK_SCRIM = 0x801B1B1B.toInt()

/**
 * O aplicativo está escuro? Quem decide é o tema escolhido em Aparência; o modo do celular
 * só vale no "Celular". Vale para a janela, para o tema do Compose e para os ícones das
 * barras do sistema — o `enableEdgeToEdge` sozinho lê `configuration.uiMode`, que é o do
 * sistema, e deixava relógio e bateria brancos sobre o creme com o aplicativo no claro.
 */
internal fun themeIsDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

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

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

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as FalaAgendaApplication
        val mode = runBlocking { app.container.settings.themeMode.first() }
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
        openOccurrenceId.value = intent.occurrenceId()
        startSpeak.value = intent.wantsSpeak()
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
        openOccurrenceId.value = intent.occurrenceId()
        startSpeak.value = intent.wantsSpeak()
    }
}

private fun Intent.occurrenceId(): String? = getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID)

private fun Intent.wantsSpeak(): Boolean =
    action == ACTION_SPEAK || getBooleanExtra(EXTRA_SPEAK, false)

const val ACTION_SPEAK = "com.theopadilha.falaagenda.SPEAK"
const val EXTRA_SPEAK = "speak"

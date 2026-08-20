package com.theopadilha.falaagenda

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.theopadilha.falaagenda.reminders.AlarmIds
import com.theopadilha.falaagenda.ui.FalaAgendaRoot
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openOccurrenceId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openOccurrenceId.value = intent.occurrenceId()
        val app = application as FalaAgendaApplication
        setContent {
            val occurrenceId by openOccurrenceId.collectAsState()
            FalaAgendaTheme {
                FalaAgendaRoot(
                    container = app.container,
                    openOccurrenceId = occurrenceId,
                    onOpenOccurrenceConsumed = { openOccurrenceId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openOccurrenceId.value = intent.occurrenceId()
    }

    override fun onStop() {
        (application as FalaAgendaApplication).container.voice.cancel()
        super.onStop()
    }
}

private fun Intent.occurrenceId(): String? = getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID)

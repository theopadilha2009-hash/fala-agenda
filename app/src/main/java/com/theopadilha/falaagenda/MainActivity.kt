package com.theopadilha.falaagenda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.theopadilha.falaagenda.ui.FalaAgendaRoot
import com.theopadilha.falaagenda.ui.theme.FalaAgendaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FalaAgendaApplication
        setContent {
            FalaAgendaTheme {
                FalaAgendaRoot(container = app.container)
            }
        }
    }

    override fun onStop() {
        (application as FalaAgendaApplication).container.voice.cancel()
        super.onStop()
    }
}

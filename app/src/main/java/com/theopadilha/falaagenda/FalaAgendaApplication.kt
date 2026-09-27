package com.theopadilha.falaagenda

import android.app.Application
import android.util.Log
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.reminders.NotificationHelper
import com.theopadilha.falaagenda.widget.AgendaWidgetProvider
import com.theopadilha.falaagenda.widget.collectWidgetUpdates
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FalaAgendaApp"

class FalaAgendaApplication : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Trabalho de fundo do app. O handler é a última linha de defesa: sem ele, uma falha
     * não tratada dentro de um launch derruba o processo — e o app fecha sozinho ao abrir.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, erro ->
            Log.w(TAG, "trabalho de fundo falhou; o app segue de pé", erro)
        },
    )

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.ensureChannel(this)
        appScope.launch {
            runCatching { container.tasks.rescheduleAll() }
        }
        appScope.launch {
            collectWidgetUpdates(
                agenda = container.tasks.observeAgenda(),
                themeMode = container.settings.themeMode,
            ) { sections, mode ->
                withContext(Dispatchers.Main) {
                    AgendaWidgetProvider.refresh(this@FalaAgendaApplication, sections, mode)
                }
            }
        }
    }
}

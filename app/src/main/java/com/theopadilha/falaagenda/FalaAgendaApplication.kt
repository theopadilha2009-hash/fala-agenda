package com.theopadilha.falaagenda

import android.app.Application
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.reminders.NotificationHelper
import com.theopadilha.falaagenda.widget.AgendaWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FalaAgendaApplication : Application() {
    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.ensureChannel(this)
        appScope.launch {
            runCatching { container.tasks.rescheduleAll() }
        }
        appScope.launch {
            container.tasks.observeAgenda().collect { sections ->
                withContext(Dispatchers.Main) {
                    AgendaWidgetProvider.refresh(this@FalaAgendaApplication, sections)
                }
            }
        }
    }
}

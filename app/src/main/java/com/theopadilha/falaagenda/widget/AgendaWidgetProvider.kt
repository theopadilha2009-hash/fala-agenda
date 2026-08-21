package com.theopadilha.falaagenda.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.theopadilha.falaagenda.ACTION_SPEAK
import com.theopadilha.falaagenda.FalaAgendaApplication
import com.theopadilha.falaagenda.MainActivity
import com.theopadilha.falaagenda.R
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.launch
import java.time.LocalDate

class AgendaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val app = context.applicationContext
        if (app is FalaAgendaApplication) {
            app.appScope.launch {
                try {
                    val sections = app.container.tasks.snapshotAgenda()
                    val snapshot = snapshotOf(sections)
                    val remote = views(app, snapshot)
                    appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, remote) }
                } finally {
                    pending.finish()
                }
            }
        } else {
            val snapshot = loadSnapshot(context)
            appWidgetIds.forEach { id ->
                appWidgetManager.updateAppWidget(id, views(context, snapshot))
            }
            pending.finish()
        }
    }

    companion object {
        fun refresh(context: Context, sections: AgendaSections) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, AgendaWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val snapshot = snapshotOf(sections)
            val remote = views(app, snapshot)
            ids.forEach { manager.updateAppWidget(it, remote) }
        }

        internal fun snapshotOf(sections: AgendaSections, today: LocalDate = LocalDate.now()): Snapshot {
            val next = (sections.today + sections.upcoming)
                .filter { it.occurrence.status == OccurrenceStatus.PENDING }
                .minByOrNull { it.occurrence.scheduledAt }
            return if (next == null) {
                Snapshot(
                    title = "Nada marcado",
                    whenLabel = "Toque para abrir a agenda",
                    empty = true,
                )
            } else {
                Snapshot(
                    title = next.series.title,
                    whenLabel = "${AgendaFormat.dateLabel(next.occurrence.localDate, today)} · ${AgendaFormat.time(next.series.localTime)}",
                    empty = false,
                )
            }
        }

        private fun loadSnapshot(context: Context): Snapshot {
            val app = context.applicationContext
            return if (app is com.theopadilha.falaagenda.FalaAgendaApplication) {
                snapshotOf(app.container.tasks.latestAgenda())
            } else {
                Snapshot("Fala Agenda", "Toque para abrir", true)
            }
        }

        private fun views(context: Context, snapshot: Snapshot): RemoteViews {
            val remote = RemoteViews(context.packageName, R.layout.widget_agenda)
            remote.setTextViewText(R.id.widget_kicker, if (snapshot.empty) "Agenda" else "Próxima")
            remote.setTextViewText(R.id.widget_title, snapshot.title)
            remote.setTextViewText(R.id.widget_when, snapshot.whenLabel)
            val open = activity(context, 1, Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            val speak = activity(context, 2, Intent(context, MainActivity::class.java).apply {
                action = ACTION_SPEAK
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            remote.setOnClickPendingIntent(R.id.widget_root, open)
            remote.setOnClickPendingIntent(R.id.widget_speak, speak)
            return remote
        }

        private fun activity(context: Context, request: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                request,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }

    data class Snapshot(
        val title: String,
        val whenLabel: String,
        val empty: Boolean,
    )
}

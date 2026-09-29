package com.theopadilha.falaagenda.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.theopadilha.falaagenda.ACTION_SPEAK
import com.theopadilha.falaagenda.FalaAgendaApplication
import com.theopadilha.falaagenda.MainActivity
import com.theopadilha.falaagenda.R
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.ui.AgendaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
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
                    val snapshot = snapshotOrFallback { app.container.tasks.snapshotAgenda() }
                    val mode = themeModeOf(app)
                    val remote = views(app, snapshot, widgetColors(app, mode))
                    appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, remote) }
                } catch (cancelado: CancellationException) {
                    throw cancelado
                } catch (erro: Throwable) {
                    Log.w(TAG, "widget não atualizou", erro)
                } finally {
                    pending.finish()
                }
            }
        } else {
            // Sem o nosso Application não há container nem tema gravado: segue o sistema.
            val snapshot = Snapshot("Fala Agenda", "Toque para abrir", empty = true)
            appWidgetIds.forEach { id ->
                appWidgetManager.updateAppWidget(id, views(context, snapshot, colors = null))
            }
            pending.finish()
        }
    }

    companion object {
        fun refresh(context: Context, sections: AgendaSections, mode: ThemeMode) {
            paint(context, snapshotOf(sections), mode)
        }

        /**
         * Repinta o widget agora, sem esperar mudança no banco.
         *
         * A virada do dia e a troca de hora mudam o que o widget deve dizer — o "Hoje" que
         * virou ontem, a "Próxima" que já passou — sem escrever nada, e quem repinta o widget
         * é a escrita no banco (ver `collectWidgetUpdates`). Sem este empurrão ele seguia
         * anunciando o dia velho da meia-noite até a próxima mexida na agenda.
         */
        suspend fun refreshNow(context: Context) {
            val app = context.applicationContext as? FalaAgendaApplication ?: return
            val snapshot = snapshotOrFallback { app.container.tasks.snapshotAgenda() }
            val mode = themeModeOf(app)
            withContext(Dispatchers.Main) { paint(context, snapshot, mode) }
        }

        private fun paint(context: Context, snapshot: Snapshot, mode: ThemeMode) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, AgendaWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val remote = views(app, snapshot, widgetColors(app, mode))
            ids.forEach { manager.updateAppWidget(it, remote) }
        }

        internal fun snapshotOf(
            sections: AgendaSections,
            today: LocalDate = LocalDate.now(),
            now: Instant = Instant.now(),
        ): Snapshot {
            val pendentes = (sections.today + sections.upcoming)
                .filter { it.occurrence.status == OccurrenceStatus.PENDING }
            // Quem ainda vai tocar ganha de quem já passou. `sectionsOf` põe de propósito a
            // pendente que atravessou a meia-noite dentro de "Hoje" (ela é a mais urgente e
            // continua acionável), e o `minByOrNull` sozinho elegia justamente ela: o widget
            // anunciava "Próxima — Tomar remédio — Ontem · 08:00" da meia-noite às 08:00, que
            // é a janela em que ela olha o telefone para planejar o dia.
            val next = pendentes
                .filter { !it.occurrence.scheduledAt.isBefore(now) }
                .minByOrNull { it.occurrence.scheduledAt }
                ?: pendentes.minByOrNull { it.occurrence.scheduledAt }
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
                    // Sem nada à frente, o que sobrou é o que já passou — e o rótulo diz isso,
                    // como o cabeçalho da home já faz (ver `AgendaFormat.headline`).
                    late = next.occurrence.scheduledAt.isBefore(now),
                )
            }
        }

        /**
         * Leitura que falha vira aviso no widget: em branco ela não saberia se não tem
         * nada marcado ou se o app quebrou.
         */
        internal suspend fun snapshotOrFallback(
            today: LocalDate = LocalDate.now(),
            now: Instant = Instant.now(),
            readSections: suspend () -> AgendaSections,
        ): Snapshot = try {
            snapshotOf(readSections(), today, now)
        } catch (cancelado: CancellationException) {
            throw cancelado
        } catch (erro: Throwable) {
            Log.w(TAG, "leitura da agenda falhou; o widget avisa em vez de ficar em branco", erro)
            Snapshot(
                title = "Não consegui ler a agenda",
                whenLabel = "Toque para abrir o app",
                empty = true,
            )
        }

        private suspend fun themeModeOf(app: FalaAgendaApplication): ThemeMode =
            runCatching { app.container.settings.themeMode.first() }.getOrDefault(ThemeMode.SYSTEM)

        internal fun views(context: Context, snapshot: Snapshot, colors: WidgetColors?): RemoteViews {
            val remote = RemoteViews(context.packageName, R.layout.widget_agenda)
            // "Próxima" só quando há o que vir: com a hora marcada já passada o kicker diz
            // "Atrasada", e a tela não mente sobre o que vai acontecer.
            remote.setTextViewText(
                R.id.widget_kicker,
                when {
                    snapshot.empty -> "Agenda"
                    snapshot.late -> "Atrasada"
                    else -> "Próxima"
                },
            )
            remote.setTextViewText(R.id.widget_title, snapshot.title)
            remote.setTextViewText(R.id.widget_when, snapshot.whenLabel)
            if (colors != null) {
                // O launcher não conhece a escolha dela em Aparência, então a cor vai imposta aqui.
                remote.setTextColor(R.id.widget_kicker, colors.accent)
                remote.setTextColor(R.id.widget_title, colors.text)
                remote.setTextColor(R.id.widget_when, colors.muted)
                remote.setTextColor(R.id.widget_speak, colors.onAccent)
                remote.setInt(R.id.widget_root, "setBackgroundColor", colors.background)
                remote.setInt(R.id.widget_speak, "setBackgroundColor", colors.accent)
            }
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
        /** O que o widget está mostrando é o que já passou: o kicker diz "Atrasada". */
        val late: Boolean = false,
    )
}

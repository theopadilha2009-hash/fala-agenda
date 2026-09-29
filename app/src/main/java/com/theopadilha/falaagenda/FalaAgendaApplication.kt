package com.theopadilha.falaagenda

import android.app.Application
import android.util.Log
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.reminders.NotificationHelper
import com.theopadilha.falaagenda.speech.VoskModel
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
        container = AppContainer(this, background = appScope)
        NotificationHelper.ensureChannel(this)
        appScope.launch {
            runCatching { container.tasks.rescheduleAll() }
        }
        // O modelo da fala offline não é mais pedido aqui. Abertura de app não é pedido
        // de voz, e isto era 31 MB baixados em toda abertura — calados, na conta dela.
        // Quem pede agora é o toque no microfone: ver `OfflineModelInstaller.request`.
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

    /**
     * O sistema avisou que este processo está na fila para morrer: é o único "acabou"
     * que o aparelho dá — o `onTerminate` não é chamado aqui. É onde o modelo de 53 MB
     * do Vosk é fechado. Fora daí ele fica de pé de propósito: recarregá-lo leva
     * segundos, dentro do prazo de preparo da escuta, e é isso que custava a fala dela.
     *
     * Na thread principal e sem esperar: uma escuta de pé segura o modelo e o
     * `release` desiste na hora, sem entrar na fila de um carregamento em curso.
     *
     * O nível está marcado como obsoleto desde a API 35 — o sistema deixou de garantir
     * a entrega dele. Continua sendo o único aviso de saída que existe, e um aviso que
     * não chega não custa nada: quem morre sem ele tem a memória recuperada junto com
     * o processo.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_COMPLETE) VoskModel.release()
    }
}

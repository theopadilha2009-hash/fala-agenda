package com.theopadilha.falaagenda.widget

import android.util.Log
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.repo.AgendaSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive

internal const val TAG = "FalaAgendaWidget"

// Espera entre tentativas quando o banco não responde; o suficiente para o disco voltar.
internal const val RETRY_DELAY_MS = 5_000L

/**
 * Repinta o widget quando a agenda ou o tema gravado mudam.
 *
 * A leitura do banco não pode propagar exceção — era ela que fechava o app ao abrir — nem
 * matar a observação para sempre: uma falha passageira (disco cheio, migração, banco
 * corrompido) espera e assina de novo, porque o banco pode voltar. Tema ilegível não
 * derruba a agenda: nesse caso o widget segue o celular, como sempre fez.
 */
internal suspend fun collectWidgetUpdates(
    agenda: Flow<AgendaSections>,
    themeMode: Flow<ThemeMode>,
    retryDelayMs: Long = RETRY_DELAY_MS,
    paint: suspend (AgendaSections, ThemeMode) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        try {
            combine(agenda, themeMode.catch { emit(ThemeMode.SYSTEM) }) { sections, mode ->
                sections to mode
            }.collect { (sections, mode) -> paint(sections, mode) }
            return
        } catch (cancelado: CancellationException) {
            throw cancelado
        } catch (erro: Throwable) {
            Log.w(TAG, "leitura da agenda falhou; nova tentativa em ${retryDelayMs}ms", erro)
            delay(retryDelayMs)
        }
    }
}

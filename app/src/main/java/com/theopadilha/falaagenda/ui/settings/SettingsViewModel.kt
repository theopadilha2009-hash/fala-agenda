package com.theopadilha.falaagenda.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.theopadilha.falaagenda.data.prefs.SecureTokenStore
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.data.remote.ActivationClient
import com.theopadilha.falaagenda.di.AppContainer
import com.theopadilha.falaagenda.domain.model.QuietHours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime

private const val TAG = "FalaAgendaSettings"

/**
 * As gravações da tela de ajustes moram aqui, e não no escopo da composição: girar o
 * aparelho logo depois de tocar "OK" cancelava o `DataStore.edit` no meio (ou antes de ele
 * começar, no despacho) e o ajuste continuava o antigo, sem aviso nenhum — o mesmo na
 * ativação, cuja janela de rede é de até 15 s. O recado também vive aqui: a tela recriada
 * ainda o encontra esperando, e uma visita nova à tela não vê o recado da anterior.
 */
internal class SettingsViewModel(
    private val settings: SettingsStore,
    private val activation: ActivationClient,
    private val tokens: SecureTokenStore,
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Do ViewModel, e não da tela: girar no meio de duas gravações não as solta uma da outra. */
    private val writeLock = Mutex()

    fun setThemeMode(mode: ThemeMode) = save(
        success = "Aparência salva.",
        failure = "Não consegui salvar a aparência. Tente de novo.",
    ) { settings.setThemeMode(mode) }

    fun setQuietHours(field: String, chosen: LocalTime) = save(
        success = "Horário de silêncio atualizado.",
        failure = "Não consegui salvar o horário. Tente de novo.",
    ) {
        // Roda com o writeLock de [save] na mão: o horário que não está sendo mudado vem de
        // leitura fresca, então trocar início e fim em sequência não desfaz a primeira
        // escolha com um retrato antigo.
        val saved = settings.quietHours.first()
        settings.setQuietHours(
            if (field == "start") QuietHours(chosen, saved.end) else QuietHours(saved.start, chosen),
        )
    }

    fun activate(code: String) {
        _message.value = null
        viewModelScope.launch {
            try {
                // A mensagem do serviço ("código inválido") é mais útil que um texto
                // genérico, então o erro vem de dentro do runCatching.
                _message.value = withContext(Dispatchers.IO) {
                    runCatching {
                        val tokenValue = activation.activate(code)
                        tokens.setToken(tokenValue)
                        "Ativado neste aparelho."
                    }.getOrElse { it.message ?: "Não foi possível ativar." }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Não foi possível ativar.", error)
                _message.value = "Não foi possível ativar. Tente de novo."
            }
        }
    }

    // Toda gravação da tela passa por aqui: a mensagem de sucesso só aparece depois que o
    // DataStore confirmou. Antes a tela dizia "atualizado" na hora do toque e a falha de
    // gravação escapava pelo escopo da composição — o horário antigo seguia no cartão.
    // As gravações são serializadas: dois toques rápidos não se atropelam e o que fica
    // gravado é o último toque dela.
    private fun save(success: String, failure: String, block: suspend () -> Unit) {
        _message.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { writeLock.withLock { block() } }
                _message.value = success
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, failure, error)
                _message.value = failure
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                        return SettingsViewModel(
                            settings = container.settings,
                            activation = container.activation,
                            tokens = container.tokenStore,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
                }
            }
    }
}

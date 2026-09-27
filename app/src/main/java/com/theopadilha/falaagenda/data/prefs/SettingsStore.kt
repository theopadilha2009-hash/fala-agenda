package com.theopadilha.falaagenda.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.theopadilha.falaagenda.domain.model.QuietHours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Arquivo corrompido não pode inutilizar as preferências para sempre: o handler troca o
// arquivo pelo padrão na primeira leitura e as gravações voltam a funcionar.
internal fun replaceCorruptedPreferences(): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.dataStore by preferencesDataStore(
    name = "fala_agenda_settings",
    corruptionHandler = replaceCorruptedPreferences(),
)

class SettingsStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private val quietStartMin = intPreferencesKey("quiet_start_min")
    private val quietEndMin = intPreferencesKey("quiet_end_min")
    private val onboardingDone = booleanPreferencesKey("onboarding_done")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    // Erro de leitura (IO, disco cheio) não pode subir como exceção: o collect morre — e
    // aí o onboarding fica num spinner eterno e o tema derruba o onCreate. Falhou a
    // leitura, vale o padrão, como se a chave não existisse.
    private val prefs: Flow<Preferences> = store.data.catch { emit(emptyPreferences()) }

    val quietHours: Flow<QuietHours> = prefs.map { p ->
        QuietHours(
            start = LocalTime.ofSecondOfDay(((p[quietStartMin] ?: (22 * 60)) * 60).toLong()),
            end = LocalTime.ofSecondOfDay(((p[quietEndMin] ?: (8 * 60)) * 60).toLong()),
        )
    }

    val onboardingComplete: Flow<Boolean> = prefs.map { it[onboardingDone] == true }

    val themeMode: Flow<ThemeMode> = prefs.map { p ->
        runCatching { ThemeMode.valueOf(p[themeModeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setQuietHours(hours: QuietHours) {
        save {
            store.edit {
                it[quietStartMin] = hours.start.hour * 60 + hours.start.minute
                it[quietEndMin] = hours.end.hour * 60 + hours.end.minute
            }
        }
    }

    suspend fun setOnboardingComplete() {
        save { store.edit { it[onboardingDone] = true } }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        save { store.edit { it[themeModeKey] = mode.name } }
    }

    // As gravações são chamadas de escopo de composição, onde a exceção não tem quem a
    // receba: um erro de escrita derrubaria o processo. Aqui ele vira "não salvou" — o
    // app segue e o próximo uso tenta de novo.
    private suspend fun save(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
    }

    suspend fun currentQuietHours(): QuietHours = quietHours.first()
}

class SecureTokenStore(context: Context) {
    private val prefs = runCatching {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            "fala_agenda_secure",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("fala_agenda_secure_fallback", Context.MODE_PRIVATE)
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)
    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val KEY_TOKEN = "installation_token"
    }
}

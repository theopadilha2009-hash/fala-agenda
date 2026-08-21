package com.theopadilha.falaagenda.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.theopadilha.falaagenda.domain.model.QuietHours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore("fala_agenda_settings")

class SettingsStore(private val context: Context) {
    private val quietStartMin = intPreferencesKey("quiet_start_min")
    private val quietEndMin = intPreferencesKey("quiet_end_min")
    private val onboardingDone = booleanPreferencesKey("onboarding_done")
    private val exactAlarmWarned = booleanPreferencesKey("exact_alarm_warned")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val quietHours: Flow<QuietHours> = context.dataStore.data.map { prefs ->
        QuietHours(
            start = LocalTime.ofSecondOfDay(((prefs[quietStartMin] ?: (22 * 60)) * 60).toLong()),
            end = LocalTime.ofSecondOfDay(((prefs[quietEndMin] ?: (8 * 60)) * 60).toLong()),
        )
    }

    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[onboardingDone] == true }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeModeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setQuietHours(hours: QuietHours) {
        context.dataStore.edit {
            it[quietStartMin] = hours.start.hour * 60 + hours.start.minute
            it[quietEndMin] = hours.end.hour * 60 + hours.end.minute
        }
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[onboardingDone] = true }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeModeKey] = mode.name }
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

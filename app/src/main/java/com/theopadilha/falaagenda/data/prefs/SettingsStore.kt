package com.theopadilha.falaagenda.data.prefs

import android.content.Context
import android.util.Log
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

    // Gravação que falha sobe para quem chamou: a tela de ajustes só anuncia "atualizado"
    // depois que isto volta sem exceção. Engolir o erro aqui deixava o cartão com o horário
    // antigo e a tela dizendo que tinha salvo. Cada chamador trata — nenhum deles pode
    // deixar a exceção derrubar o processo.
    suspend fun setQuietHours(hours: QuietHours) {
        store.edit {
            it[quietStartMin] = hours.start.hour * 60 + hours.start.minute
            it[quietEndMin] = hours.end.hour * 60 + hours.end.minute
        }
    }

    suspend fun setOnboardingComplete() {
        store.edit { it[onboardingDone] = true }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[themeModeKey] = mode.name }
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
    }.getOrElse { erro ->
        // O fallback protege o app de cair, mas era mudo: se o Tink falhar (R8, KeyStore,
        // keyset corrompido) o token passa a viver em texto plano e nada na tela denuncia.
        // No aparelho da release — o único cenário onde o R8 existe — a pista é só este log:
        // `adb logcat -s SecureTokenStore` (o Log.w sobrevive ao minify; nenhuma regra do app
        // remove log de efeito colateral). `run-as` é negado na release assinada, então listar
        // `shared_prefs/` só funciona em build debugável ou root — e mesmo lá o
        // `fala_agenda_secure_fallback.xml` nasce só na primeira escrita do token: ausência
        // não descarta a degradação, só diz que ainda não gravaram nada em claro.
        Log.w(
            TAG,
            "SharedPreferences criptografado indisponível; o token vai em texto plano no fallback.",
            erro,
        )
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
        private const val TAG = "SecureTokenStore"
    }
}

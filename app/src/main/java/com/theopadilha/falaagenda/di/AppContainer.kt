package com.theopadilha.falaagenda.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.theopadilha.falaagenda.BuildConfig
import com.theopadilha.falaagenda.data.local.AppDatabase
import com.theopadilha.falaagenda.data.prefs.SecureTokenStore
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.data.remote.ActivationClient
import com.theopadilha.falaagenda.data.remote.ParseReminderClient
import com.theopadilha.falaagenda.data.remote.SupabaseConfig
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.domain.parser.HybridParser
import com.theopadilha.falaagenda.domain.parser.LocalTaskParser
import com.theopadilha.falaagenda.domain.parser.NetworkStatus
import com.theopadilha.falaagenda.domain.time.AppClock
import com.theopadilha.falaagenda.domain.time.SystemAppClock
import com.theopadilha.falaagenda.platform.AppUpdater
import com.theopadilha.falaagenda.reminders.ReminderScheduler
import com.theopadilha.falaagenda.speech.OfflineModelInstaller
import com.theopadilha.falaagenda.speech.VoiceCaptureController
import com.theopadilha.falaagenda.speech.VoskModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(
    context: Context,
    val clock: AppClock = SystemAppClock(),
    background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    val db: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)

    /**
     * O keystore só é aberto quando alguém pede o token — e quem pede é a tela de
     * ajustes, com o Supabase configurado. Antes disto a montagem do `MasterKey` e do
     * `EncryptedSharedPreferences` acontecia em toda abertura do app, na thread
     * principal, antes de qualquer tela, para um caminho que ela quase nunca usa.
     */
    val tokenStore by lazy { SecureTokenStore(appContext) }
    val supabase = SupabaseConfig(
        url = BuildConfig.SUPABASE_URL.trim(),
        anonKey = BuildConfig.SUPABASE_ANON_KEY.trim(),
    )
    val scheduler = ReminderScheduler(appContext, settings)
    val tasks = TaskRepository(db.seriesDao(), db.occurrenceDao(), clock, scheduler)
    val localParser = LocalTaskParser(clock)
    private val network = NetworkStatus {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@NetworkStatus false
        val caps = cm.getNetworkCapabilities(network) ?: return@NetworkStatus false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    val remoteParser = if (supabase.isConfigured) {
        ParseReminderClient(supabase, tokenProvider = { tokenStore.token() })
    } else {
        null
    }
    val hybridParser = HybridParser(
        local = localParser,
        clock = clock,
        remote = remoteParser,
        network = network,
        isAiEnabled = { supabase.isConfigured && !tokenStore.token().isNullOrBlank() },
    )
    val activation = ActivationClient(supabase)
    val offlineModel = OfflineModelInstaller(
        appContext,
        isMetered = { isMeteredNetwork(appContext) },
    )
    // Sem o modelo baixado isto é null e a fala segue no motor do sistema, como antes.
    // A consulta é por escuta, não uma vez só: o download pode terminar com o app aberto.
    // O motor resolvido, esse, é um por processo — quem o guarda e o fecha é o VoskModel.
    // O download, esse, quem pede é a própria escuta: abrir o app não é pedido de voz.
    val voice = VoiceCaptureController(
        appContext,
        offline = { VoskModel.offlineSpeech(appContext) },
        requestOfflineModel = { offlineModel.request(background) },
    )
    val updater = AppUpdater(appContext)
}

/** Rede medida é a que cobra por byte: os 31 MB do modelo esperam uma rede sem custo. */
private fun isMeteredNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    return cm?.isActiveNetworkMetered ?: false
}

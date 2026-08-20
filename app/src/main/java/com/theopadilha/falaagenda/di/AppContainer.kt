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
import com.theopadilha.falaagenda.reminders.ReminderScheduler
import com.theopadilha.falaagenda.speech.VoiceCaptureController

class AppContainer(
    context: Context,
    val clock: AppClock = SystemAppClock(),
) {
    private val appContext = context.applicationContext
    val db: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    val tokenStore = SecureTokenStore(appContext)
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
    val voice = VoiceCaptureController(appContext)
}

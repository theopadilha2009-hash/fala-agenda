package com.theopadilha.falaagenda.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.QuietHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalTime

class SettingsStoreTest {

    private class StoreQueFalhaNaLeitura : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("sem acesso ao arquivo") }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("sem acesso ao arquivo")
    }

    @Test
    fun erroDeLeituraViraPadraoEmVezDeExcecao() = runBlocking {
        val settings = SettingsStore(StoreQueFalhaNaLeitura())

        // Sem o catch, cada um destes derruba o coletor: spinner eterno no onboarding
        // e o onCreate do app morrendo no runBlocking do tema.
        assertThat(settings.onboardingComplete.first()).isFalse()
        assertThat(settings.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.quietHours.first())
            .isEqualTo(QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0)))
    }

    @Test
    fun gravacaoQueFalhaNaoDerrubaQuemChamou() = runBlocking {
        val settings = SettingsStore(StoreQueFalhaNaLeitura())

        // Vêm de escopo de composição: se lançarem, derrubam o processo.
        settings.setOnboardingComplete()
        settings.setThemeMode(ThemeMode.DARK)
        settings.setQuietHours(QuietHours(LocalTime.of(21, 0), LocalTime.of(7, 0)))
    }

    @Test
    fun arquivoCorrompidoViraPadraoESobreviveAEscrita() = runBlocking {
        val dir = Files.createTempDirectory("fala-agenda-prefs").toFile()
        val file = File(dir, "fala_agenda_settings.preferences_pb")
        val corrompido = byteArrayOf(0x0A, 0x7F, 0x01, 0x02, 0x03)
        file.writeBytes(corrompido)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(
                corruptionHandler = replaceCorruptedPreferences(),
                scope = scope,
                produceFile = { file },
            )
            val settings = SettingsStore(store)

            assertThat(settings.onboardingComplete.first()).isFalse()
            // O arquivo é reescrito na leitura: é o que prova que o caminho de corrupção
            // rodou de verdade (e não que estes bytes viraram preferências vazias).
            assertThat(file.readBytes().toList()).isNotEqualTo(corrompido.toList())

            // Sem a troca do arquivo, a gravação seguiria falhando e o onboarding voltaria
            // a cada uso, para sempre.
            settings.setOnboardingComplete()
            assertThat(settings.onboardingComplete.first()).isTrue()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

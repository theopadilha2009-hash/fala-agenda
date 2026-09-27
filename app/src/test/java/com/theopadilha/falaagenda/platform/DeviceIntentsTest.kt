package com.theopadilha.falaagenda.platform

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Os atalhos para os Ajustes e para o compartilhamento: cada um é um caminho sem volta do usuário. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class DeviceIntentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun autoridadeDoFileProviderSegueOPacoteDoApp() {
        // O manifesto declara "${applicationId}.files"; a autoridade tem que sair do pacote real,
        // senão o FileProvider não acha o provedor e o "Instalar agora" estoura.
        assertThat(DeviceIntents.fileProviderAuthority(context))
            .isEqualTo("${context.packageName}.files")
    }

    @Test
    fun instalarDeForaSegueOPermissaoDoSistema() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertThat(DeviceIntents.canInstallPackages(context)).isFalse()

        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertThat(DeviceIntents.canInstallPackages(context)).isTrue()
    }

    @Test
    fun telaDeFontesDesconhecidasApontaParaOProprioPacote() {
        val intent = DeviceIntents.unknownSources(context)

        assertThat(intent.action).isEqualTo(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        assertThat(intent.data.toString()).isEqualTo("package:${context.packageName}")
    }

    @Test
    fun bateriaOtimizadaPedeAExcecaoParaOApp() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)

        assertThat(DeviceIntents.isBatteryUnrestricted(context)).isFalse()
        val intent = DeviceIntents.batterySettings(context)
        assertThat(intent.action).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(intent.data.toString()).isEqualTo("package:${context.packageName}")
    }

    @Test
    fun bateriaJaLiberadaSoAbreAListaDeAjustes() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)

        assertThat(DeviceIntents.isBatteryUnrestricted(context)).isTrue()
        val intent = DeviceIntents.batterySettings(context)
        assertThat(intent.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        assertThat(intent.data).isNull()
    }

    @Test
    fun compartilharTextoLevaOTextoNoChooser() {
        val chooser = DeviceIntents.shareText("Vitamina às 8h", "Enviar")
        val envio = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(envio).isNotNull()
        assertThat(envio!!.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(envio.type).isEqualTo("text/plain")
        assertThat(envio.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Vitamina às 8h")
    }

    @Test
    fun compartilharLinkTrazAUrlDeOndeBaixar() {
        val chooser = DeviceIntents.shareLink()

        val envio = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(envio?.getStringExtra(Intent.EXTRA_TEXT)).contains(AppUpdater.RELEASES_PAGE)
    }

    @Test
    fun semApkOCompartilhamentoViraOLink() {
        val chooser = DeviceIntents.shareChooser(context, apk = null)

        val envio = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertThat(envio?.type).isEqualTo("text/plain")
        assertThat(envio?.getStringExtra(Intent.EXTRA_TEXT)).contains(AppUpdater.RELEASES_PAGE)
    }

    @Test
    fun abrirUmaTelaComQuemRespondaDaCerto() {
        var aberta: Intent? = null
        val comApp = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                aberta = intent
            }
        }
        val intent = DeviceIntents.unknownSources(context)

        assertThat(DeviceIntents.open(comApp, intent)).isTrue()
        assertThat(aberta).isEqualTo(intent)
    }

    @Test
    fun aparelhoSemAppQueRespondaNaoFechaOApp() {
        val semApp = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException("nenhum app responde a $intent")
            }
        }

        assertThat(DeviceIntents.open(semApp, Intent(Intent.ACTION_VIEW))).isFalse()
    }
}

package com.theopadilha.falaagenda.platform

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.reminder.Manufacturer
import com.theopadilha.falaagenda.domain.reminder.ManufacturerGuide
import com.theopadilha.falaagenda.domain.reminder.VendorSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * A ponte entre o guia (lógica pura) e o aparelho: qual fabricante este celular diz ser,
 * e para onde aponta cada atalho de ajustes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class ManufacturerHintTest {
    @Test
    fun oGuiaSaiDoFabricanteQueOAparelhoResponde() {
        ShadowBuild.setManufacturer("Xiaomi")
        assertThat(ManufacturerHint.guide().manufacturer).isEqualTo(Manufacturer.XIAOMI)

        ShadowBuild.setManufacturer("samsung")
        assertThat(ManufacturerHint.guide().manufacturer).isEqualTo(Manufacturer.SAMSUNG)
    }

    @Test
    fun aparelhoDaHMDSemSerNokiaNaoRecebeOGuiaDoNokia() {
        ShadowBuild.setManufacturer("HMD Global")

        assertThat(ManufacturerHint.guide().manufacturer).isNull()
    }

    @Test
    fun aparelhoDeMarcaQueAListaNaoCobreFicaComOTextoGenerico() {
        ShadowBuild.setManufacturer("Zebra")

        val guia = ManufacturerHint.guide()

        assertThat(guia.manufacturer).isNull()
        assertThat(guia.steps).isEqualTo(ManufacturerGuide.GENERIC.steps)
        assertThat(ManufacturerHint.shortcutIntent(guia.shortcut)).isNull()
    }

    @Test
    fun atalhoDoXiaomiApontaParaATelaDeInicioAutomatico() {
        val intent = ManufacturerHint.shortcutIntent(VendorSettings.XIAOMI_AUTOSTART)

        assertThat(intent).isNotNull()
        assertThat(intent!!.component?.packageName).isEqualTo("com.miui.securitycenter")
        assertThat(intent.component?.className)
            .isEqualTo("com.miui.permcenter.autostart.AutoStartManagementActivity")
    }

    @Test
    fun oAtalhoDoFabricanteNaoEngoleATelaDeBateriaDoSistema() {
        // O autostart do fabricante não desliga a otimização de bateria: com o botão único,
        // `isBatteryUnrestricted` (e o `batteryOk` da home) ficava falso para sempre nessas
        // marcas, e a tela do sistema não era alcançável por lugar nenhum.
        val xiaomi = ManufacturerGuide.forManufacturer("Xiaomi")

        assertThat(ManufacturerHint.needsSystemBatteryScreen(xiaomi, bateriaLiberada = false))
            .isTrue()
        // Já liberada: o caminho do sistema não tem mais o que fazer.
        assertThat(ManufacturerHint.needsSystemBatteryScreen(xiaomi, bateriaLiberada = true))
            .isFalse()
        // Sem tela própria o botão principal já é a de bateria: não há o que duplicar.
        val samsung = ManufacturerGuide.forManufacturer("Samsung")
        assertThat(ManufacturerHint.needsSystemBatteryScreen(samsung, bateriaLiberada = false))
            .isFalse()
    }

    @Test
    fun todoAtalhoOferecidoTemUmaTelaParaAbrir() {
        // Um atalho sem Intent vira um botão que não faz nada na mão dela.
        VendorSettings.entries
            .filter { it != VendorSettings.NONE }
            .forEach { assertThat(ManufacturerHint.shortcutIntent(it)).isNotNull() }

        assertThat(ManufacturerHint.shortcutIntent(VendorSettings.NONE)).isNull()
    }
}

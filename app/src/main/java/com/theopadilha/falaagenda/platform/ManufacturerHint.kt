package com.theopadilha.falaagenda.platform

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import com.theopadilha.falaagenda.domain.reminder.ManufacturerGuide
import com.theopadilha.falaagenda.domain.reminder.VendorSettings

/**
 * O guia do aparelho que está na mão dela: o fabricante sai daqui, e cada atalho vira a
 * tela de ajustes daquele fabricante.
 *
 * A tela pode não existir (aparelho sem ela, sistema remexido, fabricante que mudou o
 * componente entre versões). O Intent sai do mesmo jeito e quem responde é o
 * [DeviceIntents.open], que devolve se abriu — a tela errada não fecha o app na cara dela.
 */
object ManufacturerHint {
    fun guide(): ManufacturerGuide = ManufacturerGuide.forManufacturer(Build.MANUFACTURER)

    fun shortcutIntent(settings: VendorSettings): Intent? {
        val tela = when (settings) {
            VendorSettings.NONE -> return null
            VendorSettings.XIAOMI_AUTOSTART -> ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            VendorSettings.HUAWEI_STARTUP -> ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            VendorSettings.ONEPLUS_STARTUP -> ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
            VendorSettings.ASUS_AUTOSTART -> ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.MainActivity",
            )
            VendorSettings.VIVO_STARTUP -> ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            VendorSettings.OPPO_STARTUP -> ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
        }
        return Intent().setComponent(tela).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

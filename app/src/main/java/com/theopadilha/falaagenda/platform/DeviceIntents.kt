package com.theopadilha.falaagenda.platform

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object DeviceIntents {
    fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

    fun shareText(text: String, chooserTitle: String = "Enviar"): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    fun shareLink(): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Instale o Fala Agenda no celular: ${AppUpdater.RELEASES_PAGE}")
        }
        return Intent.createChooser(send, "Enviar Fala Agenda")
    }

    fun shareChooser(context: Context, apk: File?): Intent {
        if (apk == null) return shareLink()
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), apk)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Fala Agenda", uri)
            putExtra(Intent.EXTRA_TEXT, "Instale o Fala Agenda no celular: ${AppUpdater.RELEASES_PAGE}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Enviar Fala Agenda").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun copyInstalledApk(context: Context): File {
        val dest = File(AppUpdater.updatesDir(context), "Fala-Agenda.apk")
        File(context.applicationInfo.sourceDir).copyTo(dest, overwrite = true)
        return dest
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun batterySettings(context: Context): Intent {
        val pkg = context.packageName
        return if (!isBatteryUnrestricted(context)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownSources(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun installApk(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

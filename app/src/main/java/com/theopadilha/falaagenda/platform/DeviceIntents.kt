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
import java.io.IOException

object DeviceIntents {
    fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

    /**
     * Abre a tela pedida e diz se deu. Aparelho sem navegador, sem instalador ou sem a tela de
     * ajustes que o fabricante mexeu responde com `ActivityNotFoundException` — que, solta,
     * fecha o app na cara de quem tocou. Quem chama mostra o recado quando devolve `false`.
     */
    fun open(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

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

    /**
     * Copia o APK instalado para um arquivo que ela possa mandar para outra pessoa.
     *
     * A cópia é atômica (temporário + rename): interrompida no meio — o aplicativo morrendo,
     * o disco enchendo, uma leitura sem espaço —, o APK que já estava lá continua inteiro
     * em vez de virar um arquivo truncado que o próximo "Enviar o aplicativo" entrega
     * corrompido para outra pessoa.
     */
    fun copyInstalledApk(context: Context): File {
        val dest = File(AppUpdater.updatesDir(context), "Fala-Agenda.apk")
        writeAtomically(dest) { temp ->
            File(context.applicationInfo.sourceDir).copyTo(temp, overwrite = true)
        }
        return dest
    }

    /**
     * [write] escreve no arquivo temporário; só o rename publica o resultado em [dest]. Quem
     * falhar no meio (ou for cancelado) deixa [dest] como estava.
     */
    internal fun writeAtomically(dest: File, write: (File) -> Unit) {
        val temp = File(dest.parentFile, "${dest.name}.parcial")
        try {
            write(temp)
            // Mesmo diretório, mesmo sistema: o rename substitui o destino de uma vez, sem
            // apagar antes. Se ele não for, é falha da cópia — o antigo fica de pé.
            if (!temp.renameTo(dest)) throw IOException("Não consegui publicar ${dest.name}")
        } finally {
            // Sobrou temporário: é cópia pela metade, e não pode ficar no diretório
            // passando por APK bom.
            temp.delete()
        }
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun batterySettings(context: Context): Intent =
        batterySettingsIntentOrNull(context) ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * A tela de bateria do sistema **quando há o que pedir ali**: com a restrição ligada é o
     * pedido de exceção para o próprio pacote; com ela já desligada não há tela que resolva, e
     * a resposta é `null` — quem chama mostra o recado do fabricante em vez de mandá-la a uma
     * lista onde não há nada para ligar (o mesmo cuidado do `alertFix` para os avisos).
     *
     * O `@SuppressLint("BatteryLife")` fica aqui, e não na [batterySettings], porque é este o
     * Intent que pede a isenção direta — o lint reclama da política da Play, e o app já a usa.
     */
    @SuppressLint("BatteryLife")
    fun batterySettingsIntentOrNull(context: Context): Intent? =
        if (isBatteryUnrestricted(context)) {
            null
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
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

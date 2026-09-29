package com.theopadilha.falaagenda.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.security.MessageDigest

/** Como terminou a conferência da assinatura do APK que veio da release. */
enum class ApkSignatureVerdict {
    /** Mesma certidão do aplicativo instalado. */
    TRUSTED,

    /** Assinado por outra chave: não pode chegar ao instalador. */
    MISMATCH,

    /** O sistema não entregou as certidões: sem como decidir, também não instala. */
    UNKNOWN,
}

/** De onde saem as certidões de assinatura para conferir o APK baixado. */
interface SigningCertificates {
    /** Certidões (SHA-256) do aplicativo instalado; vazio quando o sistema não deu. */
    fun installed(): Set<String>

    /** Certidões (SHA-256) do arquivo no disco; vazio quando não deu para ler. */
    fun archive(apk: File): Set<String>
}

/**
 * De onde saem os números de versão para comparar o APK baixado com o aplicativo instalado.
 * A assinatura prova autoria; quem prova que o arquivo é mais novo é este número.
 */
interface PackageVersions {
    /** `versionCode` do aplicativo instalado; `null` quando o sistema não deu. */
    fun installed(): Long?

    /** `versionCode` do arquivo no disco; `null` quando não deu para ler. */
    fun archive(apk: File): Long?
}

class AndroidPackageVersions(private val context: Context) : PackageVersions {
    override fun installed(): Long? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
            .getOrNull()
            .let(ApkSignature::versionCode)

    override fun archive(apk: File): Long? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            .let(ApkSignature::versionCode)
}

class AndroidSigningCertificates(private val context: Context) : SigningCertificates {
    override fun installed(): Set<String> =
        runCatching { context.packageManager.getPackageInfo(context.packageName, flags()) }
            .getOrNull()
            .let(ApkSignature::certificates)

    override fun archive(apk: File): Set<String> =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags())
            .let(ApkSignature::certificates)

    private fun flags(): Int = signingFlags(Build.VERSION.SDK_INT)
}

/**
 * `GET_SIGNING_CERTIFICATES` só existe da API 28 em diante; antes disso o único jeito de ler
 * a certidão é o `GET_SIGNATURES` depreciado, que é o que a API 26/27 entende.
 */
internal fun signingFlags(sdk: Int): Int =
    if (sdk >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

internal object ApkSignature {
    /** Certidões do pacote em SHA-256; vazio quando o sistema não entregou nenhuma. */
    fun certificates(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfoSigners(info)
        } else {
            legacySigners(info)
        }
        return signers.orEmpty().map { fingerprint(it.toByteArray()) }.toSet()
    }

    /**
     * `PackageInfo.signingInfo` só existe da API 28 em diante — na 26/27 o campo não está na
     * classe do sistema, e lê-lo não devolve `null`: derruba a chamada. Quem responde lá é
     * `signatures`, que é o que a flag antiga pede. Para APK assinado só com o esquema v2 (o de
     * hoje), o `PackageParser` daquelas versões verifica o v2 e copia o signatário para
     * `signatures` — a certidão sai igual, sem passar perto do `signingInfo`.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun signingInfoSigners(info: PackageInfo): Array<Signature>? =
        info.signingInfo?.apkContentsSigners

    /** API 26/27: o único retrato de assinatura que aquelas versões entregam. */
    @Suppress("DEPRECATION")
    private fun legacySigners(info: PackageInfo): Array<Signature>? = info.signatures

    /**
     * `longVersionCode` só existe da API 28 em diante; a 26/27 — piso do app — só tem o
     * `versionCode` antigo, que naquelas versões ainda é o número inteiro que vale.
     * Ler o campo errado não devolve `null`: derruba a chamada.
     */
    fun versionCode(info: PackageInfo?): Long? {
        if (info == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode(info)
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun longVersionCode(info: PackageInfo): Long = info.longVersionCode

    fun fingerprint(certificate: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(certificate).joinToString("") { b -> "%02x".format(b) }
    }

    /** Basta uma certidão em comum: é o mesmo keystore assinando os dois lados. */
    fun verdict(installed: Set<String>, downloaded: Set<String>): ApkSignatureVerdict = when {
        installed.isEmpty() || downloaded.isEmpty() -> ApkSignatureVerdict.UNKNOWN
        downloaded.any { it in installed } -> ApkSignatureVerdict.TRUSTED
        else -> ApkSignatureVerdict.MISMATCH
    }
}

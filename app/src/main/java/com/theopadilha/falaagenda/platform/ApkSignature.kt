package com.theopadilha.falaagenda.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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
        val signers = info.signingInfo?.apkContentsSigners ?: legacySignatures(info)
        return signers.orEmpty().map { fingerprint(it.toByteArray()) }.toSet()
    }

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

    @Suppress("DEPRECATION")
    private fun legacySignatures(info: PackageInfo) = info.signatures
}

package com.theopadilha.falaagenda.platform

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File

/**
 * A conferência de assinatura é a única barreira entre uma release trocada no caminho e o
 * instalador do aparelho; cada ramo dela precisa de prova.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class ApkSignatureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun certidaoIgualEhConfiavel() {
        val certidao = ApkSignature.fingerprint(CERTIDAO)

        assertThat(ApkSignature.verdict(setOf(certidao), setOf(certidao)))
            .isEqualTo(ApkSignatureVerdict.TRUSTED)
    }

    @Test
    fun apkAssinadoComOutraChaveEhRecusado() {
        val instalada = ApkSignature.fingerprint(CERTIDAO)
        val outra = ApkSignature.fingerprint(OUTRA_CERTIDAO)

        assertThat(ApkSignature.verdict(setOf(instalada), setOf(outra)))
            .isEqualTo(ApkSignatureVerdict.MISMATCH)
    }

    @Test
    fun umaCertidaoEmComumBasta() {
        val instalada = ApkSignature.fingerprint(CERTIDAO)
        val outra = ApkSignature.fingerprint(OUTRA_CERTIDAO)

        assertThat(ApkSignature.verdict(setOf(instalada, outra), setOf(outra)))
            .isEqualTo(ApkSignatureVerdict.TRUSTED)
    }

    @Test
    fun sistemaSemCertidaoNaoDecide() {
        val certidao = ApkSignature.fingerprint(CERTIDAO)

        assertThat(ApkSignature.verdict(emptySet(), setOf(certidao)))
            .isEqualTo(ApkSignatureVerdict.UNKNOWN)
        assertThat(ApkSignature.verdict(setOf(certidao), emptySet()))
            .isEqualTo(ApkSignatureVerdict.UNKNOWN)
        assertThat(ApkSignature.verdict(emptySet(), emptySet()))
            .isEqualTo(ApkSignatureVerdict.UNKNOWN)
    }

    @Test
    fun certidaoViraSha256EmHex() {
        // Vetor conhecido: SHA-256 da entrada vazia.
        assertThat(ApkSignature.fingerprint(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun flagsDeAssinaturaSeguemAVersaoDaApi() {
        @Suppress("DEPRECATION")
        assertThat(signingFlags(26)).isEqualTo(PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        assertThat(signingFlags(27)).isEqualTo(PackageManager.GET_SIGNATURES)
        assertThat(signingFlags(28)).isEqualTo(PackageManager.GET_SIGNING_CERTIFICATES)
        assertThat(signingFlags(34)).isEqualTo(PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Test
    fun certidaoDoApkSaiDoSigningInfo() {
        // Os dois campos preenchidos, com chaves diferentes: na 28+ quem responde é o
        // signingInfo, e só ele.
        val pacote = PackageInfo().apply {
            signingInfo = signingInfoCom(CERTIDAO)
            signatures = arrayOf(Signature(OUTRA_CERTIDAO))
        }

        assertThat(ApkSignature.certificates(pacote))
            .containsExactly(ApkSignature.fingerprint(CERTIDAO))
    }

    @Test
    fun naApi28MaisAssinaturaAntigaNaoEhPlanoB() {
        // Sem signingInfo não se decide, mesmo com o campo antigo preenchido: não decidir é não
        // instalar, que é o lado seguro.
        val pacote = PackageInfo().apply { signatures = arrayOf(Signature(CERTIDAO)) }

        assertThat(ApkSignature.certificates(pacote)).isEmpty()
    }

    /**
     * A 26/27 é o piso do app e não tem `PackageInfo.signingInfo` — no aparelho, mexer nesse
     * campo derruba a conferência em vez de devolver null. Aqui o teste roda na 27 de verdade
     * para provar que esse caminho passa longe dele.
     */
    @Test
    @Config(sdk = [27])
    fun certidaoDoApkNaApi27SaiDasAssinaturasAntigas() {
        val pacote = PackageInfo().apply { signatures = arrayOf(Signature(CERTIDAO)) }

        assertThat(ApkSignature.certificates(pacote))
            .containsExactly(ApkSignature.fingerprint(CERTIDAO))
    }

    @Test
    @Config(sdk = [27])
    fun certidaoDoAppInstaladoNaApi27SaiDasAssinaturasAntigas() {
        val certidoes = AndroidSigningCertificates(context)
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .apply { signatures = arrayOf(Signature(CERTIDAO)) }

        assertThat(certidoes.installed())
            .containsExactly(ApkSignature.fingerprint(CERTIDAO))
    }

    @Test
    fun pacoteSemCertidaoNaoInventaCertidao() {
        assertThat(ApkSignature.certificates(null)).isEmpty()
        assertThat(ApkSignature.certificates(PackageInfo())).isEmpty()
    }

    @Test
    fun certidaoDoArquivoBaixadoVemDoPackageManager() {
        val arquivo = File(context.cacheDir, "baixado.apk").apply { writeBytes(ByteArray(16)) }
        val certidoes = AndroidSigningCertificates(context)

        assertThat(certidoes.archive(arquivo)).isEmpty()

        shadowOf(context.packageManager).setPackageArchiveInfo(
            arquivo.absolutePath,
            PackageInfo().apply { signingInfo = signingInfoCom(CERTIDAO) },
        )

        assertThat(certidoes.archive(arquivo))
            .containsExactly(ApkSignature.fingerprint(CERTIDAO))
    }

    @Test
    fun certidaoDoAppInstaladoVemDoPacoteAtual() {
        val certidoes = AndroidSigningCertificates(context)
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .apply { signingInfo = signingInfoCom(CERTIDAO) }

        assertThat(certidoes.installed())
            .containsExactly(ApkSignature.fingerprint(CERTIDAO))
    }

    private fun signingInfoCom(certidao: ByteArray): SigningInfo =
        SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(certidao))) }

    private companion object {
        val CERTIDAO = "chave-do-app-instalado".toByteArray()
        val OUTRA_CERTIDAO = "chave-de-quem-trocou-o-apk".toByteArray()
    }
}

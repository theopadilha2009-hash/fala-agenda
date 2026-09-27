package com.theopadilha.falaagenda.ui.update

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.platform.UpdateCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UpdateSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** O escopo que no aplicativo vive no processo. Aqui, sem confinamento, cada passo
     *  acontece na hora e o teste não depende de relógio nem de tela. */
    private fun escopoDoProcesso() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun achou(
        newer: Boolean = true,
        apkUrl: String? = "https://github.com/x/fala-agenda/releases/download/v0.6.0/app-release.apk",
        sha256Url: String? = "https://github.com/x/fala-agenda/releases/download/v0.6.0/apk.sha256",
    ) = UpdateCheck(
        local = "0.5.2",
        remote = "0.6.0",
        apkUrl = apkUrl,
        sha256Url = sha256Url,
        newer = newer,
        message = "Tem versão nova: 0.6.0. A sua é 0.5.2.",
    )

    @Test
    fun downloadTerminaMesmoSemTelaAberta() {
        val apk = temp.newFile("Fala-Agenda-update.apk")
        val gate = CompletableDeferred<Unit>()
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { achou() },
            fetch = { _, _ -> gate.await(); apk },
        )

        session.start()
        session.downloadNow()

        // A tela pode fechar agora: o download não pertence a ela.
        assertThat(session.state.value.downloading).isTrue()

        gate.complete(Unit)

        // Quem voltar para a tela encontra o instalador pronto, sem baixar de novo.
        assertThat(session.state.value.downloading).isFalse()
        assertThat(session.state.value.apk).isEqualTo(apk)
    }

    @Test
    fun voltarParaATelaNaoRefazAProcuraNemPerdeOBaixado() {
        val apk = temp.newFile("Fala-Agenda-update.apk")
        var procuras = 0
        var downloads = 0
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { procuras++; achou() },
            fetch = { _, _ -> downloads++; apk },
        )

        session.start()
        session.downloadNow()

        // Sair e voltar (ou girar o aparelho) recria a tela: ela chama start() de novo.
        session.start()
        session.start()

        assertThat(procuras).isEqualTo(1)
        assertThat(downloads).isEqualTo(1)
        assertThat(session.state.value.apk).isEqualTo(apk)
    }

    @Test
    fun girarDuranteODownloadNaoRecomecaNemApagaAbarra() {
        val gate = CompletableDeferred<Unit>()
        var downloads = 0
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { achou() },
            fetch = { _, _ -> downloads++; gate.await(); temp.newFile("apk") },
        )

        session.start()
        session.downloadNow()
        assertThat(session.state.value.downloading).isTrue()

        session.start()
        session.refresh()

        assertThat(downloads).isEqualTo(1)
        assertThat(session.state.value.downloading).isTrue()

        gate.complete(Unit)
        assertThat(session.state.value.downloading).isFalse()
    }

    @Test
    fun toqueDuploNaoBaixaDuasVezes() {
        val gate = CompletableDeferred<Unit>()
        var downloads = 0
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { achou() },
            fetch = { _, _ -> downloads++; gate.await(); temp.newFile("apk") },
        )

        session.start()
        session.downloadNow()
        session.downloadNow()

        assertThat(downloads).isEqualTo(1)
        gate.complete(Unit)
    }

    @Test
    fun baixaDaMesmaUrlQueAProcuraEncontrou() {
        var pedido: Pair<String, String?>? = null
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { achou() },
            fetch = { url, sha -> pedido = url to sha; temp.newFile("apk") },
        )

        session.start()
        session.downloadNow()

        assertThat(pedido?.first).isEqualTo(achou().apkUrl)
        assertThat(pedido?.second).isEqualTo(achou().sha256Url)
    }

    @Test
    fun downloadQueFalhaExplicaEMantemOPoderBaixarDeNovo() {
        val apk = temp.newFile("Fala-Agenda-update.apk")
        var falhar = true
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { achou() },
            fetch = { _, _ -> if (falhar) throw IOException("Sem internet agora.") else apk },
        )

        session.start()
        session.downloadNow()

        assertThat(session.state.value.message).isEqualTo("Sem internet agora.")
        assertThat(session.state.value.apk).isNull()
        assertThat(session.state.value.downloading).isFalse()

        falhar = false
        session.downloadNow()

        assertThat(session.state.value.apk).isEqualTo(apk)
        assertThat(session.state.value.message).isNull()
    }

    @Test
    fun procuraQueFalhaExplicaEDeixaTentarDeNovo() {
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { throw IOException("Sem internet agora.") },
            fetch = { _, _ -> temp.newFile("apk") },
        )

        session.start()

        assertThat(session.state.value.checking).isFalse()
        assertThat(session.state.value.message).isEqualTo("Sem internet agora.")
        assertThat(session.state.value.info).isNull()
    }

    @Test
    fun instaladorQueSumiuVoltaParaBaixarComExplicacao() {
        val apk = temp.newFile("Fala-Agenda-update.apk")
        var downloads = 0
        val session = UpdateSession(
            scope = escopoDoProcesso(),
            lookUp = { achou() },
            fetch = { _, _ -> downloads++; apk },
        )

        session.start()
        session.downloadNow()
        assertThat(session.installStep(canInstall = true)).isEqualTo(InstallStep.OpenInstaller(apk))

        // O Android limpou o cache (celular cheio) ou um familiar tocou em "Limpar cache".
        apk.delete()

        assertThat(session.installStep(canInstall = true)).isEqualTo(InstallStep.ApkGone)
        assertThat(session.state.value.apk).isNull()
        assertThat(session.state.value.message).contains("\"Baixar e instalar\"")
        // A tela voltou a oferecer o download, em vez de travar no "Instalar agora".
        assertThat(session.state.value.info?.newer).isTrue()
        assertThat(downloads).isEqualTo(1)
    }

    @Test
    fun semArquivoNemMandaElaLiberarPermissao() {
        val sumiu = File(temp.root, "nao-existe.apk")
        assertThat(installStepFor(sumiu, canInstall = false)).isEqualTo(InstallStep.ApkGone)
        assertThat(installStepFor(null, canInstall = false)).isEqualTo(InstallStep.ApkGone)
    }

    @Test
    fun arquivoPresenteSemPermissaoPedeALiberacao() {
        val apk = temp.newFile("Fala-Agenda-update.apk")
        assertThat(installStepFor(apk, canInstall = false)).isEqualTo(InstallStep.AllowInstall)
        assertThat(installStepFor(apk, canInstall = true)).isEqualTo(InstallStep.OpenInstaller(apk))
    }
}

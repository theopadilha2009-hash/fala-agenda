package com.theopadilha.falaagenda.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

/**
 * O caminho do auto-update é o que mais quebrou em produção e é o que entrega código ao
 * aparelho: aqui ele roda de ponta a ponta contra um servidor local, sem rede de verdade.
 *
 * O DNS aponta para o servidor local e a URL é `http://github.com:<porta>` porque a allowlist
 * de host do AppUpdater recusa qualquer coisa fora do GitHub — que é o que se espera dela.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class AppUpdaterFluxoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun subirServidor() {
        server = MockWebServer()
        server.start()
        arquivoBaixado().delete()
    }

    @After
    fun derrubarServidor() {
        server.shutdown()
    }

    private fun updater(certidoes: SigningCertificates): AppUpdater = AppUpdater(
        context = context,
        http = OkHttpClient.Builder()
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                },
            )
            .build(),
        certificates = certidoes,
    )

    private fun url(caminho: String): String = "http://github.com:${server.port}$caminho"

    private val mesmaChave = CertidoesFalsas(instalada = setOf("aa"), baixada = setOf("aa"))

    private fun outraChave() = CertidoesFalsas(instalada = setOf("aa"), baixada = setOf("bb"))

    @Test
    fun versaoPublicadaViraAvisoDeAtualizacao() {
        val publicada = versaoMaisNovaQueAInstalada()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v$publicada",
                  "assets": [
                    { "name": "app-release.apk", "browser_download_url": "${url("/app-release.apk")}" },
                    { "name": "apk.sha256", "browser_download_url": "${url("/apk.sha256")}" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val check = updater(mesmaChave).check(url("/latest"))

        assertThat(check.newer).isTrue()
        assertThat(check.remote).isEqualTo(publicada)
        assertThat(check.apkUrl).isEqualTo(url("/app-release.apk"))
    }

    /**
     * O caso que o bump de versionName criou de verdade: publicado igual ao instalado não é
     * novidade, e a tela não pode oferecer atualização nenhuma.
     */
    @Test
    fun versaoPublicadaIgualAInstaladaNaoViraAviso() {
        val instalada = AppUpdater.localVersion()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v$instalada",
                  "assets": [
                    { "name": "app-release.apk", "browser_download_url": "${url("/app-release.apk")}" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val check = updater(mesmaChave).check(url("/latest"))

        assertThat(check.newer).isFalse()
        assertThat(check.remote).isEqualTo(instalada)
        assertThat(check.message).contains("última versão")
    }

    @Test
    fun semVersaoPublicadaNaoEhErro() {
        server.enqueue(MockResponse().setResponseCode(404))

        val check = updater(mesmaChave).check(url("/latest"))

        assertThat(check.newer).isFalse()
        assertThat(check.message).contains("não há uma versão publicada")
    }

    @Test
    fun githubForaDoArViraRecadoNaTela() {
        server.enqueue(MockResponse().setResponseCode(500))

        val erro = falhaDe { updater(mesmaChave).check(url("/latest")) }

        assertThat(erro.message).contains("500")
    }

    @Test
    fun baixarApkComSomaConfereEEntregaOArquivo() {
        val apk = "conteudo-do-apk"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val arquivo = updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256"))

        assertThat(arquivo.exists()).isTrue()
        assertThat(arquivo.readText()).isEqualTo(apk)
    }

    @Test
    fun apkDiferenteDoPublicadoEhApagado() {
        server.enqueue(MockResponse().setBody("apk-que-nao-e-o-publicado"))
        server.enqueue(MockResponse().setBody("${sha256("outro".toByteArray())}  app-release.apk\n"))

        falhaDe { updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256")) }

        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun apkDeOutraChaveNaoChegaAoInstalador() {
        server.enqueue(MockResponse().setBody("apk-da-release-invasora"))

        val erro = falhaDe { updater(outraChave()).download(url("/app-release.apk")) }

        assertThat(erro.message).isEqualTo(AppUpdater.ASSINATURA_DIFERENTE)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun semCertidaoParaConferirNaoInstala() {
        val semCertidao = CertidoesFalsas(instalada = emptySet(), baixada = emptySet())
        server.enqueue(MockResponse().setBody("apk-sem-assinatura-legivel"))

        val erro = falhaDe { updater(semCertidao).download(url("/app-release.apk")) }

        assertThat(erro.message).isEqualTo(AppUpdater.ASSINATURA_ILEGIVEL)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun apkSemArquivoDeSomaAindaPassaPelaAssinatura() {
        server.enqueue(MockResponse().setBody("apk-sem-sha256-publicado"))

        val erro = falhaDe {
            updater(outraChave()).download(url("/app-release.apk"), sha256Url = null)
        }

        assertThat(erro.message).isEqualTo(AppUpdater.ASSINATURA_DIFERENTE)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun apkJáBaixadoDaMesmaVersaoNaoEhBaixadoDeNovo() {
        val apk = "apk-da-0.6.0"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val primeiro = updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256"))
        val chamadas = server.requestCount
        val segundo = updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256"))

        assertThat(segundo).isEqualTo(primeiro)
        assertThat(server.requestCount).isEqualTo(chamadas)
    }

    @Test
    fun versaoDiferenteNaoReaproveitaOArquivoAntigo() {
        server.enqueue(MockResponse().setBody("apk-da-0.6.0"))
        val antigo = updater(mesmaChave).download(url("/v0.6.0/app-release.apk"))
        val conteudoAntigo = antigo.readText()
        server.enqueue(MockResponse().setBody("apk-da-0.7.0"))

        val novo = updater(mesmaChave).download(url("/v0.7.0/app-release.apk"))

        assertThat(conteudoAntigo).isEqualTo("apk-da-0.6.0")
        assertThat(novo.readText()).isEqualTo("apk-da-0.7.0")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun apkPelaMetadeNaoSobraNoCache() {
        server.enqueue(
            MockResponse().setBody("apk-curto").setHeader("Content-Length", "9999"),
        )

        falhaDe { updater(mesmaChave).download(url("/app-release.apk")) }

        assertThat(arquivoBaixado().exists()).isFalse()
        assertThat(File(AppUpdater.updatesDir(context), "Fala-Agenda-update.source").exists())
            .isFalse()
    }

    @Test
    fun fonteForaDoGithubNemEhChamada() {
        val erro = falhaDe { updater(mesmaChave).download("https://espelho.example/app.apk") }

        assertThat(erro.message).contains("Fonte de atualização inválida")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun apkVazioNaoViraInstalador() {
        server.enqueue(MockResponse().setBody(""))

        val erro = falhaDe { updater(mesmaChave).download(url("/app-release.apk")) }

        assertThat(erro.message).contains("vazio")
    }

    private fun arquivoBaixado(): File =
        File(AppUpdater.updatesDir(context), "Fala-Agenda-update.apk")

    /**
     * A release do fixture precisa ser mais nova que a instalada. Cravar "0.6.0" funcionou até o
     * versionName do app subir para 0.6.0 nesta auditoria: aí virou empate e o teste apodreceu.
     * Derivando do que o app reporta, ele continua valendo no próximo bump.
     */
    private fun versaoMaisNovaQueAInstalada(): String =
        AppUpdater.localVersion()
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
            .toMutableList()
            .also { it[it.lastIndex] = it.last() + 1 }
            .joinToString(".")

    private fun falhaDe(bloco: () -> Unit): Throwable {
        val erro = runCatching(bloco).exceptionOrNull()
        assertThat(erro).isNotNull()
        return erro!!
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class CertidoesFalsas(
        private val instalada: Set<String>,
        private val baixada: Set<String>,
    ) : SigningCertificates {
        override fun installed(): Set<String> = instalada
        override fun archive(apk: File): Set<String> = baixada
    }
}

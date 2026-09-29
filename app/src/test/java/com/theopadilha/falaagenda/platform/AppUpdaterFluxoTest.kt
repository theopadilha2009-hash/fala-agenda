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

    private fun updater(
        certidoes: SigningCertificates,
        versoes: PackageVersions = VersoesFalsas(instalada = null, baixada = null),
    ): AppUpdater = AppUpdater(
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
        versions = versoes,
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
        val apk = "apk-da-release-invasora"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val erro = falhaDe {
            updater(outraChave()).download(url("/app-release.apk"), url("/apk.sha256"))
        }

        assertThat(erro.message).isEqualTo(AppUpdater.ASSINATURA_DIFERENTE)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun semCertidaoParaConferirNaoInstala() {
        val semCertidao = CertidoesFalsas(instalada = emptySet(), baixada = emptySet())
        val apk = "apk-sem-assinatura-legivel"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val erro = falhaDe {
            updater(semCertidao).download(url("/app-release.apk"), url("/apk.sha256"))
        }

        assertThat(erro.message).isEqualTo(AppUpdater.ASSINATURA_ILEGIVEL)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    /**
     * Antes esta release baixava com a assinatura como única barreira. Integridade agora é
     * obrigatória: sem o `.sha256` publicado não se confere o arquivo, e recusar antes de
     * baixar 20 MB é mais barato para quem paga os dados do aparelho.
     */
    @Test
    fun releaseSemArquivoDeSomaNaoBaixaNada() {
        server.enqueue(MockResponse().setBody("apk-sem-sha256-publicado"))

        val erro = falhaDe {
            updater(mesmaChave).download(url("/app-release.apk"), sha256Url = null)
        }

        assertThat(erro.message).isEqualTo(AppUpdater.SOMA_AUSENTE)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun somaPublicadaEmBrancoTambemEhRecusada() {
        server.enqueue(MockResponse().setBody("apk-com-url-de-soma-vazia"))

        val erro = falhaDe {
            updater(mesmaChave).download(url("/app-release.apk"), sha256Url = "   ")
        }

        assertThat(erro.message).isEqualTo(AppUpdater.SOMA_AUSENTE)
        assertThat(server.requestCount).isEqualTo(0)
    }

    /**
     * `followRedirects(true)` resolve o 302 sozinho: a allowlist de host da URL inicial não
     * vale para o destino. Quem controla a rede poderia apontar o redirect para o próprio
     * servidor e entregar outro APK (a assinatura ainda barraria, mas o arquivo não deveria
     * nem chegar a ser baixado de lá).
     */
    @Test
    fun redirectParaForaDoGithubNaoEhSeguido() {
        // O destino aponta para o servidor de verdade (mesma porta) de propósito: o guarda roda
        // depois do ConnectInterceptor, então um endereço que nem aceita conexão falharia antes
        // dele e o teste passaria a medir outra coisa.
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader(
                "Location",
                "http://espelho.example:${server.port}/app-release.apk",
            ),
        )
        server.enqueue(MockResponse().setBody("apk-do-espelho"))
        server.enqueue(MockResponse().setBody("${sha256("apk-do-espelho".toByteArray())}  app-release.apk\n"))

        val erro = falhaDe {
            updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256"))
        }

        assertThat(erro.message).isEqualTo(AppUpdater.DESTINO_NAO_CONFIAVEL)
        // O segundo salto nem chegou ao servidor: foram dois destinos, só um pedido.
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    /**
     * O GitHub manda o instalador para `release-assets.githubusercontent.com` (302 conferido
     * contra a API em 29/09/2026). Recusar redirect em vez de validar o destino quebraria
     * toda atualização — este teste é o que impede a correção de virar excesso de zelo.
     */
    @Test
    fun redirectParaOHostDeAssetsDoGithubEhSeguido() {
        val apk = "apk-que-veio-do-cdn-do-github"
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader(
                "Location",
                "http://release-assets.githubusercontent.com:${server.port}/app-release.apk",
            ),
        )
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val arquivo = updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256"))

        assertThat(arquivo.readText()).isEqualTo(apk)
    }

    /**
     * O APK guardado da rodada anterior era devolvido sem passar pela checagem de versão, que
     * nasceu depois dele: um "Instalar agora" a partir do cache podia oferecer um rebaixamento,
     * e quem barrava era o instalador do sistema, com o erro genérico dele no lugar do recado
     * do aplicativo.
     */
    @Test
    fun apkEmCacheMaisAntigoQueOInstaladoNaoEhEntregue() {
        val apk = "apk-da-versao-que-ficou-no-cache"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))
        val guardado = updater(mesmaChave, VersoesFalsas(instalada = 7L, baixada = 8L))
            .download(url("/app-release.apk"), url("/apk.sha256"))
        val chamadas = server.requestCount
        assertThat(guardado.exists()).isTrue()

        // Agora o aparelho está na 9 e o arquivo guardado é o da 8: o cache não pode chegar ao
        // instalador só porque veio de uma rodada mais antiga.
        val erro = falhaDe {
            updater(mesmaChave, VersoesFalsas(instalada = 9L, baixada = 8L))
                .download(url("/app-release.apk"), url("/apk.sha256"))
        }

        assertThat(erro.message).isEqualTo(AppUpdater.VERSAO_NAO_E_MAIS_NOVA)
        assertThat(guardado.exists()).isFalse()
        assertThat(server.requestCount).isEqualTo(chamadas)
    }

    /**
     * O interceptor do destino está no mesmo cliente HTTP que a checagem de release usa: o
     * recado dele pode aparecer na tela em que arquivo nenhum existia e download nenhum tinha
     * começado, e afirmar que apagou o arquivo ali é mentira.
     */
    @Test
    fun recusaDeDestinoNaChecagemNaoAfirmaQueApagouArquivo() {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader(
                "Location",
                "http://espelho.example:${server.port}/latest",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"tag_name":"v9.9.9","assets":[]}"""))

        val erro = falhaDe { updater(mesmaChave).check(url("/latest")) }

        assertThat(erro.message).isEqualTo(AppUpdater.DESTINO_NAO_CONFIAVEL)
        assertThat(erro.message.orEmpty()).doesNotContain("Apaguei")
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    /**
     * A release nova traz, dentro dela, um APK legitimamente assinado — só que da versão 6,
     * enquanto o aparelho já está na 7. Assinatura e soma passam; é o número da versão que
     * precisa barrar, senão ela "atualiza" para trás.
     */
    @Test
    fun instaladorMaisAntigoQueOInstaladoNaoChegaAoInstalador() {
        val apk = "apk-legitimo-da-versao-6"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val erro = falhaDe {
            updater(mesmaChave, VersoesFalsas(instalada = 7L, baixada = 6L))
                .download(url("/app-release.apk"), url("/apk.sha256"))
        }

        assertThat(erro.message).isEqualTo(AppUpdater.VERSAO_NAO_E_MAIS_NOVA)
        assertThat(arquivoBaixado().exists()).isFalse()
    }

    @Test
    fun mesmaVersaoJaInstaladaTambemEhRecusada() {
        val apk = "apk-da-versao-que-ja-esta-no-aparelho"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val erro = falhaDe {
            updater(mesmaChave, VersoesFalsas(instalada = 7L, baixada = 7L))
                .download(url("/app-release.apk"), url("/apk.sha256"))
        }

        assertThat(erro.message).isEqualTo(AppUpdater.VERSAO_NAO_E_MAIS_NOVA)
    }

    @Test
    fun instaladorMaisNovoQueOInstaladoPassa() {
        val apk = "apk-legitimo-da-versao-8"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val arquivo = updater(mesmaChave, VersoesFalsas(instalada = 7L, baixada = 8L))
            .download(url("/app-release.apk"), url("/apk.sha256"))

        assertThat(arquivo.readText()).isEqualTo(apk)
    }

    @Test
    fun semVersaoLegivelAAtualizacaoNaoEhBarrada() {
        // Os dois lados vêm do mesmo PackageManager que a assinatura já consultou; sem número
        // legível não há como afirmar rebaixamento, e a assinatura segue sendo a barreira.
        val apk = "apk-sem-versao-legivel"
        server.enqueue(MockResponse().setBody(apk))
        server.enqueue(MockResponse().setBody("${sha256(apk.toByteArray())}  app-release.apk\n"))

        val arquivo = updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256"))

        assertThat(arquivo.readText()).isEqualTo(apk)
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
        server.enqueue(MockResponse().setBody("${sha256("apk-da-0.6.0".toByteArray())}  app-release.apk\n"))
        val antigo = updater(mesmaChave)
            .download(url("/v0.6.0/app-release.apk"), url("/v0.6.0/apk.sha256"))
        val conteudoAntigo = antigo.readText()
        server.enqueue(MockResponse().setBody("apk-da-0.7.0"))
        server.enqueue(MockResponse().setBody("${sha256("apk-da-0.7.0".toByteArray())}  app-release.apk\n"))

        val novo = updater(mesmaChave)
            .download(url("/v0.7.0/app-release.apk"), url("/v0.7.0/apk.sha256"))

        assertThat(conteudoAntigo).isEqualTo("apk-da-0.6.0")
        assertThat(novo.readText()).isEqualTo("apk-da-0.7.0")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test
    fun apkPelaMetadeNaoSobraNoCache() {
        server.enqueue(
            MockResponse().setBody("apk-curto").setHeader("Content-Length", "9999"),
        )
        server.enqueue(MockResponse().setBody("${sha256("apk-curto".toByteArray())}  app-release.apk\n"))

        falhaDe { updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256")) }

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
        server.enqueue(MockResponse().setBody("${sha256(ByteArray(0))}  app-release.apk\n"))

        val erro = falhaDe { updater(mesmaChave).download(url("/app-release.apk"), url("/apk.sha256")) }

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

    private class VersoesFalsas(
        private val instalada: Long?,
        private val baixada: Long?,
    ) : PackageVersions {
        override fun installed(): Long? = instalada
        override fun archive(apk: File): Long? = baixada
    }
}

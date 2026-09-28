package com.theopadilha.falaagenda.speech

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O modelo chega pela rede e passa a ser o ouvido do app: arquivo trocado no caminho
 * não pode virar isso. Soma, allowlist e nada pela metade — as mesmas regras do
 * instalador de APK, que é onde elas já moram.
 *
 * A URL é `http://alphacephei.com:<porta>` com o DNS apontando para o servidor local,
 * para a allowlist de verdade ficar no caminho em vez de ser substituída no teste.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class OfflineModelInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before
    fun subirServidor() {
        server = MockWebServer()
        server.start()
        VoskModel.dir(context).deleteRecursively()
    }

    @After
    fun derrubarServidor() {
        server.shutdown()
    }

    @Test
    fun instalaOModeloConferido() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))

        val instalou = installer().installIfNeeded(url(), sha256(zip))

        assertThat(instalou).isTrue()
        assertThat(VoskModel.isInstalled(context)).isTrue()
        assertThat(File(VoskModel.dir(context), "final.mdl").isFile).isTrue()
        assertThat(File(VoskModel.dir(context), "mfcc.conf").isFile).isTrue()
    }

    @Test
    fun somaDiferenteNaoInstala() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))

        val instalou = installer().installIfNeeded(url(), "0".repeat(64))

        assertThat(instalou).isFalse()
        assertThat(VoskModel.isInstalled(context)).isFalse()
    }

    @Test
    fun modeloIncompletoNaoPassaPorInstalado() {
        val zip = modeloZip(sem = "mfcc.conf")
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))

        val instalou = installer().installIfNeeded(url(), sha256(zip))

        assertThat(instalou).isFalse()
        assertThat(VoskModel.isInstalled(context)).isFalse()
    }

    @Test
    fun fonteForaDaAllowlistNemEhBuscada() {
        val instalou = installer().installIfNeeded("https://exemplo.com/modelo.zip", "0".repeat(64))

        assertThat(instalou).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun modeloJaInstaladoNemEhBaixadoDeNovo() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        assertThat(installer().installIfNeeded(url(), sha256(zip))).isTrue()

        assertThat(installer().installIfNeeded(url(), sha256(zip))).isTrue()

        // Uma requisição só: a segunda passada achou o modelo no lugar.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun zipComCaminhoParaForaNaoEscreveFora() {
        val zip = modeloZip(extra = "../escapou.txt" to "x")
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))

        val instalou = installer().installIfNeeded(url(), sha256(zip))

        assertThat(instalou).isFalse()
        assertThat(File(context.filesDir.parentFile, "escapou.txt").exists()).isFalse()
    }

    @Test
    fun allowlistSoAceitaAFonteOficial() {
        assertThat(OfflineModelInstaller.allowedSource(OfflineModelInstaller.SOURCE_URL)).isTrue()
        assertThat(OfflineModelInstaller.allowedSource("https://github.com/x/y.zip")).isFalse()
        assertThat(
            OfflineModelInstaller.allowedSource("https://alphacephei.com.outro.com/x.zip"),
        ).isFalse()
    }

    private fun url(): String = "http://alphacephei.com:${server.port}/modelo.zip"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installer(): OfflineModelInstaller = OfflineModelInstaller(
        context = context,
        http = OkHttpClient.Builder()
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        listOf(InetAddress.getByName("127.0.0.1"))
                },
            )
            .build(),
    )

    private fun modeloZip(
        sem: String? = null,
        extra: Pair<String, String>? = null,
    ): ByteArray {
        val saida = ByteArrayOutputStream()
        ZipOutputStream(saida).use { zip ->
            listOf(
                "final.mdl" to "peso",
                "mfcc.conf" to "--sample-frequency=16000",
            ).filter { it.first != sem }.forEach { (nome, conteudo) ->
                zip.putNextEntry(ZipEntry("${VoskModel.DIR_NAME}/$nome"))
                zip.write(conteudo.toByteArray())
                zip.closeEntry()
            }
            extra?.let { (nome, conteudo) ->
                zip.putNextEntry(ZipEntry(nome))
                zip.write(conteudo.toByteArray())
                zip.closeEntry()
            }
        }
        return saida.toByteArray()
    }
}

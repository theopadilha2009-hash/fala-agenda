package com.theopadilha.falaagenda.speech

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

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
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineModelInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    /** A etiqueta com que o instalador conta o que deu errado. */
    private val TAG = "FalaAgendaOffline"

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

    /**
     * O download não olha o tipo de rede. O desperdício que ele tinha era outro — 31 MB
     * a cada abertura do app —, e esse o gatilho por pedido de voz resolveu. Barrar a
     * rede medida custaria o recurso inteiro num celular que só tem dados móveis: a fala
     * offline nunca chegaria no aparelho dela.
     */
    @Test
    fun oPedidoNaoConsultaARede() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))

        val pedido = installer(contextoQueNaoTemRede())
            .request(CoroutineScope(Dispatchers.Unconfined), url(), sha256(zip))

        assertThat(pedido).isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(VoskModel.isInstalled(context)).isTrue()
    }

    @Test
    fun pedidoComDownloadEmCursoNaoBaixaDeNovo() {
        val scheduler = TestCoroutineScheduler()
        val escopo = CoroutineScope(StandardTestDispatcher(scheduler))
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()

        val primeiro = instalador.request(escopo, url(), sha256(zip))
        val segundo = instalador.request(escopo, url(), sha256(zip))

        assertThat(segundo).isNull()
        assertThat(primeiro).isNotNull()

        scheduler.advanceUntilIdle()
        assertThat(VoskModel.isInstalled(context)).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    /**
     * A guarda que sustenta `oPedidoNaoConsultaARede` só vale se estourar por qualquer
     * caminho. O `applicationContext` de um `ContextWrapper` é o contexto de verdade, então
     * um gate reescrito como `applicationContext.getSystemService(CONNECTIVITY_SERVICE)`
     * passaria verde com a consulta de volta no lugar.
     */
    @Test
    fun aGuardaDoTesteEstouraPorQualquerCaminho() {
        val guarda = contextoQueNaoTemRede()

        assertThrows(IllegalStateException::class.java) {
            guarda.getSystemService(Context.CONNECTIVITY_SERVICE)
        }
        assertThrows(IllegalStateException::class.java) {
            guarda.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
        }
    }

    /**
     * O escopo pode estar morto no instante do `launch`: aí o corpo da corrotina nem começa,
     * o `finally` não roda, e a trava — que é do processo, não da escuta — fica presa para o
     * resto da vida dele. Todo pedido seguinte devolveria `null` sem dizer nada, e o modelo
     * nunca chegaria.
     */
    @Test
    fun pedidoEmEscopoCanceladoNaoTravaOPedidoSeguinte() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()
        val morto = CoroutineScope(Job() + Dispatchers.Unconfined).apply { cancel() }

        val perdido = instalador.request(morto, url(), sha256(zip))

        // O pedido foi aceito — quem chamou não tem como saber que nasceu morto.
        assertThat(perdido).isNotNull()
        assertThat(server.requestCount).isEqualTo(0)

        val seguinte = instalador.request(CoroutineScope(Dispatchers.Unconfined), url(), sha256(zip))

        assertThat(seguinte).isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(VoskModel.isInstalled(context)).isTrue()
    }

    @Test
    fun pedidoBaixaOModeloQuandoNaoTemNadaInstalado() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))

        val pedido = installer()
            .request(CoroutineScope(Dispatchers.Unconfined), url(), sha256(zip))

        assertThat(pedido).isNotNull()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(VoskModel.isInstalled(context)).isTrue()
    }

    /** O modelo que nunca chega tem que contar por quê: sem log, ninguém sabe. */
    @Test
    fun falhaDoDownloadFicaNoLogComOMotivo() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        ShadowLog.clear()

        val instalou = installer().installIfNeeded(url(), "0".repeat(64))

        assertThat(instalou).isFalse()
        val avisos = ShadowLog.getLogs().filter { it.type == Log.WARN && it.tag == TAG }
        assertThat(avisos).hasSize(1)
        assertThat(avisos.first().throwable).isNotNull()
    }

    private fun url(): String = "http://alphacephei.com:${server.port}/modelo.zip"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installer(paraOnde: Context = context): OfflineModelInstaller =
        OfflineModelInstaller(
            context = paraOnde,
            http = OkHttpClient.Builder()
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            listOf(InetAddress.getByName("127.0.0.1"))
                    },
                )
                .build(),
        )

    /**
     * Um contexto que estoura se alguém perguntar à rede. O download não pergunta: o
     * único critério dele é o pedido de voz, e é isso que este contexto prova.
     */
    private fun contextoQueNaoTemRede(): Context = object : ContextWrapper(context) {
        // Por padrão o `applicationContext` de um wrapper é o contexto de verdade — era por
        // aí que a guarda deixava passar. Devolvendo o próprio wrapper, o pedido de serviço
        // estoura em qualquer serviço e por qualquer caminho.
        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? =
            error("o download consultou o sistema: $name")
    }

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

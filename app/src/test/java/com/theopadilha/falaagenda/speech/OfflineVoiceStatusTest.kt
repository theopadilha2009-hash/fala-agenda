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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O instalador contando o que aconteceu com o modelo da fala offline.
 *
 * O que ele sabia até agora era um `Boolean` e uma linha de log: a tela de ajustes não
 * tinha o que mostrar, e quem atende o telefone não tinha como diagnosticar à distância
 * por que "o áudio nunca funciona". O estado aqui sai do que o download realmente sabe —
 * começou, terminou, ou não deu —, e não de um progresso percentual: o corpo do OkHttp
 * não reporta quanto já chegou, então um número na tela seria invenção.
 *
 * A URL é `http://alphacephei.com:<porta>` com o DNS apontando para o servidor local,
 * para a allowlist de verdade ficar no caminho.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineVoiceStatusTest {
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
        VoskModel.dir(context).deleteRecursively()
    }

    @Test
    fun semModeloOEstadoComecaEmNaoInstalado() {
        assertThat(installer().status.value).isEqualTo(OfflineVoiceStatus.NaoInstalado)
    }

    @Test
    fun modeloJaNoLugarApareceComoProntoNaAbertura() {
        escreverModelo()

        assertThat(installer().status.value).isEqualTo(OfflineVoiceStatus.Pronto)
    }

    /**
     * "Preparando" é o estado que ela mais vai ver: são 31 MB, e o download pode durar.
     * Ele tem que aparecer enquanto o download está de pé, e não só no fim.
     */
    @Test
    fun oDownloadEmCursoApareceComoInstalando() {
        val scheduler = TestCoroutineScheduler()
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()

        instalador.request(CoroutineScope(StandardTestDispatcher(scheduler)), url(), sha256(zip))

        assertThat(instalador.status.value).isEqualTo(OfflineVoiceStatus.Instalando)
    }

    @Test
    fun oDownloadQueTerminaApareceComoPronto() {
        val scheduler = TestCoroutineScheduler()
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()

        instalador.request(CoroutineScope(StandardTestDispatcher(scheduler)), url(), sha256(zip))
        scheduler.advanceUntilIdle()

        assertThat(instalador.status.value).isEqualTo(OfflineVoiceStatus.Pronto)
        assertThat(VoskModel.isInstalled(context)).isTrue()
    }

    @Test
    fun oDownloadQueFalhaApareceComoFalhouComOMotivo() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()

        instalador.installIfNeeded(url(), "0".repeat(64))

        val falhou = instalador.status.value
        assertThat(falhou).isInstanceOf(OfflineVoiceStatus.Falhou::class.java)
        assertThat((falhou as OfflineVoiceStatus.Falhou).motivo).isNotEmpty()
    }

    /**
     * O motivo tem que ser a causa de verdade. Um texto fixo diria "não deu" sem dizer
     * por quê — que é exatamente o problema que este estado veio resolver. Duas falhas
     * de origens diferentes, dois motivos diferentes.
     */
    @Test
    fun oMotivoDaFalhaDistingueUmaCausaDaOutra() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val somaErrada = installer()
        somaErrada.installIfNeeded(url(), "0".repeat(64))

        val fonteErrada = installer()
        fonteErrada.installIfNeeded("https://exemplo.com/modelo.zip", "0".repeat(64))

        assertThat(motivo(somaErrada)).isNotEqualTo(motivo(fonteErrada))
    }

    /**
     * A regra de produto é "uma tentativa por pedido de voz": quem decide tentar de novo
     * é ela, falando outra vez. O estado tem que voltar para `Pronto` sozinho nessa
     * segunda tentativa, senão a tela de ajustes ficaria dizendo que falhou para sempre.
     */
    @Test
    fun depoisDaFalhaOPedidoSeguinteTentaDeNovoEVoltaParaPronto() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()

        instalador.installIfNeeded(url(), "0".repeat(64))
        assertThat(instalador.status.value).isInstanceOf(OfflineVoiceStatus.Falhou::class.java)

        instalador.installIfNeeded(url(), sha256(zip))

        assertThat(instalador.status.value).isEqualTo(OfflineVoiceStatus.Pronto)
        assertThat(VoskModel.isInstalled(context)).isTrue()
    }

    /**
     * O escopo pode estar morto no instante do `launch`. A trava solta (ver `request`),
     * mas o estado também tem que voltar: "estamos preparando a voz" para um download
     * que nunca vai existir é a mesma mentira que o pedido seguinte devolvendo `null`
     * sem dizer nada.
     */
    @Test
    fun pedidoEmEscopoCanceladoNaoDeixaOEstadoPresoEmInstalando() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()
        val morto = CoroutineScope(Job() + Dispatchers.Unconfined).apply { cancel() }

        instalador.request(morto, url(), sha256(zip))

        assertThat(instalador.status.value).isEqualTo(OfflineVoiceStatus.NaoInstalado)
    }

    /**
     * O modelo também chega por fora do app (`scripts/vosk-model.sh`, o envio por USB).
     * Um estado que só se atualiza quando o download é nosso ficaria dizendo "não
     * preparada" com o modelo já no lugar.
     */
    @Test
    fun modeloQueChegaPorForaViraProntoNoPedidoSeguinte() {
        val zip = modeloZip()
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        val instalador = installer()
        assertThat(instalador.status.value).isEqualTo(OfflineVoiceStatus.NaoInstalado)

        escreverModelo()
        val pedido = instalador.request(CoroutineScope(Dispatchers.Unconfined), url(), sha256(zip))

        assertThat(pedido).isNull()
        assertThat(instalador.status.value).isEqualTo(OfflineVoiceStatus.Pronto)
    }

    private fun motivo(instalador: OfflineModelInstaller): String =
        (instalador.status.value as OfflineVoiceStatus.Falhou).motivo

    private fun escreverModelo() {
        val dir = VoskModel.dir(context)
        dir.mkdirs()
        File(dir, "final.mdl").writeText("peso")
        File(dir, "mfcc.conf").writeText("--sample-frequency=16000")
    }

    private fun url(): String = "http://alphacephei.com:${server.port}/modelo.zip"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installer(): OfflineModelInstaller =
        OfflineModelInstaller(
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

    private fun modeloZip(): ByteArray {
        val saida = ByteArrayOutputStream()
        ZipOutputStream(saida).use { zip ->
            listOf(
                "final.mdl" to "peso",
                "mfcc.conf" to "--sample-frequency=16000",
            ).forEach { (nome, conteudo) ->
                zip.putNextEntry(ZipEntry("${VoskModel.DIR_NAME}/$nome"))
                zip.write(conteudo.toByteArray())
                zip.closeEntry()
            }
        }
        return saida.toByteArray()
    }
}

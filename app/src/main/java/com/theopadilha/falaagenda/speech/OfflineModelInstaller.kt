package com.theopadilha.falaagenda.speech

import android.content.Context
import android.util.Log
import com.theopadilha.falaagenda.BuildConfig
import com.theopadilha.falaagenda.platform.AppUpdater
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "FalaAgendaOffline"

/**
 * Baixa o modelo do Vosk e instala em `filesDir`. Mesmo molde do AppUpdater: host
 * conhecido, integridade conferida contra um sha256 fixo, e nada pela metade fica
 * no lugar. Roda em segundo plano — a fala continua no motor do sistema até o
 * modelo ficar pronto, e é por isso que falhar aqui não é erro para quem usa.
 *
 * A soma é do arquivo publicado em alphacephei.com, a fonte oficial dos modelos do
 * Vosk. Ela é o que separa o modelo de um arquivo trocado no caminho: sem bater, o
 * download é descartado inteiro.
 *
 * Quem pede é [request], e quem chama é o toque no microfone. O download não é
 * disparado pela abertura do app: 31 MB por abertura, calados, é caro demais para
 * um pedido de voz que ela não fez.
 */
class OfflineModelInstaller(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    /** Um download de cada vez: dois pedidos de voz seguidos não baixam 62 MB. */
    private val emCurso = AtomicBoolean(false)

    /**
     * Pede o modelo sem segurar quem chamou, e devolve o trabalho iniciado — ou `null`
     * quando não é para baixar agora.
     *
     * Aqui não se espera nada: quem chama é a escuta, no toque do microfone, e a fala
     * não depende deste download. Ela começa no motor do sistema, como sempre, e a
     * escuta seguinte já encontra o offline.
     *
     * Sem laço e sem retentativa própria: uma tentativa por pedido de voz. O download é
     * caro, então quem decide tentar de novo é ela, ao pedir voz outra vez — e não um
     * relógio nosso batendo num servidor de 31 MB.
     *
     * O tipo de rede não entra na conta. O que faltava aqui era o gatilho, não um filtro:
     * recusar o download em rede medida deixaria um celular só com dados móveis sem a
     * fala offline para sempre, e a fala é o motivo de o app existir.
     */
    fun request(
        scope: CoroutineScope,
        source: String = SOURCE_URL,
        sha256: String = SHA256,
    ): Job? {
        if (VoskModel.isInstalled(context)) return null
        if (!emCurso.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                installIfNeeded(source, sha256)
            } finally {
                // Uma tentativa por pedido de voz, e sem laço: falhou, a próxima tentativa
                // é o próximo toque no microfone — o único momento em que este download
                // caro se justifica.
                emCurso.set(false)
            }
        }
    }

    /** `true` quando o modelo está instalado ao fim — inclusive se já estava. */
    fun installIfNeeded(
        source: String = SOURCE_URL,
        sha256: String = SHA256,
    ): Boolean {
        if (VoskModel.isInstalled(context)) return true
        val zip = File(cacheDir(context), ZIP_NAME)
        val staging = File(context.filesDir, STAGING_NAME)
        return try {
            fetch(source, zip)
            if (!AppUpdater.sha256(zip).equals(sha256, ignoreCase = true)) {
                error("o modelo veio diferente do publicado")
            }
            staging.deleteRecursively()
            extract(zip, staging)
            val baixado = File(staging, VoskModel.DIR_NAME)
            if (!VoskModel.isComplete(baixado)) error("o modelo veio incompleto")
            val destino = VoskModel.dir(context)
            destino.deleteRecursively()
            if (!baixado.renameTo(destino)) error("não deu para guardar o modelo")
            true
        } catch (erro: Exception) {
            // Modelo pela metade nunca fica passando por bom: o próximo pedido de voz
            // tenta de novo. E o motivo vai para o log — sem ele, o modelo que nunca
            // chegou não conta a ninguém por quê, e a fala fica no motor do sistema
            // sem que ninguém saiba que era para ser offline.
            Log.w(TAG, "o modelo da fala offline não chegou; a escuta segue no motor do sistema", erro)
            false
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    private fun fetch(url: String, dest: File) {
        if (!allowedSource(url)) error("fonte do modelo inválida")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FalaAgenda/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("o modelo não veio (${response.code})")
            val body = response.body ?: error("o modelo veio vazio")
            val declared = body.contentLength()
            if (declared > MAX_BYTES) error("o modelo veio grande demais")
            var total = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        // O tamanho declarado mente; o que vale é o que chega.
                        if (total > MAX_BYTES) error("o modelo veio grande demais")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) error("o modelo veio vazio")
        }
    }

    private fun extract(zip: File, target: File) {
        val raiz = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { entrada ->
            while (true) {
                val item = entrada.nextEntry ?: break
                val destino = File(target, item.name).canonicalFile
                // Zip com caminho para fora da pasta é ataque, não modelo.
                if (destino != raiz && !destino.path.startsWith(raiz.path + File.separator)) {
                    error("zip com caminho suspeito")
                }
                if (item.isDirectory) {
                    destino.mkdirs()
                } else {
                    destino.parentFile?.mkdirs()
                    destino.outputStream().use { entrada.copyTo(it) }
                }
                entrada.closeEntry()
            }
        }
    }

    companion object {
        const val SOURCE_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
        const val SHA256 = "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7"

        private const val ZIP_NAME = "vosk-model.zip"
        private const val STAGING_NAME = "vosk-model-parcial"

        /** Zip tem 31 MB hoje; o teto existe para um servidor errado não encher o aparelho. */
        const val MAX_BYTES = 64L * 1024 * 1024

        fun cacheDir(context: Context): File =
            File(context.cacheDir, "modelo").apply { mkdirs() }

        fun allowedSource(url: String): Boolean {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
            return host == "alphacephei.com"
        }
    }
}

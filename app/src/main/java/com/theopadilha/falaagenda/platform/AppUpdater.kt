package com.theopadilha.falaagenda.platform

import android.content.Context
import com.theopadilha.falaagenda.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Recusa que uma nova tentativa não conserta: o veredito é sobre o que a release publicou —
 * destino, soma, assinatura, número da versão — e o mesmo endereço vai dar o mesmo veredito.
 * Quem tenta de novo paga 13 MB dos dados do aparelho para receber o mesmo recado. Falha de
 * rede, de transferência ou de leitura do arquivo continua sendo exceção comum, e essa vale
 * um toque: a tela ainda convida a baixar.
 */
class UpdateRefused(val reason: String) : IllegalStateException(reason)

data class UpdateCheck(
    val local: String,
    val remote: String?,
    val apkUrl: String?,
    val sha256Url: String? = null,
    val newer: Boolean,
    val message: String,
)

class AppUpdater(
    private val context: Context,
    http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val certificates: SigningCertificates = AndroidSigningCertificates(context),
    private val versions: PackageVersions = AndroidPackageVersions(context),
) {
    /**
     * Cada salto do download passa por aqui, e não só a URL inicial: `followRedirects(true)`
     * resolve o 302 sozinho, então a allowlist aplicada uma vez só valeria para o endereço de
     * partida e o de chegada ficaria livre. Quem controla a rede poderia apontar o redirect
     * para o próprio servidor e escolher o que o aplicativo baixa.
     *
     * O GitHub **usa** redirect no download de asset (302 de `github.com` para
     * `release-assets.githubusercontent.com`, conferido em 29/09/2026): proibir redirect
     * quebraria toda atualização. Proibir *sair do GitHub* não quebra nada.
     *
     * O interceptor de rede roda depois do `ConnectInterceptor`, então a conexão com o
     * destino ainda chega a ser aberta antes da recusa — o pedido, não: nada é enviado,
     * e nenhum byte do arquivo vem de lá.
     */
    private val http: OkHttpClient = http.newBuilder()
        .addNetworkInterceptor { chain ->
            if (!allowedDownloadUrl(chain.request().url.toString())) {
                throw UpdateRefused(DESTINO_NAO_CONFIAVEL)
            }
            chain.proceed(chain.request())
        }
        .build()

    fun check(apiUrl: String = LATEST_API): UpdateCheck {
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "FalaAgenda/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return UpdateCheck(
                    local = localVersion(),
                    remote = null,
                    apkUrl = null,
                    newer = false,
                    message = "Ainda não há uma versão publicada para baixar.",
                )
            }
            if (!response.isSuccessful) {
                error("Não consegui procurar atualização (${response.code}).")
            }
            val body = response.body?.string().orEmpty()
            return fromJson(body, localVersion(), json)
        }
    }

    fun download(url: String, sha256Url: String? = null): File {
        if (!allowedDownloadUrl(url)) throw UpdateRefused(FONTE_INVALIDA)
        // Integridade não é opcional: uma release sem o `.sha256` publicado não pode virar uma
        // instalação silenciosamente mais fraca. Recusar antes de baixar 20 MB também é mais
        // barato para quem paga os dados do aparelho.
        val soma = sha256Url?.takeIf { it.isNotBlank() } ?: throw UpdateRefused(SOMA_AUSENTE)
        if (!allowedDownloadUrl(soma)) throw UpdateRefused(FONTE_INVALIDA)
        val dest = File(updatesDir(context), "Fala-Agenda-update.apk")
        val origem = File(updatesDir(context), "Fala-Agenda-update.source")
        return try {
            if (jaBaixado(dest, origem, url)) {
                // O arquivo guardado passou por soma e assinatura quando foi gravado, mas não
                // pela checagem de versão, que nasceu depois dele: sem isto, um "Instalar agora"
                // vindo do cache podia oferecer um rebaixamento, e quem barrava era o instalador
                // do sistema, com o erro genérico dele no lugar do recado do aplicativo.
                requireNotOlder(dest)
                dest
            } else {
                dest.delete()
                origem.delete()
                fetchTo(url, dest, MAX_APK_BYTES)
                val sumFile = File(updatesDir(context), "apk.sha256")
                fetchTo(soma, sumFile, 8 * 1024)
                val expected = parseSha256Sum(sumFile.readText())
                    ?: throw UpdateRefused("Não deu para ler a assinatura do instalador.")
                val actual = sha256(dest)
                if (!expected.equals(actual, ignoreCase = true)) {
                    throw UpdateRefused("O arquivo veio diferente do publicado. Não instalei.")
                }
                requireTrustedSignature(dest)
                requireNotOlder(dest)
                origem.writeText(url)
                dest
            }
        } catch (e: Exception) {
            // Arquivo pela metade ou recusado nunca fica no cache passando por bom.
            dest.delete()
            origem.delete()
            throw e
        }
    }

    /**
     * O APK daquela versão já está no cache e continua assinado pela chave do app instalado?
     * Baixar 20 MB de novo a cada vez que a tela abre custa os dados de quem usa o aparelho.
     */
    private fun jaBaixado(apk: File, origem: File, url: String): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        if (!origem.isFile || origem.readText().trim() != url) return false
        return ApkSignature.verdict(certificates.installed(), certificates.archive(apk)) ==
            ApkSignatureVerdict.TRUSTED
    }

    /**
     * O APK só chega ao instalador se for assinado pela mesma chave do aplicativo já instalado.
     * É a barreira que separa uma release legítima de um APK trocado no caminho: quem troca o
     * arquivo na rede não tem a chave.
     *
     * O `.sha256` publicado ao lado do arquivo não substitui isso, e nem chega perto: ele sai
     * da mesma release e trafega pelo mesmo caminho, então quem troca o APK troca a soma
     * junto. O que ele denuncia é outra coisa — arquivo corrompido na entrega ou asset trocado
     * por engano na publicação. Vale a pena, mas é a assinatura que decide: sem como ler as
     * certidões, não instala. Falhar fechado é o certo para quem usa o app.
     */
    private fun requireTrustedSignature(apk: File) {
        val verdict = ApkSignature.verdict(certificates.installed(), certificates.archive(apk))
        if (verdict == ApkSignatureVerdict.TRUSTED) return
        throw UpdateRefused(
            when (verdict) {
                ApkSignatureVerdict.MISMATCH -> ASSINATURA_DIFERENTE
                else -> ASSINATURA_ILEGIVEL
            },
        )
    }

    /**
     * A assinatura prova autoria, não versão: um APK antigo, legitimamente assinado pela mesma
     * chave, empacotado numa release nova, passaria na assinatura e na soma — e o aplicativo
     * "atualizaria" para trás. Por isso o número da versão só é lido **depois** da assinatura:
     * antes dela, o número não merece confiança.
     *
     * Os dois lados saem do mesmo PackageManager que a assinatura acabou de consultar; se
     * algum não vier legível, não há como afirmar rebaixamento, e a assinatura segue barrando.
     */
    private fun requireNotOlder(apk: File) {
        if (!isDowngrade(versions.archive(apk), versions.installed())) return
        throw UpdateRefused(VERSAO_NAO_E_MAIS_NOVA)
    }

    private fun fetchTo(url: String, dest: File, maxBytes: Long) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FalaAgenda/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Não deu para baixar o instalador (${response.code}).")
            }
            val body = response.body ?: error("O arquivo veio vazio.")
            val declared = body.contentLength()
            if (declared > maxBytes) error("O instalador veio grande demais.")
            var total = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) error("O instalador veio grande demais.")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) error("O arquivo veio vazio.")
            if (declared >= 0 && total != declared) error("O instalador veio incompleto.")
        }
    }

    companion object {
        const val LATEST_API =
            "https://api.github.com/repos/theopadilha2009-hash/fala-agenda/releases/latest"
        const val RELEASES_PAGE =
            "https://github.com/theopadilha2009-hash/fala-agenda/releases/latest"

        fun localVersion(): String = BuildConfig.VERSION_NAME.substringBefore("-")

        fun isDebugInstall(): Boolean = BuildConfig.APPLICATION_ID.endsWith(".debug")

        const val MAX_APK_BYTES = 40L * 1024 * 1024

        const val ASSINATURA_DIFERENTE =
            "O instalador não tem a assinatura deste aplicativo. Apaguei o arquivo e não instalei nada."

        const val ASSINATURA_ILEGIVEL =
            "Não consegui conferir a assinatura do instalador. Por segurança, não instalei nada."

        const val FONTE_INVALIDA = "Fonte de atualização inválida."

        /**
         * O interceptor está no mesmo cliente HTTP que o `check()` usa, então este recado pode
         * aparecer na checagem de release — onde arquivo nenhum existia e download nenhum tinha
         * começado. Ele não afirma que um arquivo foi apagado; o que vale nos dois caminhos é
         * que nada saiu do lugar.
         */
        const val DESTINO_NAO_CONFIAVEL =
            "O pedido de atualização tentou ir para outro endereço e eu não deixei. " +
                "Não baixei nem instalei nada."

        const val SOMA_AUSENTE =
            "A versão nova não veio com o arquivo que confere o download, então não baixei nada. " +
                "Avise quem instalou o aplicativo para você."

        const val VERSAO_NAO_E_MAIS_NOVA =
            "A versão que veio não é mais nova que a que já está no aparelho. Apaguei o arquivo " +
                "e não instalei nada. Avise quem instalou o aplicativo para você."

        /**
         * O rebaixamento é o que a assinatura **não** pega: um APK antigo, legitimamente
         * assinado pela mesma chave, empacotado numa release nova, passaria em toda a
         * conferência de autoria. Só o número da versão separa "atualização" de "volta".
         * Sem os dois números não há como afirmar rebaixamento — e quem barra aí é a assinatura.
         */
        fun isDowngrade(remote: Long?, installed: Long?): Boolean =
            remote != null && installed != null && remote <= installed

        fun allowedDownloadUrl(url: String): Boolean {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
            return host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        }

        fun updatesDir(context: Context): File =
            File(context.cacheDir, "updates").apply { mkdirs() }

        fun fromJson(
            raw: String,
            local: String,
            json: Json = Json { ignoreUnknownKeys = true },
        ): UpdateCheck {
            val parsed = json.decodeFromString(GithubRelease.serializer(), raw)
            val remote = versionName(parsed.tagName)
            val apk = parsed.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val sha = parsed.assets.firstOrNull { it.name.endsWith(".sha256", ignoreCase = true) }
            val newer = isNewer(remote, local)
            val message = when {
                apk == null -> "A versão $remote saiu, mas ainda não tem instalador."
                !newer -> "Você já está na última versão ($local)."
                // Sem a soma publicada o arquivo não tem como ser conferido; oferecer o botão
                // só levaria a uma recusa depois de 20 MB baixados.
                sha == null -> "A versão $remote saiu, mas veio sem o arquivo de conferência " +
                    "do instalador. Avise quem instalou o aplicativo para você."
                else -> "Tem versão nova: $remote. A sua é $local."
            }
            return UpdateCheck(
                local = local,
                remote = remote,
                apkUrl = apk?.url,
                sha256Url = sha?.url,
                newer = newer && apk != null && sha != null,
                message = message,
            )
        }

        fun parseSha256Sum(text: String): String? {
            val token = text.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            return token.lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        }

        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }

        fun versionName(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V").substringBefore("-")

        fun isNewer(remote: String, local: String): Boolean {
            val a = parts(remote)
            val b = parts(local)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(s: String): List<Int> =
            versionName(s).split(".").mapNotNull { it.toIntOrNull() }
    }
}

@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

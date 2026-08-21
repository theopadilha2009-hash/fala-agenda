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

data class UpdateCheck(
    val local: String,
    val remote: String?,
    val apkUrl: String?,
    val newer: Boolean,
    val message: String,
)

class AppUpdater(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun check(): UpdateCheck {
        val request = Request.Builder()
            .url(LATEST_API)
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

    fun download(url: String): File {
        if (!allowedDownloadUrl(url)) error("Fonte de atualização inválida.")
        val dest = File(updatesDir(context), "Fala-Agenda-update.apk")
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
            if (declared > MAX_APK_BYTES) error("O instalador veio grande demais.")
            var total = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_APK_BYTES) error("O instalador veio grande demais.")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) error("O arquivo veio vazio.")
        }
        return dest
    }

    companion object {
        const val LATEST_API =
            "https://api.github.com/repos/theopadilha2009-hash/fala-agenda/releases/latest"
        const val RELEASES_PAGE =
            "https://github.com/theopadilha2009-hash/fala-agenda/releases/latest"

        fun localVersion(): String = BuildConfig.VERSION_NAME.substringBefore("-")

        fun isDebugInstall(): Boolean = BuildConfig.APPLICATION_ID.endsWith(".debug")

        const val MAX_APK_BYTES = 40L * 1024 * 1024

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
            val newer = isNewer(remote, local)
            val message = when {
                apk == null -> "A versão $remote saiu, mas ainda não tem instalador."
                newer -> "Tem versão nova: $remote. A sua é $local."
                else -> "Você já está na última versão ($local)."
            }
            return UpdateCheck(
                local = local,
                remote = remote,
                apkUrl = apk?.url,
                newer = newer && apk != null,
                message = message,
            )
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

package com.theopadilha.falaagenda.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun versaoNovaEhDetectada() {
        assertThat(AppUpdater.isNewer("0.4.0", "0.3.0")).isTrue()
        assertThat(AppUpdater.isNewer("0.3.0", "0.3.0")).isFalse()
        assertThat(AppUpdater.isNewer("0.3.1", "0.4.0")).isFalse()
        assertThat(AppUpdater.isNewer("v0.4.0", "0.3.9")).isTrue()
    }

    @Test
    fun parseGithubComApk() {
        val json = """
            {
              "tag_name": "v0.4.1",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/theopadilha2009-hash/fala-agenda/releases/download/v0.4.1/app-release.apk"
                },
                {
                  "name": "apk.sha256",
                  "browser_download_url": "https://github.com/theopadilha2009-hash/fala-agenda/releases/download/v0.4.1/apk.sha256"
                }
              ]
            }
        """.trimIndent()
        val check = AppUpdater.fromJson(json, "0.4.0")
        assertThat(check.newer).isTrue()
        assertThat(check.remote).isEqualTo("0.4.1")
        assertThat(check.apkUrl).contains("app-release.apk")
        assertThat(check.sha256Url).contains("apk.sha256")
        assertThat(check.message).contains("0.4.1")
    }

    @Test
    fun parseSha256SumPegaHex() {
        val raw = "92db4a3507f7a384552b18c911d6fc711ece382154aabd4a42ba9a24f7e03735  app/build/outputs/apk/release/app-release.apk\n"
        assertThat(AppUpdater.parseSha256Sum(raw))
            .isEqualTo("92db4a3507f7a384552b18c911d6fc711ece382154aabd4a42ba9a24f7e03735")
        assertThat(AppUpdater.parseSha256Sum("not-a-hash")).isNull()
    }

    @Test
    fun parseSemApkNaoPedeUpdate() {
        val json = """{ "tag_name": "v0.4.0", "assets": [] }"""
        val check = AppUpdater.fromJson(json, "0.3.0")
        assertThat(check.newer).isFalse()
        assertThat(check.apkUrl).isNull()
    }

    @Test
    fun soAceitaDownloadDoGithub() {
        assertThat(AppUpdater.allowedDownloadUrl("https://github.com/theopadilha2009-hash/fala-agenda/releases/download/v0.4.0/app-release.apk")).isTrue()
        assertThat(AppUpdater.allowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/foo")).isTrue()
        assertThat(AppUpdater.allowedDownloadUrl("https://evil.example/app-release.apk")).isFalse()
        assertThat(AppUpdater.allowedDownloadUrl("not-a-url")).isFalse()
    }

    @Test
    fun mesmaVersaoNaoAtualiza() {
        val json = """
            {
              "tag_name": "v0.4.0",
              "assets": [{ "name": "app-release.apk", "browser_download_url": "https://x/a.apk" }]
            }
        """.trimIndent()
        val check = AppUpdater.fromJson(json, "0.4.0")
        assertThat(check.newer).isFalse()
        assertThat(check.message).contains("última versão")
    }
}

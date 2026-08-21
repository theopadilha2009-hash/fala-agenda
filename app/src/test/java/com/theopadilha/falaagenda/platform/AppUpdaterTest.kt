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
              "tag_name": "v0.4.0",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://example.com/app-release.apk"
                }
              ]
            }
        """.trimIndent()
        val check = AppUpdater.fromJson(json, "0.3.0")
        assertThat(check.newer).isTrue()
        assertThat(check.remote).isEqualTo("0.4.0")
        assertThat(check.apkUrl).isEqualTo("https://example.com/app-release.apk")
        assertThat(check.message).contains("0.4.0")
    }

    @Test
    fun parseSemApkNaoPedeUpdate() {
        val json = """{ "tag_name": "v0.4.0", "assets": [] }"""
        val check = AppUpdater.fromJson(json, "0.3.0")
        assertThat(check.newer).isFalse()
        assertThat(check.apkUrl).isNull()
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

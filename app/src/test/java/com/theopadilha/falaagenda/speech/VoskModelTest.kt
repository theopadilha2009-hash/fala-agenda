package com.theopadilha.falaagenda.speech

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * O modelo não vem no APK: quem decide se há motor offline é o conteúdo do
 * diretório. Modelo pela metade não pode passar por instalado — o Vosk abriria
 * e falharia só na hora de ouvir, com o microfone já ligado.
 */
class VoskModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun modeloCompletoEstaInstalado() {
        val dir = folder.newFolder("modelo")
        File(dir, "final.mdl").writeText("peso")
        File(dir, "mfcc.conf").writeText("--sample-frequency=16000")

        assertThat(VoskModel.isComplete(dir)).isTrue()
    }

    @Test
    fun diretorioVazioNaoEstaInstalado() {
        assertThat(VoskModel.isComplete(folder.newFolder("vazio"))).isFalse()
    }

    @Test
    fun modeloPelaMetadeNaoEstaInstalado() {
        val dir = folder.newFolder("meio")
        File(dir, "final.mdl").writeText("peso")

        assertThat(VoskModel.isComplete(dir)).isFalse()
    }

    @Test
    fun diretorioQueNaoExisteNaoEstaInstalado() {
        assertThat(VoskModel.isComplete(File(folder.root, "nunca-baixado"))).isFalse()
    }
}

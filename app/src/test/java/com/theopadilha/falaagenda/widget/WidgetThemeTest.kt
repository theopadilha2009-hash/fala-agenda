package com.theopadilha.falaagenda.widget

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import org.junit.Test
import java.io.File

class WidgetThemeTest {

    @Test
    fun seguirOCelularNaoFixaCor() {
        assertThat(paletteFor(ThemeMode.SYSTEM)).isNull()
    }

    @Test
    fun claroUsaAsCoresDoTemaClaro() {
        val paleta = paletteFor(ThemeMode.LIGHT)!!
        assertThat(paleta.background).isEqualTo(R.color.widget_light_bg)
        assertThat(paleta.text).isEqualTo(R.color.widget_light_text)
        assertThat(paleta.muted).isEqualTo(R.color.widget_light_muted)
        assertThat(paleta.accent).isEqualTo(R.color.widget_light_accent)
        assertThat(paleta.onAccent).isEqualTo(R.color.widget_light_on_accent)
    }

    @Test
    fun escuroUsaAsCoresDoTemaEscuro() {
        val paleta = paletteFor(ThemeMode.DARK)!!
        assertThat(paleta.background).isEqualTo(R.color.widget_dark_bg)
        assertThat(paleta.text).isEqualTo(R.color.widget_dark_text)
        assertThat(paleta.muted).isEqualTo(R.color.widget_dark_muted)
        assertThat(paleta.accent).isEqualTo(R.color.widget_dark_accent)
        assertThat(paleta.onAccent).isEqualTo(R.color.widget_dark_on_accent)
    }

    /**
     * A escolha dela só vale com o celular no modo oposto se a cor do tema não for a
     * mesma que o values-night sobrescreve.
     */
    @Test
    fun oTemaFixoNaoUsaCorQueANoiteSobrescreve() {
        val claro = paletteFor(ThemeMode.LIGHT)!!
        val escuro = paletteFor(ThemeMode.DARK)!!
        assertThat(listOf(claro.background, claro.text, claro.muted, claro.accent, claro.onAccent))
            .containsNoneOf(
                R.color.widget_bg,
                R.color.widget_text,
                R.color.widget_muted,
                R.color.widget_accent,
                R.color.widget_on_accent,
            )
        assertThat(escuro.background).isNotEqualTo(claro.background)
        assertThat(escuro.text).isNotEqualTo(claro.text)
        assertThat(escuro.accent).isNotEqualTo(claro.accent)
    }

    @Test
    fun asCoresNovasExistemSoNoValuesENaoNaNoite() {
        val dia = coresDe("src/main/res/values/colors.xml")
        val noite = coresDe("src/main/res/values-night/colors.xml")

        assertThat(dia["widget_light_bg"]).isEqualTo("#F4F1E8")
        assertThat(dia["widget_light_text"]).isEqualTo("#1A1A18")
        assertThat(dia["widget_light_accent"]).isEqualTo("#2F6B52")
        assertThat(dia["widget_dark_bg"]).isEqualTo("#1A221E")
        assertThat(dia["widget_dark_text"]).isEqualTo("#F4F1EA")
        assertThat(dia["widget_dark_accent"]).isEqualTo("#8FCBB0")

        val doTemaFixo = noite.keys.filter {
            it.startsWith("widget_light_") || it.startsWith("widget_dark_")
        }
        assertThat(doTemaFixo).isEmpty()
    }

    private fun coresDe(caminho: String): Map<String, String> {
        val arquivo = listOf(File(caminho), File("app/$caminho")).firstOrNull { it.isFile }
            ?: error("não achei $caminho a partir de ${File(".").absolutePath}")
        return Regex("""<color name="([^"]+)">([^<]+)</color>""")
            .findAll(arquivo.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}

package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.ThemeMode
import com.theopadilha.falaagenda.themeIsDark
import org.junit.Test

/**
 * Quem decide a cor dos ícones das barras do sistema é o tema do aplicativo, e não o modo
 * do celular: o `enableEdgeToEdge` lê `configuration.uiMode` sozinho e, com o celular no
 * escuro e o aplicativo no claro, relógio e bateria ficavam brancos sobre o creme. O resto
 * (a janela, o `enableEdgeToEdge`) é do framework e não dá para provar aqui.
 */
class ThemeIsDarkTest {
    @Test
    fun temaEscolhidoVenceOModoDoCelular() {
        assertThat(themeIsDark(ThemeMode.LIGHT, systemDark = true)).isFalse()
        assertThat(themeIsDark(ThemeMode.DARK, systemDark = false)).isTrue()
    }

    @Test
    fun seguirOCelularSegueOModoDoCelular() {
        assertThat(themeIsDark(ThemeMode.SYSTEM, systemDark = true)).isTrue()
        assertThat(themeIsDark(ThemeMode.SYSTEM, systemDark = false)).isFalse()
    }
}

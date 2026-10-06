package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * As frases do aviso atrasado — o caminho que mais aparece para ela quando o alarme não é
 * exato — nasciam hardcoded no composable e em jargão de sistema ("alarme exato", "ajustes
 * de alarme"): o nome técnico da tela, não o que acontece com o lembrete dela. O texto vive
 * fora do composable (ver as constantes em `HomeScreen`) para este teste poder lê-lo, e o que
 * ele prende é a promessa: o jargão não volta, e o texto não promete o que o app não faz.
 *
 * O `ReminderScheduler` usa `setAlarmClock` (na hora) com a permissão e `setAndAllowWhileIdle`
 * (o sistema pode atrasar) sem ela — as frases têm de ser verdadeiras nos dois caminhos.
 */
class AvisoInexatoTextoTest {

    private val frases = listOf(
        AVISO_INEXATO_SNACKBAR,
        AVISO_INEXATO_BOTAO,
        AVISO_INEXATO_FALHA_AO_ABRIR,
    )

    /**
     * O jargão de sistema que estava na tela dela, termo por termo. "alarme" sozinho entra
     * aqui de propósito: era ele que aparecia no botão e no cartão, e é ele que não deve
     * voltar para o lugar onde ela lê.
     */
    @Test
    fun asFrasesNaoFalamEmJargaoDeSistema() {
        val jargao = listOf(
            "alarme exato",
            "alarme",
            "ajustes",
            "horário exato",
            "não deixou",
        )

        frases.forEach { frase ->
            jargao.forEach { termo ->
                assertThat(frase.lowercase()).doesNotContain(termo)
            }
        }
    }

    /**
     * Não esconde o problema: o cartão persistente diz que o aviso pode chegar depois da hora.
     * Sem essa parte o texto prometeria um horário que o sistema pode não cumprir. (O cartão
     * antigo só aparecia no salvamento e dizia "a tarefa foi salva"; agora ele lê o aparelho a
     * cada resume e vale por si, sem a moldura de confirmação.)
     */
    @Test
    fun oCartaoDizQueOAvisoPodeAtrasar() {
        val inexato = alarmHealthCard(batteryUnrestricted = true, canScheduleExact = false)!!

        assertThat(inexato.text).contains("depois da hora")
    }

    /** O snackbar também não pode calar o atraso. */
    @Test
    fun oSnackbarTambemDizQueOAvisoPodeAtrasar() {
        assertThat(AVISO_INEXATO_SNACKBAR).contains("atrasar")
    }

    /**
     * Onde a frase é AÇÃO, ela diz o que acontece no toque, não o nome da tela que abre. O
     * botão antigo ("Abrir ajustes de alarme") mandava ela para uma tela de sistema pelo nome.
     */
    @Test
    fun oBotaoDizOQueElaGanhaENaoONomeDaTela() {
        assertThat(AVISO_INEXATO_BOTAO).contains("horário certo")
        assertThat(AVISO_INEXATO_BOTAO.lowercase()).doesNotContain("ajustes")
    }

    /**
     * A frase que aparece quando a tela do sistema não abre continua sendo um caminho, não um
     * beco: ela diz o que fazer, no mesmo tom do resto.
     */
    @Test
    fun aFalhaAoAbrirContinuaDizendoOCaminho() {
        assertThat(AVISO_INEXATO_FALHA_AO_ABRIR).contains("Não consegui abrir")
        assertThat(AVISO_INEXATO_FALHA_AO_ABRIR).contains("horário certo")
    }
}

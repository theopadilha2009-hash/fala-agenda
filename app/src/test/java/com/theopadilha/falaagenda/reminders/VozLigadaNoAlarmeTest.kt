package com.theopadilha.falaagenda.reminders

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * A ligação da voz no caminho do alarme, travada no fonte.
 *
 * O receiver do alarme e o do toque não têm harness de teste neste projeto — exercitá-los pediria
 * `FalaAgendaApplication`, Room, `AlarmManager` e um motor de voz de verdade, que é infraestrutura
 * inventada (é a mesma razão declarada em `ReminderActionReceiverDecisoesTest`). A política e o
 * serviço já estão presos por teste em [AlarmeQueFalaTest] e [LembreteFaladoServiceTest]; o que
 * falta prender é que **alguém chame** os dois, e que chame do lugar certo.
 *
 * Sem isto, apagar a chamada da voz deixa a suíte inteira verde — a voz existe, tem teste, e nunca
 * fala. É a mesma armadilha que o #77 mediu na notificação: o teste do canal passava enquanto o
 * lembrete saía no canal antigo.
 *
 * A leitura é do fonte, e é de propósito: um teste de comportamento aqui não é possível, e o
 * invariante que importa — "a voz sai junto do aviso que saiu" e "a voz cala no toque dela" — mora
 * na ordem e no guarda das linhas, não num valor de retorno.
 */
class VozLigadaNoAlarmeTest {
    private fun fonte(caminho: String): String {
        val arquivo = File(caminho).takeIf { it.isFile }
            ?: File("../$caminho")
        assertThat(arquivo.isFile).isTrue()
        return arquivo.readText()
    }

    /** O corpo de uma classe, do `class X` até a classe seguinte — o bastante para estas duas. */
    private fun corpoDe(arquivo: String, classe: String): String {
        val inicio = arquivo.indexOf("class $classe")
        assertThat(inicio).isAtLeast(0)
        val fim = arquivo.indexOf("\nclass ", inicio + 1).let { if (it < 0) arquivo.length else it }
        return arquivo.substring(inicio, fim).filterNot { it.isWhitespace() }
    }

    private val receivers = fonte("app/src/main/java/com/theopadilha/falaagenda/reminders/Receivers.kt")

    /**
     * O alarme disparou e a voz é pedida — **depois** do aviso sair e **guardada** por ele.
     *
     * A ordem é a regra: falar antes de saber se a notificação saiu seria falar um lembrete que a
     * tela não mostra. O guarda é o que separa os dois, e é ele que o `deveFalarOlembrete` responde.
     */
    @Test
    fun oAlarmePedeAVozDepoisDoAvisoSair() {
        val alarme = corpoDe(receivers, "ReminderAlarmReceiver")

        val aviso = alarme.indexOf("showReminder(")
        val guarda = alarme.indexOf("deveFalarOlembrete(")
        val voz = alarme.indexOf("LembreteFaladoService.falar(")

        assertThat(aviso).isAtLeast(0)
        assertThat(guarda).isAtLeast(0)
        assertThat(voz).isAtLeast(0)
        assertThat(aviso).isLessThan(guarda)
        assertThat(guarda).isLessThan(voz)
        // A voz está DENTRO do guarda, e não solta depois dele.
        assertThat(alarme.substring(guarda, voz)).contains("{")
    }

    /**
     * A voz não é esperada dentro do `goAsync`. O receiver tem 8 s para fechar o trabalho e a frase
     * dura mais que isso: suspender ali estouraria o prazo, o sistema mataria o processo com o
     * trabalho pendente e a voz morreria no meio. `falar` pede o serviço e volta.
     */
    @Test
    fun oAlarmeNaoEsperaAVozTerminar() {
        val alarme = corpoDe(receivers, "ReminderAlarmReceiver")
        val voz = alarme.indexOf("LembreteFaladoService.falar(")
        val chamada = alarme.substring(voz, alarme.indexOf("}", voz))

        assertThat(chamada).doesNotContain("join")
        assertThat(chamada).doesNotContain("await")
        assertThat(chamada).doesNotContain("runBlocking")
    }

    /**
     * O toque em "Concluir" ou "Adiar" cala a voz. É a metade do requisito que não dá para provar
     * pela política sozinha: o [AvisoFalado.parar] funciona, mas quem precisa chamá-lo é o receiver
     * do toque — e, sem esta linha, a voz continuaria dizendo "está na hora do remédio" depois que
     * ela já tomou.
     */
    @Test
    fun oToqueNaNotificacaoCalaAVoz() {
        val toque = corpoDe(receivers, "ReminderActionReceiver")

        assertThat(toque).contains("VozDoLembrete.parar(occurrenceId)")
    }
}

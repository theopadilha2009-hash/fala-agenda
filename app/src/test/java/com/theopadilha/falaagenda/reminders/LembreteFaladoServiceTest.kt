package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import java.time.Duration
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O serviço da voz: o lembrete disparou, e o celular fala.
 *
 * É a costura entre o gatilho e a política do [AvisoFalado] — o que este arquivo prende é que o
 * serviço **sobe em primeiro plano com uma notificação** (é o que o Android exige dele, e sem isso
 * o sistema mata o processo antes da frase acabar), **fala a frase do lembrete** e **sai sozinho**
 * quando a voz termina.
 *
 * O motor de voz é injetado, e um por disparo — que é o que acontece de verdade, porque cada
 * `onStartCommand` constrói o seu. O `TextToSpeech` do Android não existe no Robolectric e o
 * `onInit` dele é assíncrono, então nada aqui instancia um motor de verdade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class LembreteFaladoServiceTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val ocorrencia = "remedio:2026-10-07"
    private val titulo = "Tomar remédio"

    private class VozDeMentira : SintetizadorDeVoz {
        val falas = mutableListOf<String>()
        var paradas = 0
        var soltadas = 0
        private var esperando: ((Boolean) -> Unit)? = null
        private val terminam = mutableListOf<(String) -> Unit>()

        override fun quandoPronto(bloco: (Boolean) -> Unit) {
            esperando = bloco
        }

        fun ficouPronto() = esperando!!.invoke(true)

        override fun falar(texto: String, id: String, aoTerminar: (String) -> Unit) {
            falas += texto
            terminam += aoTerminar
        }

        fun terminouA(i: Int) = terminam[i]("fala-$i")

        override fun parar() {
            paradas++
        }

        override fun soltar() {
            soltadas++
        }
    }

    private val vozes = mutableListOf<VozDeMentira>()

    /** A voz do disparo mais recente. */
    private val voz: VozDeMentira get() = vozes.last()

    @After
    fun devolverOCriadorDeVoz() {
        LembreteFaladoService.criarVoz = { VozDoAparelho(it) }
    }

    /** Um serviço de verdade, com um motor de mentira por disparo, e o relógio na mão do teste. */
    private fun subirOServico(): ServiceController<LembreteFaladoService> {
        LembreteFaladoService.criarVoz = { VozDeMentira().also { vozes += it } }
        val intent = LembreteFaladoService.intentPara(contexto, ocorrencia, titulo)
        val servico = Robolectric.buildService(LembreteFaladoService::class.java, intent).create()
        servico.get().onStartCommand(intent, 0, 1)
        return servico
    }

    private fun avancarOrelogio(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /**
     * A frase que ela ouve é a do lembrete, com o que a tarefa é. O texto exato é comparado com o
     * recurso, e não com uma string solta: o que ele prende é a frase montada com o título da
     * tarefa dentro, que é a decisão de produto.
     */
    @Test
    fun falaAFraseDoLembreteComOTituloDaTarefa() {
        subirOServico()
        voz.ficouPronto()

        assertThat(voz.falas)
            .containsExactly(contexto.getString(R.string.reminder_spoken, titulo))
        assertThat(voz.falas.single()).contains(titulo)
    }

    /**
     * O serviço sobe em **primeiro plano**, com a notificação que o Android exige dele. Sem
     * `startForeground` nos 5 segundos, o sistema mata o processo — e a frase morre no meio, que é
     * exatamente a queixa dela.
     */
    @Test
    fun sobeEmPrimeiroPlanoComANotificacaoDaVoz() {
        val servico = subirOServico()

        val notificacao = shadowOf(servico.get()).lastForegroundNotification
        assertThat(notificacao).isNotNull()
        assertThat(notificacao.channelId).isEqualTo(NotificationHelper.CHANNEL_VOZ_ID)
    }

    /**
     * O canal da voz é **mudo**. Ele existe porque o Android exige uma notificação do serviço em
     * primeiro plano, não para avisar de novo: com som próprio ele competiria com o canal de alarme
     * do #77, e dois alarmes tocando juntos é pior que um.
     */
    @Test
    fun oCanalDaVozNaoFazBarulho() {
        subirOServico()

        val canal = contexto.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationHelper.CHANNEL_VOZ_ID)
        assertThat(canal).isNotNull()
        assertThat(canal.sound).isNull()
        assertThat(canal.importance).isLessThan(NotificationManager.IMPORTANCE_HIGH)
    }

    /**
     * A voz termina e o serviço **sai sozinho**, com o motor fechado. Um serviço de primeiro plano
     * que fica de pé depois de falar segura o processo e deixa na barra uma notificação que não
     * quer dizer mais nada.
     */
    @Test
    fun depoisDeFalarOServicoSaiSozinho() {
        val servico = subirOServico()
        voz.ficouPronto()
        voz.terminouA(0)
        avancarOrelogio(PAUSA_ENTRE_AS_FALAS_MS + 1)
        voz.terminouA(1)

        assertThat(shadowOf(servico.get()).isStoppedBySelf).isTrue()
        assertThat(voz.soltadas).isAtLeast(1)
    }

    /**
     * O mesmo lembrete dispara de novo — a escada de repetições —, e cada disparo constrói o seu
     * próprio motor. O prazo do aviso **velho** vence depois, com o aviso novo ainda no ar: ele não
     * pode derrubar o serviço nem soltar o motor da voz nova. Sem a guarda, a conta atrasada do
     * primeiro disparo calaria o aplicativo exatamente quando ele estava insistindo.
     */
    @Test
    fun aContaDoDisparoVelhoNaoDerrubaAVozNova() {
        LembreteFaladoService.criarVoz = { VozDeMentira().also { vozes += it } }
        val intent = LembreteFaladoService.intentPara(contexto, ocorrencia, titulo)
        val servico = Robolectric.buildService(LembreteFaladoService::class.java, intent).create()
        servico.get().onStartCommand(intent, 0, 1)
        val vozVelha = voz
        vozVelha.ficouPronto()

        // O segundo disparo chega 1 s depois do primeiro, e cada aviso conta o prazo dele a partir
        // daí: o do primeiro vence em `prazo`, o do segundo em `prazo + 1 s`.
        val deslocamentoDoSegundo = 1_000L
        avancarOrelogio(deslocamentoDoSegundo)
        servico.get().onStartCommand(intent, 0, 2)
        val vozNova = voz
        assertThat(vozNova).isNotSameInstanceAs(vozVelha)

        // Vai até depois do prazo do PRIMEIRO e antes do prazo do segundo: é exatamente a janela em
        // que a conta atrasada do aviso velho vence com o aviso novo ainda no ar. O motor do aviso
        // novo não fica pronto de propósito — é o pior caso, o do `onInit` que não chega.
        val prazo = prazoTotalDaFalaMs(contexto.getString(R.string.reminder_spoken, titulo))
        avancarOrelogio(prazo - deslocamentoDoSegundo + 100)

        assertThat(shadowOf(servico.get()).isStoppedBySelf).isFalse()
        assertThat(vozNova.soltadas).isEqualTo(0)

        // E o serviço ainda sai, pelo prazo do aviso NOVO: a guarda protege a voz nova, não a
        // prende para sempre.
        avancarOrelogio(deslocamentoDoSegundo)
        assertThat(shadowOf(servico.get()).isStoppedBySelf).isTrue()
    }

    /**
     * O serviço é destruído no meio da frase — o sistema matou o processo. O motor **fecha**: sem
     * isso o `TextToSpeech` sobrevive ao serviço que o criou e continua falando para uma
     * notificação que já saiu da barra, que é literalmente a voz presa.
     */
    @Test
    fun destruidoNoMeioDaFraseSoltaOMotor() {
        val servico = subirOServico()
        voz.ficouPronto()
        assertThat(voz.falas).hasSize(1)

        servico.destroy()

        assertThat(voz.soltadas).isAtLeast(1)
        assertThat(voz.paradas).isAtLeast(1)
    }

    /**
     * Um pedido torto chega **enquanto a voz está falando** — o segundo `onStartCommand`, sem os
     * dados do lembrete. O pedido ruim é ignorado: parar o serviço aqui mataria a frase que está
     * tocando, e quem a está ouvindo não tem culpa de o pedido novo ter chegado sem os dados.
     */
    @Test
    fun pedidoTortoNaoDerrubaAVozQueEstaFalando() {
        val servico = subirOServico()
        voz.ficouPronto()
        val torto = LembreteFaladoService.intentPara(contexto, ocorrencia, titulo)
            .apply { removeExtra(AlarmIds.EXTRA_OCCURRENCE_ID) }

        servico.get().onStartCommand(torto, 0, 2)

        assertThat(shadowOf(servico.get()).isStoppedBySelf).isFalse()
        assertThat(voz.soltadas).isEqualTo(0)
    }

    /**
     * Chamado sem os dados do lembrete, o serviço **não sobe** e sai na hora: uma voz sem frase
     * seria um primeiro plano sem razão de ser, segurando o processo por nada.
     */
    @Test
    fun semOsDadosDoLembreteNaoSobeEmPrimeiroPlano() {
        LembreteFaladoService.criarVoz = { VozDeMentira().also { vozes += it } }
        val intent = LembreteFaladoService.intentPara(contexto, ocorrencia, titulo)
            .apply { removeExtra(AlarmIds.EXTRA_OCCURRENCE_ID) }
        val servico = Robolectric.buildService(LembreteFaladoService::class.java, intent).create()
        servico.get().onStartCommand(intent, 0, 1)

        assertThat(vozes).isEmpty()
        assertThat(shadowOf(servico.get()).lastForegroundNotification).isNull()
        assertThat(shadowOf(servico.get()).isStoppedBySelf).isTrue()
    }
}

package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.app.Notification
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

    private class VozDeMentira(
        private val recusaAFrase: Boolean = false,
        private val falhaDepoisDoAceite: Boolean = false,
    ) : SintetizadorDeVoz {
        /** As falas que **saíram**. A recusa e a falha não entram aqui: não houve som nenhum. */
        val falas = mutableListOf<String>()
        var paradas = 0
        var soltadas = 0
        private var esperando: ((Boolean) -> Unit)? = null
        private val terminam = mutableListOf<(String) -> Unit>()

        override fun quandoPronto(bloco: (Boolean) -> Unit) {
            esperando = bloco
        }

        /** O `onInit` do aparelho chegou, com a resposta que o teste escolheu. */
        fun ficouPronto(sabeFalar: Boolean = true) = esperando!!.invoke(sabeFalar)

        override fun falar(
            texto: String,
            id: String,
            aoSair: () -> Unit,
            aoTerminar: (String) -> Unit,
        ) {
            if (recusaAFrase || falhaDepoisDoAceite) {
                // Os dois caminhos em que nenhum som sai: a recusa síncrona do `speak` (que devolve
                // o código de erro) e a falha depois do aceite (o `onError` do motor). Nos dois o
                // motor avisa o **fim** e nunca avisa a **saída**.
                aoTerminar(id)
                return
            }
            falas += texto
            terminam += aoTerminar
            // O motor começou a falar — o `onStart` dele.
            aoSair()
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
        LembreteFaladoService.subirEmPrimeiroPlano = { it.tentarSubirEmPrimeiroPlano() }
    }

    /** Um serviço de verdade, com um motor de mentira por disparo, e o relógio na mão do teste. */
    private fun subirOServico(
        recusaAFrase: Boolean = false,
        falhaDepoisDoAceite: Boolean = false,
    ): ServiceController<LembreteFaladoService> {
        LembreteFaladoService.criarVoz = {
            VozDeMentira(recusaAFrase, falhaDepoisDoAceite).also { vozes += it }
        }
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

    // --- O toque na notificação abre a tarefa do disparo ATUAL --------------------------------

    /**
     * O toque na notificação da voz abre a tarefa que está sendo falada agora.
     *
     * O `PendingIntent` é montado durante o `startForeground`, e é por isso que o disparo em
     * curso precisa estar gravado **antes** dele: com a atribuição depois, o primeiro disparo
     * montava o intent com `occurrence_id = ""`, e o toque dela caía num id que não existe —
     * a home respondia "Esta tarefa não está mais na agenda" para a tarefa que estava tocando.
     */
    @Test
    fun aNotificacaoDaVozAbreATarefaDoDisparoAtual() {
        val servico = subirOServico()

        val notificacao = shadowOf(servico.get()).lastForegroundNotification
        assertThat(notificacao).isNotNull()
        val alvo = shadowOf(notificacao.contentIntent).savedIntent
        assertThat(alvo.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID)).isEqualTo(ocorrencia)
    }

    // --- Um disparo novo não vaza o motor nem deixa a voz velha falando ------------------------

    /**
     * Dois lembretes que se cruzam — dois remédios no mesmo horário, ou um "Adiar" que cai perto
     * do outro. O disparo novo **abandona** o aviso anterior: sem isso o motor velho fica de pé
     * com a escada de repetições viva, e a segunda metade da frase antiga sai por cima da voz
     * nova. É o defeito que este PR existe para evitar, na única situação em que dois lembretes
     * se encontram.
     */
    @Test
    fun oDisparoNovoAbandonaOMotorEAEscadaDoAnterior() {
        LembreteFaladoService.criarVoz = { VozDeMentira().also { vozes += it } }
        val anterior = "remedioA:2026-10-07"
        val seguinte = "remedioB:2026-10-07"
        val servico = Robolectric.buildService(LembreteFaladoService::class.java, intentDo(anterior))
            .create()
        servico.get().onStartCommand(intentDo(anterior), 0, 1)
        val vozVelha = voz
        vozVelha.ficouPronto()
        assertThat(vozVelha.falas).hasSize(1)

        servico.get().onStartCommand(intentDo(seguinte), 0, 2)
        val vozNova = voz
        assertThat(vozNova).isNotSameInstanceAs(vozVelha)

        // O motor do aviso velho foi solto: ele não continua de pé falando sozinho.
        assertThat(vozVelha.paradas).isAtLeast(1)
        assertThat(vozVelha.soltadas).isAtLeast(1)
        // E a escada dele morreu junto: o fim da fala velha não faz o aviso velho repetir por
        // cima da voz nova.
        vozVelha.terminouA(0)
        avancarOrelogio(PAUSA_ENTRE_AS_FALAS_MS + 1)
        assertThat(vozVelha.falas).hasSize(1)

        // A voz nova fala normalmente, e não foi derrubada pelo encerramento do aviso velho.
        vozNova.ficouPronto()
        assertThat(vozNova.falas).hasSize(1)
        assertThat(vozNova.soltadas).isEqualTo(0)
        assertThat(shadowOf(servico.get()).isStoppedBySelf).isFalse()
    }

    // --- A barra não afirma que está falando quando não está ----------------------------------

    /**
     * A notificação do primeiro plano diz que o celular está **avisando em voz alta**. Ela só pode
     * dizer isso depois de a voz realmente sair.
     *
     * O cenário é o que o próprio PR chama de real: aparelho sem voz em português do Brasil, ou o
     * `onInit` que nunca chega. O motor não sobe, nenhuma fala sai, e a notificação ficava no ar
     * durante os 6,5 s do prazo afirmando que estava falando. Para ela isso é indistinguível de
     * "o áudio nunca funciona" — com o agravante de o aplicativo dizer na cara dela que falou.
     *
     * O oráculo é do ponto de vista dela: **a tela disse que falou ⇒ alguma fala saiu**. A
     * amostragem cobre a janela inteira do prazo, e não um instante escolhido a dedo.
     */
    @Test
    fun aBarraNuncaDizQueEstaFalandoSemAVozTerFalado() {
        subirOServico()
        // O motor nunca fica pronto: nem `onInit`, nem uma fala.

        // A outra metade da mesma regra, e é ela que impede o conserto por omissão: antes de a
        // fala sair, o título é o nome do canal — neutro, e o mesmo que ela vê nos Ajustes. Sem
        // esta asserção positiva, esvaziar o título neutro passava: a barra ficava sem título
        // nenhum e as duas metades do D1 continuavam verdes.
        assertThat(titulosNaBarra())
            .contains(contexto.getString(R.string.notification_channel_voice))

        val violacoes = amostrarOBarraco()

        assertThat(voz.falas).isEmpty()
        assertThat(violacoes).isEmpty()
    }

    /**
     * A outra metade, e é ela que impede o conserto fácil: quando a voz **sai**, a barra diz que
     * está falando. Apagar a afirmação de vez passaria no teste de cima e deixaria a notificação
     * sem dizer nada do que está acontecendo.
     */
    @Test
    fun aBarraDizQueEstaFalandoQuandoAVozSai() {
        subirOServico()
        voz.ficouPronto()
        assertThat(voz.falas).hasSize(1)

        assertThat(afirmaQueEstaFalando()).isTrue()
    }

    /**
     * O motor **sobe** — o `quandoPronto(true)` passa, o idioma está lá — e mesmo assim a frase não
     * sai: o `speak` devolve erro e o [VozDoAparelho] avisa o fim pelo `catch`, sem ter falado nada.
     * É voz corrompida, idioma listado sem dado de fala, motor ocupado.
     *
     * O `aoFalar()` do [AvisoFalado] disparava quando o motor **aceitava** a frase, não quando ela
     * saía. Neste caminho a barra anunciava "Avisando em voz alta" com zero falas — para ela,
     * indistinguível de "o áudio nunca funciona", com o aplicativo dizendo na cara dela que falou.
     * O D1 fechou só a metade do motor que não sobe; esta é a outra metade, e é a que o defeito
     * original do D1 também ocupava.
     *
     * A amostragem é fina no começo por causa disto: a recusa volta na hora, e a mentira vive entre
     * o instante 0 e o primeiro ciclo do motor. O oráculo é o efeito — a tela disse que falou ⇒
     * alguma fala saiu —, e não uma linha do serviço.
     */
    @Test
    fun aBarraNaoDizQueEstaFalandoQuandoOMotorRecusaAFrase() {
        subirOServico(recusaAFrase = true)
        voz.ficouPronto()
        assertThat(voz.falas).isEmpty()

        val violacoes = amostrarOBarraco()

        assertThat(falasQueSaíram()).isEqualTo(0)
        assertThat(violacoes).isEmpty()
    }

    /**
     * O motor **aceita** a frase — o `speak` devolve `SUCCESS` — e a síntese falha depois, pelo
     * `onError` do motor. O `VozDoAparelho` traduz esse aviso para o `aoTerminar` (é o mesmo
     * caminho de `onDone`), e a frase não existe: nenhum som saiu.
     *
     * O retorno do `falar` não separa este caso do aceite verdadeiro — os dois devolvem `true` —,
     * então a barra anunciava "Avisando em voz alta" no aceite e ficava os 6,5 s do prazo
     * afirmando, sem uma palavra. É a queixa dela ("o áudio nunca funciona") na voz do pt-BR
     * corrompida ou parcialmente baixada, ou com o motor ocupado. O oráculo é o efeito: a tela
     * disse que falou ⇒ alguma fala saiu.
     */
    @Test
    fun aFalhaDeSinteseDepoisDoAceiteAindaDeixaABarraAfirmando() {
        subirOServico(falhaDepoisDoAceite = true)
        voz.ficouPronto()

        assertThat(voz.falas).isEmpty()

        val violacoes = amostrarOBarraco()

        assertThat(falasQueSaíram()).isEqualTo(0)
        assertThat(violacoes).isEmpty()
    }

    /**
     * Dois lembretes em sequência. O primeiro fala, e a barra diz que está falando — verdade. O
     * segundo dispara e o motor dele **não sobe**: a barra do segundo nascia afirmando que estava
     * falando por conta do primeiro, a mesma mentira que o D1 existe para matar, agora atravessando
     * disparos.
     *
     * O reset de `vozFalando` no `onStartCommand` é o que impede isso, e sem este teste nenhum
     * prendia o reset: apagá-lo deixava a suíte inteira verde.
     */
    @Test
    fun aBarraNaoAfirmaQueEstaFalandoNoSegundoDisparoPorContaDoPrimeiro() {
        val servico = subirOServico()
        voz.ficouPronto()
        assertThat(voz.falas).hasSize(1)
        assertThat(afirmaQueEstaFalando()).isTrue()

        // O segundo lembrete, e o motor dele nunca fica pronto: nenhuma fala do segundo sai.
        servico.get().onStartCommand(intentDo("remedioB:2026-10-07"), 0, 2)
        assertThat(vozes).hasSize(2)
        val vozNova = voz
        assertThat(vozNova.falas).isEmpty()

        // A afirmação do primeiro não pode valer para o segundo. O que se assere é o **efeito** na
        // barra: o título de "falando" não está lá, e sim o neutro, porque a voz do segundo — a
        // única que interessa agora — não saiu. Sem o reset, o segundo nascia com o título do
        // primeiro e a barra mentia sobre a voz que ainda nem tinha tentado falar.
        assertThat(afirmaQueEstaFalando()).isFalse()
        assertThat(titulosNaBarra())
            .contains(contexto.getString(R.string.notification_channel_voice))
    }

    /**
     * O `startForeground` do disparo novo falha e existe uma voz viva na barra. O pedido ruim sai
     * pela [sairSeNaoHavoz] sem parar nada — a voz que está falando continua —, mas o disparo que
     * falhou já tinha sobrescrito a ocorrência em curso: o toque na notificação da voz que ainda
     * está falando abriria a tarefa do disparo que nunca subiu.
     *
     * O caminho é raro (o `startForeground` só é recusado com o primeiro plano negado pelo sistema),
     * e é justamente por isso que ele precisa de teste: é o único em que o serviço ignora um disparo
     * e mantém outro no ar ao mesmo tempo.
     */
    @Test
    fun oPrimeiroPlanoRecusadoNaoRoubaATarefaDoToqueDaVozQueEstaFalando() {
        val anterior = "remedioA:2026-10-07"
        val recusado = "remedioB:2026-10-07"
        LembreteFaladoService.criarVoz = { VozDeMentira().also { vozes += it } }
        val servico = Robolectric.buildService(LembreteFaladoService::class.java, intentDo(anterior))
            .create()
        servico.get().onStartCommand(intentDo(anterior), 0, 1)
        voz.ficouPronto()
        assertThat(tarefaDoToqueDaVoz(servico)).isEqualTo(anterior)

        LembreteFaladoService.subirEmPrimeiroPlano = { false }
        servico.get().onStartCommand(intentDo(recusado), 0, 2)

        // O disparo recusado nem chegou a construir motor: ele não assumiu nada.
        assertThat(vozes).hasSize(1)
        // E a voz do primeiro continua no ar — o pedido ruim não a derruba.
        assertThat(shadowOf(servico.get()).isStoppedBySelf).isFalse()
        assertThat(voz.soltadas).isEqualTo(0)

        // A voz que estava falando repete. É aqui que a ocorrência em curso volta a ser lida: a
        // notificação é remontada no `anunciarQueEstaFalando`, e é ela que monta o `PendingIntent`
        // do toque. Com o disparo recusado ainda no campo, o toque dela cairia na tarefa errada.
        voz.terminouA(0)
        avancarOrelogio(PAUSA_ENTRE_AS_FALAS_MS + 1)
        assertThat(voz.falas).hasSize(2)

        assertThat(tarefaDoToqueDaVoz(servico)).isEqualTo(anterior)
    }

    private fun intentDo(occurrenceId: String) =
        LembreteFaladoService.intentPara(contexto, occurrenceId, titulo)

    /** A tarefa que a notificação da voz abre hoje, lida do `PendingIntent` que está na barra. */
    private fun tarefaDoToqueDaVoz(servico: ServiceController<LembreteFaladoService>): String? {
        val notificacao = shadowOf(servico.get()).lastForegroundNotification
        assertThat(notificacao).isNotNull()
        return shadowOf(notificacao.contentIntent).savedIntent
            .getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID)
    }

    /** O título que está de fato na barra, e não o que o serviço guardou por último. */
    private fun titulosNaBarra(): List<String> =
        shadowOf(contexto.getSystemService(NotificationManager::class.java))
            .allNotifications
            .mapNotNull { it.extras?.getString(Notification.EXTRA_TITLE) }

    private fun afirmaQueEstaFalando(): Boolean =
        titulosNaBarra().contains(contexto.getString(R.string.reminder_speaking_title))

    /** As falas que **saíram** de fato, somando os motores de todos os disparos do teste. */
    private fun falasQueSaíram(): Int = vozes.sumOf { it.falas.size }

    /**
     * Amostra a barra ao longo do prazo inteiro do aviso e devolve os instantes em que ela
     * afirmou que estava falando sem nenhuma fala ter saído.
     *
     * Os instantes do começo são **finos** de propósito. O defeito que esta amostragem existe para
     * pegar vive entre o instante 0 e o primeiro ciclo do motor (a recusa volta na hora, e o
     * `aoFalar()` antigo vinha logo depois): uma amostragem em 0 e 1 s passa por cima da janela
     * inteira e dá verde num código que mente. O resto do prazo continua coberto, em passos largos.
     */
    private fun amostrarOBarraco(): List<Long> {
        val violacoes = mutableListOf<Long>()
        var decorrido = 0L
        val prazo = prazoTotalDaFalaMs(contexto.getString(R.string.reminder_spoken, titulo))
        for (instante in listOf(0L, 250L, 500L, 700L, 1_000L, 3_000L, prazo - 500, prazo + 500)) {
            avancarOrelogio(instante - decorrido)
            decorrido = instante
            if (afirmaQueEstaFalando() && falasQueSaíram() == 0) violacoes += instante
        }
        return violacoes
    }
}

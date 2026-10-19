package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.reminders.NotificationHelper.ReminderAlerts
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O ajuste diz o que o aparelho realmente faz com a voz.
 *
 * A queixa dela é literal e repetida — "o áudio nunca funciona". O aparelho dela não tem a voz em
 * português do Brasil: o motor sobe, o `onInit` responde, e a frase não sai. O veredito dos avisos
 * ([NotificationHelper.reminderAlerts]) não olhava a voz — dizia [ReminderAlerts.OK] com o lembrete
 * tocando o plim e calado na fala —, e a home não mostrava cartão nenhum.
 *
 * O que estes casos prendem: com o aparelho **sem** voz o ajuste declara
 * [ReminderAlerts.SEM_VOZ] (e o som de alarme continua, porque a voz é complemento, não substituto);
 * com a voz presente continua [ReminderAlerts.OK]; e antes de medir, o ajuste não inventa aviso —
 * "ainda não sei" não é "sem voz".
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class AjusteDaVozTest {
    private val contexto: Context = SemTextos(ApplicationProvider.getApplicationContext())

    @Before
    fun partirDoZero() {
        // A sonda é um singleton e a suíte inteira compartilha o processo: um teste anterior que
        // leu o veredito dos avisos acendeu a medição com o motor real. Sem o zero aqui, este
        // arquivo media a ordem dos testes e não o comportamento.
        SondaDeVoz.reiniciar()
    }

    @After
    fun devolverOSintetizador() {
        SondaDeVoz.criarVoz = { VozDoAparelho(it) }
        SondaDeVoz.reiniciar()
    }

    private fun liberarNotificacoes() {
        val gerente = contexto.getSystemService(NotificationManager::class.java)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(gerente).setNotificationsEnabled(true)
    }

    private fun aparelhoAudivel() {
        val audio = contexto.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    }

    /** O canal saudável de fábrica: [NotificationHelper.ensureChannel] já o cria com som de alarme. */
    private fun canalSaudavel() {
        NotificationHelper.ensureChannel(contexto)
    }

    /**
     * Instala a sonda com um sintetizador que responde o que o teste escolher — o mesmo padrão do
     * [LembreteFaladoService.criarVoz], porque o `TextToSpeech` não existe no Robolectric e o
     * `onInit` dele é assíncrono.
     */
    private fun sondaQueResponde(sabeFalar: Boolean) {
        val sintetizador = SintetizadorQueResponde(sabeFalar)
        SondaDeVoz.criarVoz = { sintetizador }
        SondaDeVoz.medir(contexto)
        sintetizador.responder()
    }

    @Test
    fun aparelhoSemVozOAjusteDeclaraSemVoz() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        sondaQueResponde(sabeFalar = false)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(ReminderAlerts.SEM_VOZ)
    }

    @Test
    fun aparelhoComVozOAjusteContinuaDizendoOk() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        sondaQueResponde(sabeFalar = true)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(ReminderAlerts.OK)
    }

    /**
     * O veredito sem medição é [ReminderAlerts.OK], e não [ReminderAlerts.SEM_VOZ]: o `onInit` do
     * motor é assíncrono e pode ainda não ter chegado. Inventar "sem voz" antes da resposta é o
     * mesmo defeito na direção oposta — o cartão falso que ensina a ser ignorado.
     */
    @Test
    fun antesDeMedirOAjusteNaoInventaAviso() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        val sintetizador = SintetizadorQueResponde(sabeFalar = false)
        SondaDeVoz.criarVoz = { sintetizador }

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(ReminderAlerts.OK)
        // E a pergunta foi feita: o ajuste é quem acende a sonda.
        assertThat(sintetizador.perguntou).isTrue()
    }

    /**
     * A voz é complemento: com o aparelho sem voz o lembrete continua sendo entregue, e o som de
     * alarme do canal continua lá — o que o ajuste muda é só o que ele diz sobre a fala.
     */
    @Test
    fun aparelhoSemVozOAlarmeContinuaSaindo() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        sondaQueResponde(sabeFalar = false)

        val entrega = NotificationHelper.showReminder(contexto, "s1:2026-10-19", "s1", "Vitamina")

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.POSTED)
        val gerente = contexto.getSystemService(NotificationManager::class.java)
        assertThat(shadowOf(gerente).size()).isEqualTo(1)
        val canal = gerente.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        assertThat(canal!!.sound).isNotNull()
    }

    /**
     * O fluxo de conserto inteiro: o ajuste disse SEM_VOZ, ela tocou no botão, instalou a voz e
     * voltou. A home relê o veredito no ON_RESUME, e a sonda re-medida — o cartão que sobreviveu
     * ao conserto até matarem o processo era o mesmo "avisos OK" na direção contrária.
     */
    @Test
    fun aVozQueFaltouEMedidaDeNovoQuandoElaVoltaDaInstalacao() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        sondaQueResponde(sabeFalar = false)
        assertThat(NotificationHelper.reminderAlerts(contexto)).isEqualTo(ReminderAlerts.SEM_VOZ)

        val nova = SintetizadorQueResponde(sabeFalar = true)
        SondaDeVoz.criarVoz = { nova }
        SondaDeVoz.reMedirSeSemVoz(contexto)
        nova.responder()

        assertThat(NotificationHelper.reminderAlerts(contexto)).isEqualTo(ReminderAlerts.OK)
    }

    /**
     * A re-medição não apaga o `false` velho antes de a resposta nova chegar: enquanto o motor
     * responde, o ajuste continua dizendo o que sabia. Zerar para `null` no meio faria a releitura
     * do resume responder OK e o cartão piscar — sumir e voltar na leitura seguinte.
     */
    @Test
    fun aRemedicaoNaoApagaOVereditoAntesDaResposta() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        sondaQueResponde(sabeFalar = false)

        val nova = SintetizadorQueResponde(sabeFalar = false)
        SondaDeVoz.criarVoz = { nova }
        SondaDeVoz.reMedirSeSemVoz(contexto)
        // O motor da re-medição ainda não respondeu.

        assertThat(NotificationHelper.reminderAlerts(contexto)).isEqualTo(ReminderAlerts.SEM_VOZ)
    }

    /** Quem tem voz não é re-medido: o conserto é para quem o cartão disse que estava sem voz. */
    @Test
    fun aRemedicaoIgnoraQuemTemVoz() {
        liberarNotificacoes()
        aparelhoAudivel()
        canalSaudavel()
        sondaQueResponde(sabeFalar = true)

        val nova = SintetizadorQueResponde(sabeFalar = true)
        SondaDeVoz.criarVoz = { nova }
        SondaDeVoz.reMedirSeSemVoz(contexto)

        assertThat(nova.perguntou).isFalse()
        assertThat(NotificationHelper.reminderAlerts(contexto)).isEqualTo(ReminderAlerts.OK)
    }

    /** Um sintetizador que só responde a "sabe falar?" — é a única pergunta da sonda. */
    private class SintetizadorQueResponde(private val sabeFalar: Boolean) : SintetizadorDeVoz {        var perguntou = false
        private var esperando: ((Boolean) -> Unit)? = null

        override fun quandoPronto(bloco: (Boolean) -> Unit) {
            perguntou = true
            esperando = bloco
        }

        fun responder() {
            esperando?.invoke(sabeFalar)
        }

        override fun falar(texto: String, id: String, aoSair: () -> Unit, aoTerminar: (String) -> Unit) {
        }

        override fun parar() {}

        override fun soltar() {}
    }

    private class SemTextos(base: Context) : ContextWrapper(base) {
        override fun getResources(): Resources = RecursosSemTextos(super.getResources())
    }

    private class RecursosSemTextos(base: Resources) :
        Resources(base.assets, base.displayMetrics, base.configuration) {

        override fun getString(id: Int): String = TEXTO + id

        override fun getString(id: Int, vararg formatArgs: Any?): String = TEXTO + id

        override fun getText(id: Int): CharSequence = TEXTO + id
    }

    private companion object {
        const val TEXTO = "texto"
    }
}

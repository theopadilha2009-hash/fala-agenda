package com.theopadilha.falaagenda.speech

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.vosk.Model

/**
 * A biblioteca nativa do Vosk não falha como `Exception`. O `Native.register` roda no bloco
 * estático de `LibVosk`, então a falha de ligação (`UnsatisfiedLinkError`) sai de lá como
 * `ExceptionInInitializerError`, e da segunda tentativa em diante como `NoClassDefFoundError`.
 * As três são `Error`: atravessavam o `catch (_: Exception)` do `transcribe`, subiam pela
 * thread `vosk-escuta` — que não tem handler — e em aparelho derrubam o processo. O app
 * fecharia sozinho no toque do microfone.
 *
 * A decisão é que isso é erro de motor, como o modelo indisponível: a escuta volta para o
 * motor do sistema, com a mensagem de sempre.
 *
 * O `model` injetado faz o papel do `load()` do motor offline (`Model(path)`, a única
 * entrada nativa da escuta) — sem ele não há como provocar a falha sem aparelho. O que
 * está provado aqui é a conversão: o `Error` chega como `onError` em vez de escapar pela
 * thread. A queda do processo em si é do handler padrão do Android, e isso aparelho é
 * quem prova.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VoskSpeechSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Sem a permissão o `start` nem abre a thread, e o teste passaria pelo motivo errado.
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // O motor condenado é estado do processo, e o sandbox dos testes é um só: quem
        // condena num caso não pode decidir a escolha do caso seguinte.
        VoiceEngine.forgetOfflineCondemnation()
    }

    @Test
    fun bibliotecaQueNaoCarregaViraErroDeMotoreNaoQueda() {
        val erros = escutar { throw UnsatisfiedLinkError("libvosk.so não carregou") }

        assertThat(erros).containsExactly(VoiceRetry.CLIENT)
    }

    /** É esta a forma da falha no aparelho: o erro de ligação sai do bloco estático. */
    @Test
    fun falhaDoBlocoEstaticoDaBibliotecaTambemEhErroDeMotor() {
        val erros = escutar {
            throw ExceptionInInitializerError(UnsatisfiedLinkError("libvosk.so não carregou"))
        }

        assertThat(erros).containsExactly(VoiceRetry.CLIENT)
    }

    /**
     * A falha de ligação não é do momento: a classe nativa fica marcada e toda escuta
     * seguinte falha igual, pagando a carga quebrada antes de cair no motor do sistema.
     * Para ela isso é o microfone lento em todo toque. Uma vez condenado, o offline sai
     * da escolha deste processo — o próximo toque já nasce no motor de sempre.
     */
    @Test
    fun falhaDeLigacaoCondenaOMotorOfflinePeloRestoDoProcesso() {
        escutar { throw UnsatisfiedLinkError("libvosk.so não carregou") }

        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                offlineAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.IN_APP_DEFAULT)
    }

    /** Microfone ocupado, modelo pela metade: a escuta seguinte pode dar certo. */
    @Test
    fun falhaQueNaoEhDeLigacaoNaoCondenaOMotorOffline() {
        escutar { throw IllegalStateException("microfone ocupado") }

        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                offlineAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.OFFLINE_VOSK)
    }

    /** Escuta até o fim e devolve os erros que chegaram na tela, na ordem. */
    private fun escutar(model: () -> Model): List<Int> {
        val erros = CopyOnWriteArrayList<Int>()
        val terminou = CountDownLatch(1)
        val source = VoskSpeechSource(
            context,
            model = model,
            onFinished = { terminou.countDown() },
        )

        source.start(
            object : SpeechSource.Listener {
                override fun onReady() = Unit
                override fun onSpeechBegin() = Unit
                override fun onPartial(text: String) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onFinal(text: String) = Unit
                override fun onError(code: Int) {
                    erros.add(code)
                }
            },
        )

        // A escuta roda em thread própria; os avisos voltam pela thread principal.
        assertThat(terminou.await(5, TimeUnit.SECONDS)).isTrue()
        shadowOf(Looper.getMainLooper()).idle()
        return erros
    }
}

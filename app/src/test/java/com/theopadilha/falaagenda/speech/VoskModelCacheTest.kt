package com.theopadilha.falaagenda.speech

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O motor offline tem que ser um só por processo. O modelo do Vosk tem 53 MB e abrir
 * leva segundos: enquanto cada resolução montava um `VoskOfflineSpeech` novo — e o
 * cache do modelo era campo dessa instância — o modelo era carregado do zero a cada
 * toque no microfone, e o anterior, que ninguém fechava, ficava inalcançável. Essa
 * carga acontece dentro do prazo de 10 s do preparo, e estourá-lo derrubava o
 * microfone no meio da fala dela.
 *
 * O `Model` nativo não entra aqui: nada nesta suíte passa por um modelo de verdade.
 * O que se prova é a resolução — mesma instância, cache que não envenena com a
 * instalação pendente, e o descarte do `release`. O fechamento do modelo em si
 * (`VoskOfflineSpeech.close`) só é exercitado no caminho em que ele nunca foi aberto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VoskModelCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // O motor é do processo, e estes testes dividem um: cada um começa do zero.
        VoskModel.release()
    }

    @Test
    fun duasEscutasSeguidasUsamOMesmoMotor() {
        instalarModelo()

        val primeiro = VoskModel.offlineSpeech(context)
        val segundo = VoskModel.offlineSpeech(context)

        assertThat(primeiro).isNotNull()
        assertThat(segundo).isSameInstanceAs(primeiro)
    }

    /** Dois toques ao mesmo tempo não podem abrir dois motores. */
    @Test
    fun doisToquesAoMesmoTempoUsamOMesmoMotor() {
        instalarModelo()
        val largada = CountDownLatch(1)
        val motores = Collections.synchronizedList(mutableListOf<OfflineSpeech?>())

        val threads = (1..2).map {
            Thread {
                largada.await()
                motores.add(VoskModel.offlineSpeech(context))
            }
        }
        threads.forEach { it.start() }
        largada.countDown()
        threads.forEach { it.join() }

        assertThat(motores).hasSize(2)
        assertThat(motores[1]).isSameInstanceAs(motores[0])
    }

    /**
     * O download pode ainda não ter terminado quando a escuta começa. Não haver motor
     * não pode virar cache: quem chega depois tem que valer na escuta seguinte, e não
     * herdar um "não tem" gravado.
     */
    @Test
    fun instalacaoPendenteNaoEnvenenaOCache() {
        assertThat(VoskModel.offlineSpeech(context)).isNull()
        assertThat(VoskModel.offlineSpeech(context)).isNull()

        instalarModelo()

        assertThat(VoskModel.offlineSpeech(context)).isNotNull()
    }

    /**
     * O processo avisou que está de saída e o modelo foi fechado: a próxima escuta abre
     * um motor novo em vez de falar com o que já não existe.
     */
    @Test
    fun releaseFechaEEsqueceOMotor() {
        instalarModelo()
        val primeiro = VoskModel.offlineSpeech(context)

        VoskModel.release()

        val segundo = VoskModel.offlineSpeech(context)
        assertThat(segundo).isNotNull()
        assertThat(segundo).isNotSameInstanceAs(primeiro)
    }

    /** Sem nada aberto o aviso de saída não tem o que fechar — e não pode estourar. */
    @Test
    fun releaseSemMotorNaoQuebra() {
        VoskModel.release()

        assertThat(VoskModel.offlineSpeech(context)).isNull()
    }

    private fun instalarModelo() {
        val dir = VoskModel.dir(context)
        dir.mkdirs()
        File(dir, "final.mdl").writeText("peso")
        File(dir, "mfcc.conf").writeText("--sample-frequency=16000")
    }
}

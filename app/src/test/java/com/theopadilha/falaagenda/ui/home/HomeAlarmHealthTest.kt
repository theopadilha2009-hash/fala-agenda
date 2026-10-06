package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A permissão de alarme exato não é do instante do salvamento: ela pode ser revogada depois,
 * e o cartão que só era alimentado no salvar sumia no toque e nunca voltava. A home passava a
 * prometer um aviso na hora que o aparelho já não deixava tocar.
 *
 * O que este teste prova é a metade do ViewModel: a leitura da permissão vem do aparelho (não
 * de um booleano guardado) e é refeita no `refreshAlarmHealth`, que é o que o resume da home
 * chama. Se a leitura virar constante, ou se o refresh não reler, este teste cai.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeAlarmHealthTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aHomeLeAExatidaoDoAlarmeDoAparelho() {        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val viewModel = HomeViewModel(AppContainer(context))
        assertThat(viewModel.canScheduleExact.value).isFalse()

        // A permissão foi ligada nos Ajustes e ela voltou: o resume relê e o cartão some.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel.refreshAlarmHealth()

        assertThat(viewModel.canScheduleExact.value).isTrue()
    }

    /**
     * O caminho de volta: a permissão estava dada e foi revogada depois (Android 14 revoga
     * alarmes exatos de quem fica meses sem abrir o app). Sem a releitura persistente, a home
     * continuava dizendo que estava tudo certo.
     */
    @Test
    fun permissaoRevogadaDepoisApareceNaProximaLeitura() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val viewModel = HomeViewModel(AppContainer(context))
        assertThat(viewModel.canScheduleExact.value).isTrue()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        viewModel.refreshAlarmHealth()

        assertThat(viewModel.canScheduleExact.value).isFalse()
    }
}

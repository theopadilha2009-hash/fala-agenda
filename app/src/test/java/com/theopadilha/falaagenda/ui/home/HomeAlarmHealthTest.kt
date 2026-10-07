package com.theopadilha.falaagenda.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.TestViewModelScopeRule
import com.theopadilha.falaagenda.di.AppContainer
import org.junit.Rule
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
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class HomeAlarmHealthTest {

    /**
     * Cada teste cria o próprio `HomeViewModel` (três, um por caso), e nenhum deles é desmontado
     * ao fim do caso: o `stateIn(…, WhileSubscribed(5_000))` da home e o `withContext(IO)` do
     * `init` ficam armados e cruzam a fronteira do teste. A regra cancela o escopo de todos os
     * rastreados antes de o `Dispatchers.Main` ser desmontado. Ver [TestViewModelScopeRule].
     */
    @get:Rule
    val escopo = TestViewModelScopeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aHomeLeAExatidaoDoAlarmeDoAparelho() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))
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

        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))
        assertThat(viewModel.canScheduleExact.value).isTrue()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        viewModel.refreshAlarmHealth()

        assertThat(viewModel.canScheduleExact.value).isFalse()
    }

    /**
     * O aviso transitório do salvamento ("o aviso pode atrasar alguns minutos") não pode
     * sobreviver à correção da permissão.
     *
     * Ele nasce quando o recado é salvo sem alarme exato. O único ponto que o baixava era o
     * toque no cartão transitório, que saiu — e a home o mostra com o booleano como chave,
     * então ele voltava a aparecer a cada retorno à tela, inclusive depois de ela conceder a
     * permissão: o app anunciava para sempre um problema que já não existia.
     */
    @Test
    fun avisoDoSalvamentoSomeQuandoAExatidaoVolta() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val viewModel = escopo.rastrear(HomeViewModel(AppContainer(context)))

        // O salvamento sem alarme exato deixou o aviso pendente.
        viewModel.setInexactWarning(true)
        assertThat(viewModel.inexactWarning.value).isTrue()

        // Ela concedeu a permissão e voltou: o resume relê e o aviso velho cai junto.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel.refreshAlarmHealth()

        assertThat(viewModel.inexactWarning.value).isFalse()
    }
}

package com.theopadilha.falaagenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.ExternalResource

/**
 * Monta e desmonta o `Dispatchers.Main` do caso — e, no meio disso, devolve ao teste o escopo
 * dos ViewModels que ele criou.
 *
 * O `viewModelScope` não é filho do teste: quem o cria é o ViewModel, e ninguém o cancela quando
 * o caso acaba. O que fica de pé é tudo que ele armou — o `stateIn(…, WhileSubscribed(5_000))` da
 * home, as esperas do `SpeechSession` e o `withContext(Dispatchers.IO)` do `init` (que faz uma
 * chamada de rede de verdade). Como o Main daqui é o `Dispatchers.Unconfined`, essas esperas são
 * timers de verdade: cinco segundos depois de o caso terminar, o cancelamento delas tenta
 * despachar para o `Dispatchers.Main`, que o `resetMain()` já desmontou.
 *
 * A exceção desse despacho não tem coletor. O `ExceptionCollector` do kotlinx-coroutines-test a
 * guarda e a entrega ao próximo `runTest`/`setMain` do processo — de uma classe *seguinte*,
 * sempre diferente. É assim que a suíte fica vermelha de forma aleatória, sempre num teste que
 * passa quando a classe roda isolada, e é o que torna inútil a regra da casa de "verde basta": o
 * vermelho não aponta para quem vazou.
 *
 * Cancelar antes do `resetMain()` devolve a corrotina para quem a criou. É a mesma receita que o
 * #73 aplicou ao `MonthSummaryFailureTest` — aqui ela vira regra, porque a forma do defeito não é
 * de uma classe: ela nasce em toda classe que cria um `HomeViewModel` e não o desmonta. Uma regra
 * é o que impede a décima de nascer igual.
 *
 * ## Como usar
 *
 * ```
 * @get:Rule val escopo = TestViewModelScopeRule()
 *
 * @Before fun setUp() { viewModel = escopo.rastrear(HomeViewModel(AppContainer(context))) }
 * ```
 *
 * A regra é dona do `setMain`/`resetMain` inteiros de propósito: o `after()` de um `TestRule` roda
 * **depois** dos `@After` da classe, então um `@After { resetMain() }` escrito à mão desmonta o
 * Main antes do cancelamento — que é exatamente a corrida que se quer evitar. Um caso que precise
 * de outro dispatcher para o Main não usa esta regra.
 *
 * ## Quando ela não é a mais externa, o defeito volta
 *
 * Nas classes que compõem a home, o `ComposeContentTestRule` também é regra, e o `after()` dele
 * descarta a composição — que é o gesto que deixa o `WhileSubscribed(5_000)` armado, porque é ali
 * que a assinatura da `agendaUi` vai a zero. A regra precisa ser a mais EXTERNA
 * (`RuleChain.outerRule(escopo).around(compose)`) para que o cancelamento aconteça com a
 * assinatura já fora: cancelando antes, o timer nasceria depois do cancelamento e voltaria a
 * cruzar a fronteira do teste.
 */
class TestViewModelScopeRule : ExternalResource() {

    private val rastreados = mutableListOf<ViewModel>()

    /**
     * Entrega o ViewModel e assume o compromisso de cancelar o escopo dele no fim do caso. É por
     * aqui que a classe declara o que criou: a regra não adivinha o que o teste montou.
     */
    fun <T : ViewModel> rastrear(viewModel: T): T {
        rastreados += viewModel
        return viewModel
    }

    override fun before() {
        // Sem confinamento: cada passo do ViewModel acontece na hora, e o teste espera o que é de
        // IO de verdade (o banco, a rede) em vez de fingir um relógio. É também o que torna as
        // esperas armadas timers reais — o preço, e o motivo de a regra existir.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    override fun after() {
        // Antes do `resetMain()`: é o cancelamento que faz o despacho pendente acontecer com o
        // Main ainda vivo. Depois dele, o mesmo despacho viraria a exceção sem coletor descrita
        // no KDoc da classe.
        rastreados.forEach { it.viewModelScope.cancel() }
        rastreados.clear()
        Dispatchers.resetMain()
    }
}

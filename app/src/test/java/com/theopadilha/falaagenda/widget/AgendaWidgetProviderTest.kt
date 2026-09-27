package com.theopadilha.falaagenda.widget

import android.app.Application
import android.content.Context
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class AgendaWidgetProviderTest {

    @Test
    fun leituraBoaMostraAProxima() {
        runBlocking {
            val snapshot = AgendaWidgetProvider.snapshotOrFallback(HOJE) { agendaCom("Vitamina") }
            assertThat(snapshot.title).isEqualTo("Vitamina")
            assertThat(snapshot.empty).isFalse()
        }
    }

    @Test
    fun leituraQuebradaViraAvisoEmVezDeWidgetEmBranco() {
        runBlocking {
            val snapshot = AgendaWidgetProvider.snapshotOrFallback(HOJE) { error("SQLiteDiskIOException") }
            assertThat(snapshot.title).isEqualTo("Não consegui ler a agenda")
            assertThat(snapshot.whenLabel).isNotEmpty()
            assertThat(snapshot.empty).isTrue()
        }
    }

    @Test
    fun temaGravadoLevaAsCoresParaOWidget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cores = WidgetColors(
            background = 0xFFF4F1E8.toInt(),
            text = 0xFF1A1A18.toInt(),
            muted = 0xFF5E5C56.toInt(),
            accent = 0xFF2F6B52.toInt(),
            onAccent = 0xFFD7EFE3.toInt(),
        )

        val acoes = acoesDe(AgendaWidgetProvider.views(context, proxima(), cores))

        assertThat(acoes).containsAtLeast(
            Acao(R.id.widget_root, "setBackgroundColor", cores.background),
            Acao(R.id.widget_title, "setTextColor", cores.text),
            Acao(R.id.widget_when, "setTextColor", cores.muted),
            Acao(R.id.widget_kicker, "setTextColor", cores.accent),
            Acao(R.id.widget_speak, "setTextColor", cores.onAccent),
            Acao(R.id.widget_speak, "setBackgroundColor", cores.accent),
        )
    }

    @Test
    fun seguirOCelularNaoImpoeCorNenhuma() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val metodos = acoesDe(AgendaWidgetProvider.views(context, proxima(), colors = null)).map { it.metodo }

        assertThat(metodos).containsNoneOf("setTextColor", "setBackgroundColor")
    }

    private fun proxima() = AgendaWidgetProvider.Snapshot(
        title = "Vitamina",
        whenLabel = "Hoje · 08:00",
        empty = false,
    )

    private data class Acao(val viewId: Int, val metodo: String, val valor: Any?)

    /**
     * O RemoteViews não expõe o que foi mandado pintar, então lemos a lista de ações que
     * ele mesmo guarda (mActions) antes de o launcher aplicar.
     */
    private fun acoesDe(remote: RemoteViews): List<Acao> {
        val lista = campoDe(remote, "mActions") as List<*>
        return lista.mapNotNull { bruta ->
            val acao = bruta ?: return@mapNotNull null
            val viewId = campoDe(acao, "viewId") as? Int ?: return@mapNotNull null
            val metodo = campoDe(acao, "methodName") as? String ?: return@mapNotNull null
            Acao(viewId, metodo, campoDe(acao, "value"))
        }
    }

    private fun campoDe(objeto: Any, nome: String): Any? {
        var classe: Class<*>? = objeto.javaClass
        while (classe != null) {
            val campo = runCatching { classe.getDeclaredField(nome) }.getOrNull()
            if (campo != null) {
                campo.isAccessible = true
                return campo.get(objeto)
            }
            classe = classe.superclass
        }
        return null
    }
}

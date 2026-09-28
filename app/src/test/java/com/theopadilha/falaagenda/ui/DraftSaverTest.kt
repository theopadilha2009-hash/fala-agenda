package com.theopadilha.falaagenda.ui

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * O `DraftSaver` é o que faz um recado em andamento atravessar a recriação da Activity —
 * na tela de confirmação e na caixa "Pode salvar?" da home. Sem ele, girar o aparelho com
 * a caixa aberta apagava a fala já reconhecida e parseada, sem erro nenhum.
 *
 * Os 15 campos do rascunho não ficam numa lista em memória: eles são o que o `Bundle` do
 * estado salvo guarda, e é por um `Parcel` de verdade que passam no giro. Daí os dois
 * níveis de teste aqui: o contrato campo a campo, e o round-trip real de `Bundle`/`Parcel`
 * — um tipo que o `Parcel` não sabe escrever só aparece no segundo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class DraftSaverTest {

    private val scope = SaverScope { true }

    /** O que o Bundle guarda: é isto que volta depois do giro. */
    private fun save(draft: ParsedTaskDraft?) = with(DraftSaver) { scope.save(draft) }

    private fun rascunhoCompleto() = ParsedTaskDraft(
        title = "tomar remédio",
        localDate = LocalDate.of(2026, 9, 28),
        localTime = LocalTime.of(8, 30),
        recurrence = RecurrenceRule(
            kind = RecurrenceKind.WEEKLY,
            weekDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            dayOfMonth = 3,
            monthOfYear = 11,
        ),
        confidence = 0.85,
        missingFields = setOf(MissingDraftField.TIME),
        ambiguous = true,
        transcript = "tomar remédio amanhã às oito e meia",
        notes = listOf("o horário ficou ambíguo"),
        source = DraftSource.AI,
        amountCents = 4590L,
        observation = "em jejum",
    )

    @Test
    fun rascunhoInteiroVoltaIgualDepoisDoGiro() {
        val draft = rascunhoCompleto()

        assertThat(DraftSaver.restore(save(draft)!!)).isEqualTo(draft)
    }

    /**
     * O giro de verdade: o que o `Bundle` do estado salvo guarda é escrito num `Parcel` e
     * lido de volta, do mesmo jeito que a recriação da Activity faz. É o `Bundle` que
     * percorre cada campo com `Parcel.writeValue`, e é aí que um tipo que o `Parcel` não
     * escreve estoura — em produção, com o recado na mão dela.
     */
    @Test
    fun oRascunhoAtravessaOGiroPassandoPorUmParcelDeVerdade() {
        val draft = rascunhoCompleto()
        val gravado = save(draft)!!

        val voltouDoGiro = bundleIdaEVolta(gravado)

        // Sem estas duas linhas um `Parcel` que não escrevesse nada (ou que devolvesse o
        // padrão) faria o teste passar por vazio — que é o disfarce que ele existe para
        // desfazer.
        assertThat(voltouDoGiro).isNotNull()
        assertThat(voltouDoGiro).isEqualTo(gravado)
        assertThat(DraftSaver.restore(voltouDoGiro!!)).isEqualTo(draft)
    }

    /** O mesmo caminho, campo a campo, como o `Bundle` o percorre. */
    @Test
    fun cadaCampoDoRascunhoPassaPeloWriteValueDoParcel() {
        val draft = rascunhoCompleto()
        val gravado = save(draft)!!

        val voltou = parcelIdaEVolta(gravado)

        assertThat(voltou).isEqualTo(gravado)
        assertThat(DraftSaver.restore(voltou)).isEqualTo(draft)
    }

    @Test
    fun rascunhoSemDataNemHoraSobrevive() {
        val draft = ParsedTaskDraft(
            title = "comprar pão",
            localDate = null,
            localTime = null,
            recurrence = RecurrenceRule(),
            confidence = 0.4,
            missingFields = setOf(MissingDraftField.DATE, MissingDraftField.TIME),
            ambiguous = false,
            transcript = "",
            source = DraftSource.MANUAL,
            amountCents = null,
            observation = "",
        )

        assertThat(DraftSaver.restore(bundleIdaEVolta(save(draft)!!)!!)).isEqualTo(draft)
    }

    @Test
    fun semRascunhoNaoSeRestauraNada() {
        // Lista vazia é "nenhum rascunho em andamento": o Bundle não pode fazer a caixa
        // "Pode salvar?" abrir sozinha depois de um giro sem recado nenhum.
        val saved = save(null)

        assertThat(saved).isEmpty()
        assertThat(DraftSaver.restore(saved!!)).isNull()
    }

    @Test
    fun bundleDeVersaoAntigaNaoDerrubaAAbertura() {
        // O Bundle salvo por uma versão antiga do app tem menos campos que o `draftFrom`
        // espera: restaurar isso não pode estourar na criação da tela.
        val antigo = arrayListOf<Any?>("tomar remédio", 1L, 2)

        assertThat(DraftSaver.restore(antigo)).isNull()
    }

    /**
     * O `Bundle` vai e volta por um `Parcel`, como na recriação da Activity. O
     * `DraftSaver` entrega uma `ArrayList` e é assim que o estado salvo a guarda (o
     * `putParcelableArrayList` do Compose, que só olha o tipo na hora de escrever).
     */
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun bundleIdaEVolta(values: ArrayList<Any?>): ArrayList<Any?>? {
        val bundle = Bundle()
        bundle.putParcelableArrayList(CHAVE, values as ArrayList<Parcelable>)
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            val voltou = requireNotNull(parcel.readBundle(DraftSaverTest::class.java.classLoader))
            voltou.getParcelableArrayList<Parcelable>(CHAVE) as ArrayList<Any?>?
        } finally {
            parcel.recycle()
        }
    }

    /** O campo a campo do `Bundle`: `Parcel.writeValue` na lista e `readValue` de volta. */
    private fun parcelIdaEVolta(values: ArrayList<Any?>): ArrayList<Any?> {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeValue(values)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            parcel.readValue(DraftSaverTest::class.java.classLoader) as ArrayList<Any?>
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val CHAVE = "draft"
    }
}

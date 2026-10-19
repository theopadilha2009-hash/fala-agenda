package com.theopadilha.falaagenda.reminders

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * A sonda que responde "o aparelho sabe falar com ela?" — e guarda a resposta.
 *
 * A pergunta já tem resposta no [VozDoAparelho]: o `quandoPronto` dele faz exatamente a dança que
 * separa "o aparelho tem TTS" de "o aparelho sabe falar em português do Brasil" (`setLanguage`, o
 * `isLanguageAvailable` de trás, o `onInit` que pode nunca chegar). Esta sonda **reusa o mesmo
 * caminho** — o mesmo motor, o mesmo critério de disponibilidade — em vez de reimplementar a
 * checagem e as duas versões divergirem depois.
 *
 * O resultado é **assíncrono por natureza** (`onInit` é callback) e **medido uma vez por processo**.
 * Quem acende a sonda é [NotificationHelper.reminderAlerts] — o ajuste é lido pela home e pelo
 * disparo do lembrete, que são exatamente os dois momentos em que a resposta importa. A exceção da
 * regra é o conserto: o aparelho **ganha** voz no meio de uma sessão — ela toca no cartão "sem a
 * voz", instala os dados e volta com o processo vivo —, e é exatamente esse fluxo que
 * [reMedirSeSemVoz] atende no retorno.
 *
 * Enquanto a resposta não chega, [sabe] devolve `null` — e **null não é "sem voz"**. Afirmar a
 * ausência antes do `onInit` daria um cartão falso no aparelho que tem a voz, que é o mesmo defeito
 * do "avisos OK" na direção oposta: um aviso que mente ensina a ser ignorado. Pelo mesmo motivo a
 * re-medição **não** apaga o `false` velho antes de a resposta nova chegar: zerar no meio faria a
 * releitura do resume responder OK e o cartão piscar — sumir e voltar na leitura seguinte.
 *
 * `internal` porque a sonda é detalhe do veredito dos avisos; quem troca o motor é o teste.
 */
internal object SondaDeVoz {
    @VisibleForTesting
    var criarVoz: (Context) -> SintetizadorDeVoz = { VozDoAparelho(it) }

    @Volatile
    private var veredito: Boolean? = null

    private var medindo = false

    /** O aparelho sabe falar? `null` é "ainda não medido" — e não uma resposta. */
    fun sabe(): Boolean? = veredito

    /**
     * Acende a medição, se ela ainda não tiver sido feita nem estiver a caminho. Volta na hora: o
     * veredito chega depois, pelo `quandoPronto` do motor, e fica guardado para as próximas
     * consultas.
     */
    fun medir(context: Context) {
        if (veredito != null || medindo) return
        medindo = true
        val voz = criarVoz(context)
        voz.quandoPronto { sabeFalar ->
            veredito = sabeFalar
            medindo = false
            // A sonda não fala: mediu, fechou. Sem isto o motor ficaria de pé — uma conexão de
            // serviço aberta para sempre — depois de responder a única pergunta que tinha.
            voz.soltar()
        }
    }

    /**
     * O ajuste disse que faltava voz e ela foi consertar: ao voltar da instalação, mede de novo.
     * É o fluxo que o cache por processo não alcançava — o motor do aparelho não ganha voz no meio
     * de uma sessão por conta própria, mas ela instala os dados e volta com o processo vivo, e o
     * `false` guardado manteria o cartão na tela até matarem o processo.
     *
     * Só quem estava **sem** voz é re-medido: o conserto é para quem o cartão disse que faltava.
     * Re-medir quem tem voz a cada volta desperdiçaria um motor por resume e abriria a porta do
     * falso negativo — uma leitura transitória ruim apagaria um veredito que era verdadeiro.
     *
     * Enquanto a resposta nova não chega, o `false` velho continua sendo a resposta de [sabe] — o
     * ajuste diz o que sabe até saber melhor, em vez de piscar.
     */
    fun reMedirSeSemVoz(context: Context) {
        // atalho: a re-medição só acontece no ON_RESUME da home; revisitar se a instalação da voz
        // puder terminar sem passar por lá (hoje o botão abre a tela do sistema e o retorno é ela).
        if (veredito != false || medindo) return
        medindo = true
        val voz = criarVoz(context)
        voz.quandoPronto { sabeFalar ->
            veredito = sabeFalar
            medindo = false
            voz.soltar()
        }
    }

    @VisibleForTesting
    fun reiniciar() {
        veredito = null
        medindo = false
    }
}

package com.theopadilha.falaagenda.reminders

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A voz do lembrete: o que ela fala, quantas vezes, e quando cala.
 *
 * A queixa dela é literal e repetida — "o áudio nunca funciona". O #77 deu som de alarme ao
 * canal; isto aqui é a outra metade. Uma notificação sonora ainda depende de ela olhar a tela, e
 * com o celular na sala e ela na cozinha o plim não é um alarme.
 *
 * O `TextToSpeech` do Android não existe no Robolectric e o `onInit` dele é assíncrono, então
 * nada aqui instancia um motor: a política — falar duas vezes com uma pausa, calar sozinha, calar
 * quando ela responde, não falar quando o aparelho não sabe falar — é exercitada contra a costura
 * [SintetizadorDeVoz] e um relógio manual. É a política que tem defeito quando tem.
 *
 * Os dois primeiros testes são o coração do arquivo, e são opostos de propósito: a voz tem que
 * **repetir** (uma vez só, na cozinha, não se ouve) e tem que **parar** (a que continua dizendo
 * "está na hora do remédio" depois que ela já tomou é pior que o silêncio).
 */
class AlarmeQueFalaTest {
    private val ocorrencia = "remedio:2026-10-07"
    private val frase = "Está na hora. Tomar remédio."

    /**
     * Um motor de mentira que obedece a quem manda no teste: só fica pronto quando o teste diz,
     * só termina uma fala quando o teste diz, e conta o que ouviu.
     */
    private class SintetizadorFalso(
        private val sabeFalar: Boolean = true,
        private val avisaQueSaiu: Boolean = true,
    ) : SintetizadorDeVoz {
        val falas = mutableListOf<String>()
        var paradas = 0
        var soltadas = 0

        /**
         * Quantas vezes a política **pediu** uma fala, inclusive as que não saíram. É o que separa
         * "a escada andou" de "a escada parou": [falas] só conta o que saiu, e num motor que recusa
         * ele fica vazio mesmo com a política insistindo.
         */
        var tentativas = 0
        private var esperando: ((Boolean) -> Unit)? = null
        private val terminam = mutableListOf<(String) -> Unit>()

        override fun quandoPronto(bloco: (Boolean) -> Unit) {
            esperando = bloco
        }

        /** O `onInit` do aparelho chegou, com a resposta que este teste escolheu. */
        fun ficouPronto() {
            esperando?.invoke(sabeFalar)
        }

        override fun falar(
            texto: String,
            id: String,
            aoSair: () -> Unit,
            aoTerminar: (String) -> Unit,
        ) {
            tentativas++
            if (!avisaQueSaiu) {
                // A frase foi aceita e nenhum som saiu — o motor falhou depois do aceite, pelo
                // `onError`. O fim é avisado, a saída não.
                aoTerminar(id)
                return
            }
            falas += texto
            terminam += aoTerminar
            // O motor começou a falar — o `onStart` dele.
            aoSair()
        }

        /** A [i]-ésima fala chegou ao fim, como o motor avisaria pelo `onDone`. */
        fun terminouA(i: Int) {
            terminam[i]("fala-$i")
        }

        override fun parar() {
            paradas++
        }

        override fun soltar() {
            soltadas++
        }
    }

    /**
     * O relógio do teste. O `AvisoFalado` agenda a segunda repetição e o prazo que fecha a conta
     * por um agendador injetado; sem isto, testar "cala sozinho" pediria esperar de verdade.
     */
    private class Relogio {
        private var agora = 0L
        private var ordem = 0
        private val fila = mutableListOf<Triple<Long, Int, () -> Unit>>()

        fun agendar(ms: Long, bloco: () -> Unit) {
            fila += Triple(agora + ms, ordem++, bloco)
        }

        fun avancar(ms: Long) {
            val alvo = agora + ms
            while (true) {
                val proximo = fila.filter { it.first <= alvo }
                    .minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                fila.remove(proximo)
                agora = proximo.first
                proximo.third()
            }
            agora = alvo
        }
    }

    private class Aviso(
        val sintetizador: SintetizadorFalso,
        val relogio: Relogio,
    ) {
        var encerrou = false

        /** Quantas vezes a política disse "está falando". */
        var anuncios = 0

        val aviso = AvisoFalado(
            voz = sintetizador,
            agendar = relogio::agendar,
            aoFalar = { anuncios++ },
            aoEncerrar = { encerrou = true },
        )
    }

    private fun novoAviso(sabeFalar: Boolean = true, avisaQueSaiu: Boolean = true): Aviso =
        Aviso(SintetizadorFalso(sabeFalar, avisaQueSaiu), Relogio())

    // --- 1. Repete, e só duas vezes -----------------------------------------------------------

    /**
     * A frase sai **duas vezes**, com uma pausa entre elas. Uma vez só, com o celular na sala e
     * ela na cozinha, é o plim que ninguém ouve — que é a queixa que este trabalho existe para
     * resolver. E a segunda vez não pode ser uma terceira: "não fale para sempre" é parte do
     * mesmo pedido, e uma voz que não cala é um alarme que ela não consegue desligar.
     */
    @Test
    fun falaAFraseDuasVezesEDepoisCalaSozinha() {
        val aviso = novoAviso()

        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()
        assertThat(aviso.sintetizador.falas).hasSize(1)

        aviso.sintetizador.terminouA(0)
        aviso.relogio.avancar(duracaoFaladaMs(frase) + PAUSA_ENTRE_AS_FALAS_MS + 1)
        assertThat(aviso.sintetizador.falas).hasSize(2)

        aviso.sintetizador.terminouA(1)
        assertThat(aviso.encerrou).isTrue()

        // Nem daqui a um minuto: a voz calou sozinha, e o serviço saiu do primeiro plano.
        aviso.relogio.avancar(60_000)
        assertThat(aviso.sintetizador.falas).hasSize(2)
    }

    // --- 2. Cala quando ela responde ----------------------------------------------------------

    /**
     * Ela tocou em "Concluir" ou "Adiar": a voz para **na hora**, e a segunda repetição não vem
     * nem que o motor avise que a primeira acabou. Continuar dizendo "está na hora do remédio"
     * depois que ela já tomou é pior que o silêncio.
     */
    @Test
    fun paraNaHoraQuandoElaResponde() {
        val aviso = novoAviso()
        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()
        assertThat(aviso.sintetizador.falas).hasSize(1)

        assertThat(aviso.aviso.parar(ocorrencia)).isTrue()

        aviso.sintetizador.terminouA(0)
        aviso.relogio.avancar(60_000)
        assertThat(aviso.sintetizador.falas).hasSize(1)
        assertThat(aviso.sintetizador.paradas).isAtLeast(1)
        assertThat(aviso.sintetizador.soltadas).isAtLeast(1)
    }

    /**
     * O toque é de OUTRO lembrete: a voz que está falando não é dela, e não pode ser calada por
     * uma ação que não é sobre ela. Sem esta guarda, concluir qualquer tarefa em qualquer lugar do
     * app cortaria o aviso falado do remédio que está tocando agora.
     */
    @Test
    fun oToqueDeOutroLembreteNaoCalaEstaVoz() {
        val aviso = novoAviso()
        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()

        assertThat(aviso.aviso.parar("outro:2026-10-07")).isFalse()

        aviso.sintetizador.terminouA(0)
        aviso.relogio.avancar(duracaoFaladaMs(frase) + PAUSA_ENTRE_AS_FALAS_MS + 1)
        assertThat(aviso.sintetizador.falas).hasSize(2)
    }

    // --- 3. Não fala o que o aparelho não sabe falar -------------------------------------------

    /**
     * O serviço morreu por fora e ninguém vai ouvir o fim da frase. A voz cala e o motor fecha —
     * **sem** avisar quem criou, porque quem criou já não existe e acordá-lo de volta por um
     * `stopSelf` seria pior que o silêncio.
     *
     * É esta a metade de "não deixe a voz presa": sem ela, o motor sobrevive ao serviço que o criou
     * e continua dizendo "está na hora do seu remédio" para uma notificação que já saiu da barra.
     */
    @Test
    fun oServicoMortoPorForaCalaOVozESoltaOMotorSemAvisar() {
        val aviso = novoAviso()
        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()

        aviso.aviso.abandonar()

        assertThat(aviso.sintetizador.paradas).isAtLeast(1)
        assertThat(aviso.sintetizador.soltadas).isAtLeast(1)
        assertThat(aviso.encerrou).isFalse()
        // E a conta que ainda estava no relógio não fala mais nada.
        aviso.relogio.avancar(60_000)
        assertThat(aviso.sintetizador.falas).hasSize(1)
    }


    /**
     * Sem motor de voz, ou sem voz em português, **não fala nada e não trava**: o som de alarme do
     * #77 continua sendo o aviso, e o serviço sai do primeiro plano em vez de ficar de pé para
     * sempre segurando o processo por causa de uma voz que nunca vai sair.
     */
    @Test
    fun semVozNoAparelhoNaoFalaENaoTrava() {
        val aviso = novoAviso(sabeFalar = false)

        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()

        assertThat(aviso.sintetizador.falas).isEmpty()
        assertThat(aviso.encerrou).isTrue()
    }

    /**
     * A segunda repetição saiu, e o motor **não avisa** que ela acabou — motor de fabricante que
     * não chama o `onDone`, ou aviso engolido no caminho. A conta fecha pelo prazo mesmo assim: um
     * serviço que fica de pé esperando um aviso que não vem segura o processo à toa e deixa na
     * barra uma notificação que não quer dizer mais nada.
     */
    @Test
    fun aUltimaFalaFechaAContaMesmoSemOAvisoDoMotor() {
        val aviso = novoAviso()
        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()
        aviso.sintetizador.terminouA(0)
        aviso.relogio.avancar(duracaoFaladaMs(frase) + PAUSA_ENTRE_AS_FALAS_MS + 1)
        assertThat(aviso.sintetizador.falas).hasSize(2)

        // O `onDone` da segunda fala nunca vem.
        aviso.relogio.avancar(duracaoFaladaMs(frase) + 1)

        assertThat(aviso.encerrou).isTrue()
    }

    /**
     * O `onInit` do Android pode simplesmente nunca chegar — motor pendurado, aparelho sem TTS,
     * `bindService` recusado. O prazo é nosso, não do motor: passado ele, a conta fecha e o
     * serviço sai do primeiro plano mesmo sem nunca ter falado.
     */
    @Test
    fun motorQueNuncaFicaProntoNaoPrendeOServico() {
        val aviso = novoAviso()

        aviso.aviso.falar(ocorrencia, frase)
        // O `onInit` nunca vem.
        aviso.relogio.avancar(prazoTotalDaFalaMs(frase) + 1)

        assertThat(aviso.sintetizador.falas).isEmpty()
        assertThat(aviso.encerrou).isTrue()
    }

    /**
     * O mesmo lembrete dispara de novo (a escada de repetições). A voz recomeça do zero — é uma
     * insistência nova, e o motor avisa de novo que está pronto —, e o aviso antigo do motor, que
     * chega atrasado, não pode fazer a política andar para trás: ele é de uma fala que já foi
     * substituída.
     */
    @Test
    fun oDisparoDeNovoRecomecaEIgnoraOAvisoVelhoDoMotor() {
        val aviso = novoAviso()
        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()
        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()
        assertThat(aviso.sintetizador.falas).hasSize(2)

        // O fim da fala VELHA chega agora. Não é ele que manda a política repetir: quem conta as
        // falas do disparo atual é a fala nova, e ela ainda está na primeira.
        aviso.sintetizador.terminouA(0)
        aviso.relogio.avancar(60_000)

        assertThat(aviso.sintetizador.falas).hasSize(2)
    }

    // --- 4. Só anuncia a fala que saiu de fato --------------------------------------------------

    /**
     * A fala saiu: a política anuncia. É a outra metade, e é ela que impede o conserto por omissão
     * — calar o anúncio de vez passaria no teste de baixo e deixaria a barra sem dizer nada.
     */
    @Test
    fun anunciaQuandoAFalaSai() {
        val aviso = novoAviso()

        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()

        assertThat(aviso.anuncios).isEqualTo(1)
    }

    /**
     * A frase foi **aceita** e nenhum som saiu — o motor falhou depois do aceite, pelo `onError`,
     * que a costura traduz para o mesmo `aoTerminar` do `onDone`. A política **não** anuncia: a
     * barra só pode dizer "avisando em voz alta" quando a voz saiu.
     */
    @Test
    fun naoAnunciaQuandoAAceitacaoNaoViraFala() {
        val aviso = novoAviso(avisaQueSaiu = false)

        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()

        assertThat(aviso.anuncios).isEqualTo(0)
    }

    /**
     * A escada **anda** pelo fim mesmo quando a fala não sai. O motor avisa o fim (`onError`) sem
     * ter avisado a saída, e a política tem que seguir: quem manda a segunda tentativa é o
     * [AvisoFalado.terminou], não o anúncio da barra. Sem isso o aviso ficaria de pé até o prazo
     * por causa de um motor que avisou o fim e não a saída — e a segunda fala, que é o motivo de
     * [FALAS_POR_AVISO] existir, nunca seria pedida.
     *
     * O oráculo é o contador de **tentativas**, e não [SintetizadorFalso.falas]: num motor que
     * recusa, o que saiu fica vazio mesmo com a política insistindo. É por isso que a asserção
     * anterior (só o anúncio) não provava nada sobre a escada.
     */
    @Test
    fun aEscadaAndaPeloFimMesmoQuandoAFalaNaoSai() {
        val aviso = novoAviso(avisaQueSaiu = false)

        aviso.aviso.falar(ocorrencia, frase)
        aviso.sintetizador.ficouPronto()
        assertThat(aviso.sintetizador.tentativas).isEqualTo(1)

        // O fim da primeira chegou: a política pede a segunda tentativa.
        aviso.relogio.avancar(PAUSA_ENTRE_AS_FALAS_MS + 1)

        assertThat(aviso.sintetizador.tentativas).isEqualTo(2)
        assertThat(aviso.anuncios).isEqualTo(0)
    }

    // --- 5. Não fala o lembrete que não está na tela --------------------------------------------

    /**
     * A voz entra junto do aviso que **realmente saiu**. Falar um lembrete que a tela não mostra
     * — canal desligado, permissão negada, sistema recusando — mandaria ela procurar na barra uma
     * notificação que não existe, e no remédio isso é o remédio que não toca.
     */
    @Test
    fun soFalaQuandoOAvisoSaiuDeFato() {
        assertThat(deveFalarOlembrete(NotificationHelper.ReminderDelivery.POSTED)).isTrue()
        assertThat(deveFalarOlembrete(NotificationHelper.ReminderDelivery.BLOCKED)).isFalse()
        assertThat(deveFalarOlembrete(NotificationHelper.ReminderDelivery.FAILED)).isFalse()
    }
}

package com.theopadilha.falaagenda.reminders

/**
 * Como este aplicativo pede voz ao aparelho.
 *
 * Existe por dois motivos, e os dois são de teste. O `TextToSpeech` do Android não existe no
 * Robolectric, e o `onInit` dele é assíncrono — não dá para instanciá-lo num teste unitário. A
 * costura também deixa a política abaixo sem nenhum `import` de Android: o que decide quando a voz
 * fala e quando ela cala não precisa saber que existe um motor de voz de verdade.
 *
 * O motor real é o [VozDoAparelho], e é ele que traduz os avisos do `TextToSpeech` para estas
 * quatro chamadas.
 */
internal interface SintetizadorDeVoz {
    /**
     * O motor terminou de subir. O bloco recebe `false` quando o aparelho não sabe falar em
     * português do Brasil — aí não há o que dizer, e a voz tem que sair do caminho sem travar.
     *
     * O bloco é chamado **uma vez**. Quem chama [falar] de novo depois disso recebe a resposta
     * outra vez, porque cada disparo é uma tentativa nova.
     */
    fun quandoPronto(bloco: (Boolean) -> Unit)

    /**
     * Diz [texto]. [id] volta em [aoTerminar] para quem chamou saber se aquele fim é da fala que
     * ainda interessa; uma fala nova substitui a anterior.
     */
    fun falar(texto: String, id: String, aoTerminar: (String) -> Unit)

    /** Cala agora, sem esperar a frase acabar. */
    fun parar()

    /** Fecha o motor. Depois disto este sintetizador não fala mais. */
    fun soltar()
}

/** Quantas vezes a frase é dita por disparo. Uma vez só, com o celular na sala, não se ouve. */
internal const val FALAS_POR_AVISO = 2

/** A pausa entre as duas falas: separa as repetições sem virar um silêncio que parece o fim. */
internal const val PAUSA_ENTRE_AS_FALAS_MS = 600L

/**
 * O prazo para o motor de voz ficar pronto. O `onInit` do Android pode simplesmente nunca chegar
 * — motor pendurado, aparelho sem sintetizador, `bindService` recusado. O prazo é nosso, não dele:
 * passado ele, a conta fecha e o serviço sai do primeiro plano mesmo sem nunca ter falado.
 */
internal const val PRAZO_DO_MOTOR_MS = 6_000L

/** O teto de uma fala, estimado pelo tamanho da frase — o `onDone` pode não vir. */
internal const val MS_POR_CARACTERE = 80L

internal fun duracaoFaladaMs(frase: String): Long = MS_POR_CARACTERE * frase.length

/**
 * O teto do aviso inteiro: o motor subir, as duas falas e a pausa entre elas. É o prazo que
 * garante que o serviço sai do primeiro plano mesmo se o motor não avisar nada do que devia.
 */
internal fun prazoTotalDaFalaMs(frase: String): Long =
    PRAZO_DO_MOTOR_MS + FALAS_POR_AVISO * duracaoFaladaMs(frase) + PAUSA_ENTRE_AS_FALAS_MS

/**
 * A voz do lembrete de remédio: o que ela fala, quantas vezes, e quando cala.
 *
 * A queixa dela é literal e repetida — "o áudio nunca funciona". O canal do lembrete já toca o som
 * de alarme do aparelho; isto aqui é a outra metade. Uma notificação sonora ainda depende de ela
 * olhar a tela, e com o celular na sala e ela na cozinha o plim não é um alarme.
 *
 * A frase sai **duas** vezes, com uma pausa curta, e depois a voz cala sozinha. As duas metades
 * disso são a mesma decisão: uma vez só não se ouve, e uma voz que não cala é um alarme que ela
 * não consegue desligar. E ela cala na hora quando o toque dela chega: continuar dizendo "está na
 * hora do remédio" depois que ela já tomou é pior que o silêncio.
 *
 * Não há espera aqui dentro: quem chama não bloqueia em nada disto. É por isso que o receiver do
 * alarme pode disparar a voz sem estourar o `goAsync` dele.
 */
internal class AvisoFalado(
    private val voz: SintetizadorDeVoz,
    private val agendar: (Long, () -> Unit) -> Unit,
    /**
     * A primeira fala deste aviso saiu de fato. Quem constrói o serviço usa isto para a barra só
     * afirmar "avisando em voz alta" depois de a voz sair: um motor que não subiu deixava a
     * notificação afirmando a fala durante todo o prazo, sem uma palavra.
     *
     * O padrão vazio é para os testes da política, que não têm barra nenhuma para atualizar.
     */
    private val aoFalar: () -> Unit = {},
    /**
     * Recebe este aviso, e não nada: um disparo novo do mesmo lembrete substitui o antigo, e o
     * encerramento atrasado do velho não pode derrubar a voz que está falando agora.
     */
    private val aoEncerrar: (AvisoFalado) -> Unit,
) {
    /** O lembrete que está falando. É ele que separa o toque dela do toque de outro lembrete. */
    private var ocorrencia: String? = null
    private var frase = ""
    private var falas = 0
    private var encerrado = false

    /**
     * O id da fala que ainda interessa, e o contador que o produz. O contador **não** reinicia a
     * cada disparo de propósito: a escada de repetições dispara o mesmo lembrete de novo, e um id
     * reaproveitado faria o aviso atrasado da fala antiga ser confundido com o da nova — a política
     * andaria para trás por causa de uma fala que já não existe.
     */
    private var idDaVez = ""
    private var contador = 0

    /**
     * Começa a falar [texto] para [occurrenceId]. Um disparo novo do mesmo lembrete recomeça do
     * zero — é uma insistência nova — e a fala anterior é substituída.
     */
    fun falar(occurrenceId: String, texto: String) {
        ocorrencia = occurrenceId
        frase = texto
        falas = 0
        encerrado = false
        voz.quandoPronto { sabeFalar -> if (sabeFalar) repetir() else encerrar() }
        agendar(prazoTotalDaFalaMs(texto)) { encerrar() }
    }

    /**
     * O serviço morreu por fora — o sistema matou o processo, ou o usuário parou o aplicativo — e
     * ninguém vai ouvir o fim desta fala. Cala e fecha o motor, **sem** avisar quem criou: o
     * serviço já está morto, e acordá-lo de volta por um `stopSelf` seria pior que o silêncio.
     *
     * É este caminho que impede a voz de ficar presa: sem ele, um `TextToSpeech` sobrevive ao
     * serviço que o criou e continua falando para uma notificação que já sumiu da barra.
     */
    fun abandonar() {
        if (encerrado) return
        encerrado = true
        voz.parar()
        voz.soltar()
    }

    /**
     * Ela tocou em "Concluir" ou "Adiar". Cala agora, se a voz que está no ar é deste lembrete.
     *
     * Devolve `false` quando não havia o que calar — outra ocorrência, ou uma voz que já tinha
     * acabado. Sem esta guarda, concluir qualquer tarefa em qualquer lugar do aplicativo cortaria o
     * aviso falado do remédio que está tocando agora.
     */
    fun parar(occurrenceId: String): Boolean {
        if (encerrado || ocorrencia != occurrenceId) return false
        encerrar()
        return true
    }

    private fun repetir() {
        if (encerrado) return
        val id = "fala-${contador++}"
        idDaVez = id
        falas++
        voz.falar(frase, id, ::terminou)
        // A fala saiu: é daqui para baixo que "avisando em voz alta" é verdade. Vem depois do
        // `falar`, e não antes, porque é o motor que acabou de receber a frase.
        aoFalar()
        // A última fala não conta com o `onDone` dela para fechar a conta: o motor pode não avisar,
        // e um serviço que fica de pé esperando um aviso que não vem segura o processo à toa.
        if (falas >= FALAS_POR_AVISO) agendar(duracaoFaladaMs(frase)) { encerrar() }
    }

    private fun terminou(id: String) {
        // Aviso de uma fala já substituída: ela não é mais a voz do lembrete.
        if (encerrado || id != idDaVez) return
        if (falas >= FALAS_POR_AVISO) {
            encerrar()
        } else {
            agendar(PAUSA_ENTRE_AS_FALAS_MS) { repetir() }
        }
    }

    private fun encerrar() {
        if (encerrado) return
        encerrado = true
        voz.parar()
        voz.soltar()
        aoEncerrar(this)
    }
}

/**
 * Quem está falando agora, para o toque dela poder calar a voz sem passar pelo serviço.
 *
 * O serviço e o receiver do toque rodam no mesmo processo — o serviço é declarado sem
 * `android:process` —, então a referência direta é o caminho mais curto e não tem o que falhar no
 * meio. Se o processo tiver sido morto, isto é `null` e não há voz para calar: o silêncio já veio.
 */
internal object VozDoLembrete {
    @Volatile
    private var atual: AvisoFalado? = null

    fun registrar(aviso: AvisoFalado?) {
        atual = aviso
    }

    fun parar(occurrenceId: String): Boolean = atual?.parar(occurrenceId) ?: false
}

/**
 * A voz entra junto do aviso que **realmente saiu**.
 *
 * Falar um lembrete que a tela não mostra — canal desligado, permissão negada, sistema recusando —
 * mandaria ela procurar na barra uma notificação que não existe, e no remédio isso é o remédio que
 * não toca. Quem decide é o desfecho da notificação, e não a tentativa.
 */
internal fun deveFalarOlembrete(entrega: NotificationHelper.ReminderDelivery): Boolean =
    entrega == NotificationHelper.ReminderDelivery.POSTED

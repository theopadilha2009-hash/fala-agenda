package com.theopadilha.falaagenda.reminders

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.theopadilha.falaagenda.R

private const val TAG = "VozDoLembrete"

/**
 * O id da notificação do serviço. Um só para o aplicativo inteiro, e não um por ocorrência: só
 * existe uma voz falando por vez — um disparo novo substitui o anterior —, e um id por ocorrência
 * só deixaria notificações órfãs na barra quando o processo fosse morto no meio de uma frase.
 */
private const val ID_DA_NOTIFICACAO_DA_VOZ = 0x7A0F

/**
 * O serviço que fala o lembrete em voz alta.
 *
 * Ele existe por causa de um fato do Android: sem um serviço em primeiro plano, o processo que
 * acabou de receber o alarme é candidato a ser morto assim que o receiver devolver o `goAsync` — e
 * a frase morreria no meio, que é exatamente a queixa dela. O primeiro plano é o que dá ao
 * aplicativo o direito de terminar de falar com o app fechado e a tela apagada.
 *
 * A voz é pedida por [falar] e **nunca esperada** por quem chamou: o receiver do alarme tem 8
 * segundos para fechar o trabalho dele e a fala é mais longa que isso. Aqui dentro também não se
 * bloqueia em nada — quem conta o tempo é o [AvisoFalado], pelo `Handler` da thread principal, e o
 * serviço sai sozinho quando a última frase termina.
 *
 * O motor de voz é construído por [criarVoz], que é trocável para o teste: o `TextToSpeech` do
 * Android não existe no Robolectric e o `onInit` dele é assíncrono.
 */
class LembreteFaladoService : Service() {
    /**
     * `internal` porque a costura do sintetizador é um detalhe de dentro do módulo — quem chama
     * isto é o receiver do alarme, e quem troca o motor é o teste. Nada aqui precisa atravessar a
     * fronteira do módulo, e o manifesto só exige que a CLASSE seja pública.
     */
    internal companion object {
        /** O título da tarefa, para a frase. Chave própria: o intent do alarme não traz o texto. */
        const val EXTRA_TITULO = "titulo_falado"

        @VisibleForTesting
        var criarVoz: (Context) -> SintetizadorDeVoz = { VozDoAparelho(it) }

        /**
         * A costura do primeiro plano, e o mesmo motivo do [criarVoz]: o Robolectric nunca recusa
         * um `startForeground`. Sem esta porta, o caminho da recusa — o único em que o serviço
         * recusa um disparo com uma voz de outro no ar — não teria como ser exercitado. O padrão é
         * o de verdade; quem troca é o teste.
         */
        @VisibleForTesting
        var subirEmPrimeiroPlano: (LembreteFaladoService) -> Boolean = {
            it.tentarSubirEmPrimeiroPlano()
        }

        fun intentPara(context: Context, occurrenceId: String, titulo: String): Intent =
            Intent(context, LembreteFaladoService::class.java).apply {
                putExtra(AlarmIds.EXTRA_OCCURRENCE_ID, occurrenceId)
                putExtra(EXTRA_TITULO, titulo)
            }

        /**
         * Pede a voz e volta na hora. Quem chama é o receiver do alarme, dentro do `goAsync` dele:
         * esperar a fala ali estouraria o prazo do receiver e o sistema mataria o processo com o
         * trabalho pendente.
         *
         * Falhar aqui não é fatal, e por isso a exceção morre neste `try`: o som de alarme do
         * lembrete já saiu e continua sendo o aviso. Uma voz que não pôde subir não pode derrubar o
         * receiver que acabou de mostrar o lembrete na tela.
         */
        fun falar(context: Context, occurrenceId: String, titulo: String) {
            try {
                context.startForegroundService(intentPara(context, occurrenceId, titulo))
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível subir a voz do lembrete $occurrenceId", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var aviso: AvisoFalado? = null

    /** O disparo que está sendo atendido agora, para a notificação abrir a tarefa certa. */
    private var ocorrenciaEmCurso: String? = null

    /**
     * A voz deste disparo já saiu de fato? A notificação do primeiro plano só pode dizer que está
     * falando depois disto — antes, ela é só a voz do lembrete sendo preparada.
     */
    private var vozFalando = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val occurrenceId = intent?.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID)
        val titulo = intent?.getStringExtra(EXTRA_TITULO)
        if (occurrenceId.isNullOrBlank() || titulo.isNullOrBlank()) {
            // Uma voz sem frase seria um primeiro plano sem razão de ser, segurando o processo por
            // nada e mostrando na barra uma notificação que não diz coisa nenhuma.
            Log.w(TAG, "Voz pedida sem os dados do lembrete")
            sairSeNaoHavoz()
            return START_NOT_STICKY
        }
        // O disparo em curso vem ANTES do primeiro plano: é o `notificacao()` que monta o
        // `PendingIntent` do toque dela, e ele é montado lá dentro do `startForeground`. Atribuído
        // depois, o primeiro disparo saía com o id vazio, e o toque dela caía num id que não
        // existe — a home respondia "Esta tarefa não está mais na agenda" para a tarefa que estava
        // tocando naquele momento.
        val anterior = ocorrenciaEmCurso
        ocorrenciaEmCurso = occurrenceId
        // E a afirmação de que está falando não atravessa disparos: quem a levanta é
        // [anunciarQueEstaFalando], quando a fala sai. Sem este zero aqui, o segundo disparo
        // nascia dizendo que estava falando por conta do primeiro.
        vozFalando = false
        if (!subirEmPrimeiroPlano(this)) {
            // O primeiro plano recusou este disparo: ele não assume o posto. Sem isto o toque na
            // notificação da voz que ainda está falando abria a ocorrência do disparo que acabou
            // de falhar — o toque dela cairia na tarefa errada.
            ocorrenciaEmCurso = anterior
            sairSeNaoHavoz()
            return START_NOT_STICKY
        }
        abandonarOAnterior()
        val novo = AvisoFalado(
            voz = criarVoz(this),
            // O relógio é o da thread principal, a mesma em que o `TextToSpeech` avisa que
            // terminou: sem isso a política seria mexida de duas threads ao mesmo tempo.
            agendar = { ms, bloco -> handler.postDelayed(bloco, ms) },
            // A barra passa a dizer que está falando quando a primeira fala sai, e não quando ela
            // é pedida: motor que nunca sobe deixava a notificação afirmando "Avisando em voz
            // alta" durante todo o prazo, sem uma fala sequer.
            aoFalar = { anunciarQueEstaFalando() },
            // O encerramento que derruba o serviço tem que ser o do aviso que ainda está no ar.
            // Hoje isto é uma segunda trava: o `abandonarOAnterior` acima já tira do `Handler` a
            // conta do aviso velho e o abandona antes de o novo assumir, então o velho nem chega a
            // pedir o encerramento. Fica assim de propósito — é a invariante escrita no código, e o
            // review mediu que, sem ela E sem o `abandonarOAnterior`, dois testes caem.
            aoEncerrar = { encerrado -> if (aviso === encerrado) encerrar() },
        )
        aviso = novo
        VozDoLembrete.registrar(novo)
        novo.falar(occurrenceId, getString(R.string.reminder_spoken, titulo))
        return START_NOT_STICKY
    }

    /**
     * O disparo anterior sai de cena por inteiro antes de o novo assumir.
     *
     * `abandonar` cala e solta o motor dele — é o mesmo caminho do `onDestroy` —, e a limpeza do
     * `Handler` apaga os `postDelayed` que ele ainda tinha na fila. Sem isso o aviso antigo ficava
     * de pé com o motor aberto e a escada de repetições viva, e a segunda metade da frase dele
     * saía por cima da voz nova. Trocar só a referência global ([VozDoLembrete.registrar]) não
     * bastava: ela deixa o aviso velho sem ninguém que o alcance, e ele continua falando.
     */
    private fun abandonarOAnterior() {
        handler.removeCallbacksAndMessages(null)
        aviso?.abandonar()
        aviso = null
    }

    /**
     * A primeira fala saiu: a barra agora pode dizer que o celular está falando. A atualização é
     * um `startForeground` de novo, com o mesmo id — é assim que se troca a notificação de um
     * serviço em primeiro plano.
     */
    private fun anunciarQueEstaFalando() {
        if (vozFalando) return
        vozFalando = true
        try {
            startForeground(ID_DA_NOTIFICACAO_DA_VOZ, notificacao())
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível anunciar a voz na notificação", e)
        }
    }

    override fun onDestroy() {
        // O sistema matou o serviço no meio da frase: o motor não pode ficar de pé falando sozinho,
        // e o registro global não pode continuar apontando para um aviso que não existe mais. É
        // este `abandonar` que cala o `TextToSpeech` — sem ele, o motor sobrevive ao serviço e
        // continua dizendo "está na hora do seu remédio" para uma notificação que já sumiu.
        handler.removeCallbacksAndMessages(null)
        aviso?.abandonar()
        aviso = null
        VozDoLembrete.registrar(null)
        super.onDestroy()
    }

    /**
     * O pedido não deu e o serviço sai — mas só se não houver voz nenhuma no ar. Com uma voz
     * falando, o pedido ruim é ignorado: parar o serviço aqui mataria a frase que está tocando, e
     * quem a está ouvindo não tem culpa de o pedido novo ter chegado torto.
     */
    private fun sairSeNaoHavoz() {
        if (aviso != null) return
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * O primeiro plano, que é o que compra o direito de terminar de falar. Se o sistema recusar,
     * não adianta falar: a frase seria cortada no meio pelo processo morrendo, e uma frase cortada
     * é pior que o silêncio — ela ouviria "Está na hora do seu..." e nada mais.
     */
    internal fun tentarSubirEmPrimeiroPlano(): Boolean = try {
        NotificationHelper.ensureChannelVoz(this)
        startForeground(ID_DA_NOTIFICACAO_DA_VOZ, notificacao())
        true
    } catch (e: Exception) {
        Log.w(TAG, "Não foi possível subir a voz em primeiro plano", e)
        false
    }

    private fun encerrar() {
        aviso = null
        VozDoLembrete.registrar(null)
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A notificação que o Android exige do primeiro plano — e só isso.
     *
     * Ela é muda e de importância baixa de propósito: quem avisa é a notificação do lembrete, que
     * está na barra ao lado com o som de alarme. Esta aqui não tem som, não vibra e não se sobrepõe
     * à tela; ela existe para o sistema deixar a frase terminar, e some quando a frase termina.
     *
     * Não leva os botões "Concluir" e "Adiar" de propósito: eles são do lembrete, e duas cópias dos
     * mesmos botões na barra seriam duas ofertas da mesma ação, uma delas em cima de uma
     * notificação que vai sumir sozinha. O toque abre a tarefa, que é o que sobra de útil aqui.
     */
    private fun notificacao(): Notification =
        NotificationCompat.Builder(this, NotificationHelper.CHANNEL_VOZ_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // A frase depende do que está acontecendo de verdade. "Avisando em voz alta" só depois
            // de a fala sair: com o motor que não subiu — aparelho sem voz em português do Brasil,
            // `onInit` que nunca chega — a notificação ficava os 6,5 s do prazo afirmando que
            // estava falando, sem ter falado nada. Era a queixa dela ("o áudio nunca funciona")
            // com o aplicativo dizendo na cara dela que o áudio estava funcionando.
            //
            // Enquanto a fala não sai, o título é o nome do canal — neutro, e o mesmo que ela vê
            // nos Ajustes. Um texto próprio ("Tentei falar, mas o aparelho não tem voz") diria
            // melhor o que houve, mas mora no `strings.xml`, que está fora do escopo deste lote.
            .setContentTitle(
                getString(
                    if (vozFalando) R.string.reminder_speaking_title
                    else R.string.notification_channel_voice,
                ),
            )
            .setContentText(getString(R.string.reminder_speaking_text))
            .setContentIntent(NotificationHelper.openPending(this, ocorrenciaEmCurso.orEmpty()))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            // Sem isto o sistema pode segurar a notificação por 10 segundos antes de mostrá-la, e
            // o serviço some em menos tempo que isso: ela apareceria para anunciar uma voz que já
            // acabou.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
}

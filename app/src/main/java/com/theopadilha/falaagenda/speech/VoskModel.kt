package com.theopadilha.falaagenda.speech

import android.content.Context
import java.io.File
import org.vosk.Model

/**
 * O modelo do Vosk tem 53 MB descompactado e não vai dentro do APK: o aparelho
 * baixa uma vez e o app usa enquanto o diretório estiver completo. Enquanto não
 * estiver, `VoskModel.isInstalled` é falso e o app segue no motor do sistema —
 * é o que mantém este caminho seguro de ligar.
 *
 * O motor resolvido fica guardado aqui, um por processo. Abrir o modelo leva
 * segundos, e antes disto cada toque no microfone resolvia uma instância nova —
 * com o cache do modelo dentro dela, isso quer dizer o modelo carregado do zero a
 * cada escuta, e o anterior (que ninguém fechava) inalcançável. A carga acontece
 * dentro do prazo de preparo do controller, e estourá-lo derrubava o microfone do
 * Vosk no meio da fala dela.
 */
object VoskModel {
    const val DIR_NAME = "vosk-model-small-pt-0.3"

    /** Os dois arquivos que, faltando, fazem o Vosk abrir um modelo pela metade. */
    private val REQUIRED = listOf("final.mdl", "mfcc.conf")

    private var cached: VoskOfflineSpeech? = null

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun isComplete(dir: File): Boolean = REQUIRED.all { File(dir, it).isFile }

    fun isInstalled(context: Context): Boolean = isComplete(dir(context))

    /**
     * O motor offline, ou `null` quando não há modelo instalado.
     *
     * O disco é reconsultado a cada chamada — são dois `stat` —, e é o que faz o
     * download terminado com o app aberto valer na escuta seguinte. O que não se
     * repete é a abertura do modelo: a instância resolvida é a mesma até [release].
     */
    fun offlineSpeech(context: Context): OfflineSpeech? = synchronized(this) {
        if (!isInstalled(context)) return null
        cached?.let { return it }
        VoskOfflineSpeech(context).also { cached = it }
    }

    /**
     * Fecha o modelo nativo e esquece a instância; a próxima escuta abre de novo.
     *
     * O aparelho não avisa quando o processo termina (`onTerminate` não é chamado),
     * então quem chama isto é o aviso de que ele está de saída — ver
     * `FalaAgendaApplication.onTrimMemory`. Fora daí o modelo fica de pé de
     * propósito: recarregá-lo é o que custava a fala dela.
     */
    fun release() = synchronized(this) {
        val aberto = cached ?: return
        cached = null
        aberto.close()
    }
}

/**
 * Abrir o modelo leva segundos e não pode ser por recado: a instância é única no
 * processo (ver [VoskModel.offlineSpeech]) e o modelo abre na primeira escuta que
 * precisar dele; o recognizer (barato) é criado a cada escuta.
 */
private class VoskOfflineSpeech(context: Context) : OfflineSpeech {
    private val appContext = context.applicationContext
    private val modelPath = VoskModel.dir(appContext).absolutePath
    private var model: Model? = null

    /** Quantas escutas estão de pé. O modelo não pode fechar por baixo de uma. */
    @Volatile
    private var escutas = 0

    override fun newSource(): SpeechSource = synchronized(this) {
        escutas += 1
        VoskSpeechSource(appContext, model = { load() }, onFinished = { escutaEncerrada() })
    }

    private fun escutaEncerrada() = synchronized(this) {
        escutas -= 1
    }

    private fun load(): Model = synchronized(this) {
        model?.let { return it }
        val opened = Model(modelPath)
        model = opened
        opened
    }

    /**
     * Fecha o modelo nativo — ninguém chamava isto, e cada toque deixava um modelo de
     * 53 MB inalcançável. Só fecha quando nenhuma escuta está de pé: o recognizer em
     * curso aponta para o modelo, e fechar por baixo dele é usar depois de liberar, no
     * código nativo. A conferência sem lock é de propósito: uma carga em curso segura
     * o lock por segundos, e quem chama aqui é a thread principal.
     */
    fun close() {
        if (escutas > 0) return
        val aberto = synchronized(this) {
            if (escutas > 0) return
            val guardado = model ?: return
            model = null
            guardado
        }
        runCatching { aberto.close() }
    }
}

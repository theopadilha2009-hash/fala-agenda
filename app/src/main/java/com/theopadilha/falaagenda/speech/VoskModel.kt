package com.theopadilha.falaagenda.speech

import android.content.Context
import java.io.File
import org.vosk.Model

/**
 * O modelo do Vosk tem 53 MB descompactado e não vai dentro do APK: o aparelho
 * baixa uma vez e o app usa enquanto o diretório estiver completo. Enquanto não
 * estiver, `VoskModel.isInstalled` é falso e o app segue no motor do sistema —
 * é o que mantém este caminho seguro de ligar.
 */
object VoskModel {
    const val DIR_NAME = "vosk-model-small-pt-0.3"

    /** Os dois arquivos que, faltando, fazem o Vosk abrir um modelo pela metade. */
    private val REQUIRED = listOf("final.mdl", "mfcc.conf")

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun isComplete(dir: File): Boolean = REQUIRED.all { File(dir, it).isFile }

    fun isInstalled(context: Context): Boolean = isComplete(dir(context))

    /** O modelo instalado, ou `null` quando não há — e aí não há motor offline. */
    fun offlineSpeech(context: Context): OfflineSpeech? =
        if (isInstalled(context)) VoskOfflineSpeech(context) else null
}

/**
 * Abrir o modelo leva segundos e não pode ser por recado: fica carregado uma vez
 * por processo, e o recognizer (barato) é criado a cada escuta.
 */
private class VoskOfflineSpeech(context: Context) : OfflineSpeech {
    private val appContext = context.applicationContext
    private val modelPath = VoskModel.dir(appContext).absolutePath
    private var model: Model? = null

    override fun newSource(): SpeechSource = VoskSpeechSource(appContext, model = { load() })

    private fun load(): Model = synchronized(this) {
        model?.let { return it }
        val opened = Model(modelPath)
        model = opened
        opened
    }
}

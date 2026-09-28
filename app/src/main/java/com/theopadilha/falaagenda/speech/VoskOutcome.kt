package com.theopadilha.falaagenda.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * O Vosk devolve JSON, e a chave muda conforme a hora: o parcial traz `partial`,
 * o resultado fechado traz `text`. Só isso interessa aqui — o resto (`result` com
 * palavras e confiança) só existe se `setWords(true)` for ligado.
 *
 * Texto que não é JSON não pode derrubar a escuta: vira string vazia.
 */
internal object VoskOutcome {
    private val json = Json { ignoreUnknownKeys = true }

    fun partial(raw: String): String = pick(raw) { it.partial }

    fun text(raw: String): String = pick(raw) { it.text }

    private fun pick(raw: String, field: (VoskJson) -> String): String =
        runCatching { field(json.decodeFromString<VoskJson>(raw)).trim() }.getOrDefault("")
}

@Serializable
private data class VoskJson(val partial: String = "", val text: String = "")

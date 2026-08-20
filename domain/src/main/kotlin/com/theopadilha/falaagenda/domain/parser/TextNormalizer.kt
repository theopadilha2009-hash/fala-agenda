package com.theopadilha.falaagenda.domain.parser

import java.text.Normalizer

internal object TextNormalizer {
    fun fold(input: String): String {
        val n = Normalizer.normalize(input.lowercase().trim(), Normalizer.Form.NFD)
        return n.replace("\\p{M}+".toRegex(), "")
    }

    fun compactSpaces(input: String): String = input.replace("\\s+".toRegex(), " ").trim()
}
